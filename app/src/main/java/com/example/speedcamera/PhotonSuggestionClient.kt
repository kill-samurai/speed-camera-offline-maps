package com.example.speedcamera

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class AddressSuggestion(
    val label: String,
    val point: GeoPoint
) {
    override fun toString(): String = label
}

class PhotonSuggestionClient(
    private val baseUrl: String = "https://photon.komoot.io/api/"
) {
    private val cache = mutableMapOf<String, List<AddressSuggestion>>()

    @Synchronized
    fun suggestions(query: String, near: GeoPoint?): List<AddressSuggestion> {
        val cacheKey = query.trim().lowercase(Locale.ROOT)
        cache[cacheKey]?.let { return it }

        val parameters = mutableListOf(
            "q=${encode(query)}",
            "limit=5",
            "lang=${encode(supportedLanguage())}"
        )
        near?.let {
            parameters += "lat=${it.latitude}"
            parameters += "lon=${it.longitude}"
        }

        val connection = URL("$baseUrl?${parameters.joinToString("&")}")
            .openConnection() as HttpURLConnection
        val suggestions = try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "SpeedCamera/1.0 (com.example.speedcamera; personal Android app)"
            )
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Suggestion server returned HTTP $status")
            parseSuggestions(JSONObject(body))
        } finally {
            connection.disconnect()
        }

        cache[cacheKey] = suggestions
        return suggestions
    }

    private fun parseSuggestions(root: JSONObject): List<AddressSuggestion> {
        val features = root.getJSONArray("features")
        return buildList {
            for (index in 0 until features.length()) {
                val feature = features.getJSONObject(index)
                val properties = feature.getJSONObject("properties")
                val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
                val label = suggestionLabel(properties)
                if (label.isNotBlank()) {
                    add(
                        AddressSuggestion(
                            label = label,
                            point = GeoPoint(
                                latitude = coordinates.getDouble(1),
                                longitude = coordinates.getDouble(0)
                            )
                        )
                    )
                }
            }
        }.distinctBy { it.label to it.point }
    }

    private fun suggestionLabel(properties: JSONObject): String {
        fun value(key: String) = properties.optString(key).trim().takeIf { it.isNotEmpty() }

        val name = value("name")
        val street = value("street")
        val houseNumber = value("housenumber")
        val firstLine = when {
            name != null -> name
            street != null && houseNumber != null -> "$street $houseNumber"
            street != null -> street
            else -> value("locality")
        }
        return listOfNotNull(
            firstLine,
            value("city") ?: value("district"),
            value("state"),
            value("country")
        ).distinct().joinToString(", ")
    }

    private fun supportedLanguage(): String = when (Locale.getDefault().language) {
        "de", "en", "fr", "it" -> Locale.getDefault().language
        else -> "en"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
