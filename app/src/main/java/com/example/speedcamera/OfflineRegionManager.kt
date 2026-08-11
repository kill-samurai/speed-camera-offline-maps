package com.example.speedcamera

import android.app.DownloadManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class OfflineRegionManager(private val context: Context) {
    data class DownloadProgress(val status: Int, val downloadedBytes: Long, val totalBytes: Long)

    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun fetchCatalog(): OfflineCatalog {
        val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SpeedCamera/1.0 (com.example.speedcamera)")
            val status = connection.responseCode
            if (status !in 200..299) error("Offline map catalog returned HTTP $status")
            OfflineCatalog.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun availableBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun requiredTemporaryBytes(item: OfflinePackage): Long =
        item.downloadBytes + item.installedBytes + INSTALL_SAFETY_BYTES

    fun startDownload(item: OfflinePackage): Long {
        val downloadDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("External app storage is unavailable")
        val filename = "${item.regionId}-${item.type}-${item.version}.zip"
        File(downloadDirectory, filename).delete()
        val request = DownloadManager.Request(Uri.parse(item.url))
            .setTitle("${item.regionName} offline map")
            .setDescription(if (item.isFull) "Map, address search, and routing" else "Map only")
            .setMimeType("application/zip")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename)
        val id = downloadManager.enqueue(request)
        preferences.edit()
            .putLong(PENDING_DOWNLOAD_ID, id)
            .putString(PENDING_PACKAGE_JSON, packageToJson(item, filename).toString())
            .apply()
        return id
    }

    fun pendingDownloadId(): Long = preferences.getLong(PENDING_DOWNLOAD_ID, -1L)

    fun downloadProgress(id: Long = pendingDownloadId()): DownloadProgress? {
        if (id < 0) return null
        val query = DownloadManager.Query().setFilterById(id)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DownloadProgress(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloadedBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                ),
                totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }
        return null
    }

    fun installCompletedDownload(id: Long): InstalledOfflineRegion {
        require(id == pendingDownloadId()) { "Unknown offline map download" }
        val progress = downloadProgress(id) ?: error("Offline map download was not found")
        require(progress.status == DownloadManager.STATUS_SUCCESSFUL) { "Offline map download failed" }
        val pending = JSONObject(preferences.getString(PENDING_PACKAGE_JSON, null) ?: error("Missing download metadata"))
        val downloadDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("External app storage is unavailable")
        val archive = File(downloadDirectory, pending.getString("filename"))
        require(archive.isFile) { "Downloaded package file is missing" }
        require(sha256(archive).equals(pending.getString("sha256"), ignoreCase = true)) {
            "Offline package checksum did not match"
        }

        val regionId = pending.getString("regionId")
        val destinationDirectory = File(context.filesDir, "offline_regions/$regionId").apply { mkdirs() }
        val installingDatabase = File(destinationDirectory, "region.db.installing")
        val installingManifest = File(destinationDirectory, "package.json.installing")
        installingDatabase.delete()
        installingManifest.delete()

        ZipInputStream(FileInputStream(archive).buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val output = when (entry.name) {
                    "region.db" -> installingDatabase
                    "package.json" -> installingManifest
                    else -> null
                }
                if (output != null && !entry.isDirectory) {
                    output.outputStream().buffered().use { target -> input.copyTo(target) }
                }
                input.closeEntry()
            }
        }
        require(installingDatabase.isFile && installingManifest.isFile) { "Offline package is incomplete" }
        validateDatabase(installingDatabase, regionId)

        val database = File(destinationDirectory, "region.db")
        val manifest = File(destinationDirectory, "package.json")
        database.delete()
        manifest.delete()
        require(installingDatabase.renameTo(database)) { "Could not install offline database" }
        require(installingManifest.renameTo(manifest)) { "Could not install offline package metadata" }
        archive.delete()
        clearPendingDownload()
        return installedRegion() ?: error("Offline package installation could not be verified")
    }

    fun installedRegion(): InstalledOfflineRegion? {
        val root = File(context.filesDir, "offline_regions")
        val manifest = root.listFiles()?.asSequence()
            ?.map { File(it, "package.json") }
            ?.firstOrNull(File::isFile)
            ?: return null
        return runCatching {
            val json = JSONObject(manifest.readText())
            val database = File(manifest.parentFile, "region.db")
            if (!database.isFile) return null
            InstalledOfflineRegion(
                regionId = json.getString("regionId"),
                regionName = json.getString("name"),
                version = json.getString("version"),
                type = if (json.getJSONArray("capabilities").toString().contains("routing")) "full" else "map",
                installedBytes = database.length(),
                databasePath = database.absolutePath
            )
        }.getOrNull()
    }

    fun deleteInstalledRegion(): Boolean {
        val installed = installedRegion() ?: return false
        return File(installed.databasePath).parentFile?.deleteRecursively() == true
    }

    fun clearFailedDownload() {
        val pendingJson = preferences.getString(PENDING_PACKAGE_JSON, null)
        pendingJson?.let {
            runCatching {
                val filename = JSONObject(it).getString("filename")
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename).delete()
            }
        }
        clearPendingDownload()
    }

    private fun validateDatabase(file: File, expectedRegionId: String) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val region = database.rawQuery("SELECT value FROM metadata WHERE key='regionId'", null).use {
                require(it.moveToFirst()) { "Offline database metadata is missing" }
                JSONObject("{\"value\":${it.getString(0)}}").getString("value")
            }
            require(region == expectedRegionId) { "Offline package contains the wrong region" }
            database.rawQuery("SELECT COUNT(*) FROM roads", null).use {
                require(it.moveToFirst() && it.getLong(0) > 0) { "Offline database contains no roads" }
            }
        } finally {
            database.close()
        }
    }

    private fun clearPendingDownload() {
        preferences.edit().remove(PENDING_DOWNLOAD_ID).remove(PENDING_PACKAGE_JSON).apply()
    }

    private fun packageToJson(item: OfflinePackage, filename: String) = JSONObject().apply {
        put("regionId", item.regionId)
        put("regionName", item.regionName)
        put("version", item.version)
        put("type", item.type)
        put("installedBytes", item.installedBytes)
        put("sha256", item.sha256)
        put("filename", filename)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/kill-samurai/speed-camera-offline-maps/main/catalog.json"
        private const val PREFERENCES_NAME = "offline_region_download"
        private const val PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val PENDING_PACKAGE_JSON = "pending_package_json"
        private const val INSTALL_SAFETY_BYTES = 50L * 1024L * 1024L
    }
}
