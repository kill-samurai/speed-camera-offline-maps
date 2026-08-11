package com.example.speedcamera

import org.json.JSONObject
import java.util.Locale

data class OfflinePackage(
    val regionId: String,
    val regionName: String,
    val version: String,
    val type: String,
    val capabilities: Set<String>,
    val downloadBytes: Long,
    val installedBytes: Long,
    val sha256: String,
    val url: String
) {
    val isFull: Boolean get() = "routing" in capabilities
}

data class OfflineCatalog(val packages: List<OfflinePackage>) {
    companion object {
        fun parse(json: String): OfflineCatalog {
            val root = JSONObject(json)
            require(root.getInt("formatVersion") == 1) { "Unsupported offline catalog format" }
            val packages = buildList {
                val regions = root.getJSONArray("regions")
                for (regionIndex in 0 until regions.length()) {
                    val region = regions.getJSONObject(regionIndex)
                    val regionPackages = region.getJSONArray("packages")
                    for (packageIndex in 0 until regionPackages.length()) {
                        val item = regionPackages.getJSONObject(packageIndex)
                        val capabilities = buildSet {
                            val values = item.getJSONArray("capabilities")
                            for (index in 0 until values.length()) add(values.getString(index))
                        }
                        add(
                            OfflinePackage(
                                regionId = region.getString("id"),
                                regionName = region.getString("name"),
                                version = region.getString("version"),
                                type = item.getString("type"),
                                capabilities = capabilities,
                                downloadBytes = item.getLong("downloadBytes"),
                                installedBytes = item.getLong("installedBytes"),
                                sha256 = item.getString("sha256"),
                                url = item.getString("url")
                            )
                        )
                    }
                }
            }
            return OfflineCatalog(packages)
        }
    }
}

data class InstalledOfflineRegion(
    val regionId: String,
    val regionName: String,
    val version: String,
    val type: String,
    val installedBytes: Long,
    val databasePath: String
)

object StorageFormatter {
    fun display(bytes: Long): String {
        val value = bytes.coerceAtLeast(0)
        return when {
            value >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", value / 1_073_741_824.0)
            value >= 1_048_576L -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
            value >= 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
            else -> "$value B"
        }
    }
}
