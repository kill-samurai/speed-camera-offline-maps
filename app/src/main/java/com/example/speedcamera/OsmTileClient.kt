package com.example.speedcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class OsmTileClient(context: Context) {
    private val executor = Executors.newFixedThreadPool(2)
    private val pending = mutableSetOf<String>()
    private val retryAfterElapsedMs = mutableMapOf<String, Long>()
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        runCatching {
            if (HttpResponseCache.getInstalled() == null) {
                HttpResponseCache.install(
                    File(context.cacheDir, "openstreetmap_tiles"),
                    DISK_CACHE_BYTES
                )
            }
        }.onFailure { Log.w(LOG_TAG, "Could not initialize the map tile cache", it) }
    }

    fun tile(zoom: Int, unwrappedX: Int, y: Int): Bitmap? {
        val tileCount = 1 shl zoom
        if (y !in 0 until tileCount) return null
        val x = ((unwrappedX % tileCount) + tileCount) % tileCount
        val key = "$zoom/$x/$y"
        memoryCache.get(key)?.let { return it }

        synchronized(pending) {
            if (retryAfterElapsedMs.getOrDefault(key, 0L) > SystemClock.elapsedRealtime()) {
                return null
            }
            if (!pending.add(key)) return null
        }
        executor.execute {
            try {
                val bitmap = downloadTile(zoom, x, y)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    synchronized(pending) { retryAfterElapsedMs.remove(key) }
                } else {
                    delayRetry(key)
                }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Map tile request failed for $key", error)
                delayRetry(key)
            } finally {
                synchronized(pending) { pending.remove(key) }
            }
        }
        return null
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun delayRetry(key: String) {
        synchronized(pending) {
            retryAfterElapsedMs[key] = SystemClock.elapsedRealtime() + FAILED_TILE_RETRY_MS
        }
    }

    private fun downloadTile(zoom: Int, x: Int, y: Int): Bitmap? {
        val connection = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.useCaches = true
            connection.setRequestProperty("Accept", "image/png")
            connection.setRequestProperty(
                "User-Agent",
                "SpeedCamera/1.0 (com.example.speedcamera; personal Android app)"
            )
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(LOG_TAG, "Map tile server returned HTTP $status")
                null
            } else {
                connection.inputStream.use(BitmapFactory::decodeStream)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val LOG_TAG = "SpeedCamera"
        private const val MEMORY_CACHE_BYTES = 12 * 1024 * 1024
        private const val DISK_CACHE_BYTES = 40L * 1024L * 1024L
        private const val FAILED_TILE_RETRY_MS = 60_000L
    }
}
