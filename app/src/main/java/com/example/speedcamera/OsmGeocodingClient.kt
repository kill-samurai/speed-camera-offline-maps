package com.example.speedcamera

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Lightweight, user-triggered address search using the public OpenStreetMap
 * Nominatim service. This is intentionally not used for autocomplete.
 */
class OsmGeocodingClient(
    private val baseUrl: String = "https://nominatim.openstreetmap.org/search"
) {
    private val cache = mutableMapOf<String, GeoPoint?>()
    private var lastRequestElapsedMs = 0L

    @Synchronized
    fun geocode(query: String, near: GeoPoint?): GeoPoint? {
        val cacheKey = query.trim().lowercase(Locale.ROOT)
        if (cache.containsKey(cacheKey)) return cache[cacheKey]

        // The public Nominatim service permits at most one request per second.
        val waitMs = 1_000L - (android.os.SystemClock.elapsedRealtime() - lastRequestElapsedMs)
        if (waitMs > 0) Thread.sleep(waitMs)

        val parameters = mutableListOf(
            "q=${encode(query)}",
            "format=jsonv2",
            "limit=1",
            "addressdetails=0"
        )
        near?.let {
            val longitudeRadius = 2.5
            val latitudeRadius = 2.5
            val viewbox = listOf(
                it.longitude - longitudeRadius,
                it.latitude + latitudeRadius,
                it.longitude + longitudeRadius,
                it.latitude - latitudeRadius
            ).joinToString(",")
            parameters += "viewbox=${encode(viewbox)}"
            parameters += "bounded=0"
        }

        val connection = URL("$baseUrl?${parameters.joinToString("&")}")
            .openConnection() as HttpURLConnection
        val result = try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            connection.setRequestProperty(
                "User-Agent",
                "SpeedCamera/1.0 (com.example.speedcamera; personal Android app)"
            )
            lastRequestElapsedMs = android.os.SystemClock.elapsedRealtime()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Address server returned HTTP $status")

            val matches = JSONArray(body)
            if (matches.length() == 0) null else {
                val match = matches.getJSONObject(0)
                GeoPoint(
                    latitude = match.getString("lat").toDouble(),
                    longitude = match.getString("lon").toDouble()
                )
            }
        } finally {
            connection.disconnect()
        }

        cache[cacheKey] = result
        return result
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
