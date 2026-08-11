package com.example.speedcamera

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OsrmRoutingClient(
    private val baseUrl: String = "https://router.project-osrm.org"
) {
    fun route(origin: GeoPoint, destination: GeoPoint, destinationLabel: String): RouteData {
        val coordinates = "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"
        val url = URL(
            "$baseUrl/route/v1/driving/$coordinates" +
                "?overview=full&geometries=geojson&steps=true"
        )
        val json = requestJson(url)
        if (json.optString("code") != "Ok") {
            error(json.optString("message", "No route found"))
        }

        val routeJson = json.getJSONArray("routes").getJSONObject(0)
        val coordinateJson = routeJson.getJSONObject("geometry").getJSONArray("coordinates")
        val points = buildList {
            for (index in 0 until coordinateJson.length()) {
                val pair = coordinateJson.getJSONArray(index)
                add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
            }
        }
        require(points.size >= 2) { "The route did not contain enough points" }

        val cumulative = ArrayList<Double>(points.size).apply {
            add(0.0)
            for (index in 1 until points.size) {
                add(last() + RouteProgress.distanceMeters(points[index - 1], points[index]))
            }
        }

        val rawSteps = routeJson.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        var searchStart = 0
        val steps = buildList {
            for (index in 0 until rawSteps.length()) {
                val stepJson = rawSteps.getJSONObject(index)
                val maneuver = stepJson.getJSONObject("maneuver")
                val location = maneuver.getJSONArray("location")
                val point = GeoPoint(location.getDouble(1), location.getDouble(0))
                val routeIndex = closestRouteIndex(points, point, searchStart)
                searchStart = routeIndex
                add(
                    RouteStep(
                        point = point,
                        instruction = instructionFor(
                            maneuver.optString("type"),
                            maneuver.optString("modifier"),
                            stepJson.optString("name")
                        ),
                        routeIndex = routeIndex
                    )
                )
            }
        }

        return RouteData(
            points = points,
            steps = steps,
            cumulativeMeters = cumulative,
            totalDistanceMeters = routeJson.getDouble("distance"),
            totalDurationSeconds = routeJson.getDouble("duration"),
            destination = destination,
            destinationLabel = destinationLabel
        )
    }

    private fun requestJson(url: URL): JSONObject {
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SpeedCamera/1.0 (Android personal navigation app)")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) error("Routing server returned HTTP $status")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun closestRouteIndex(points: List<GeoPoint>, target: GeoPoint, start: Int): Int {
        var bestIndex = start.coerceIn(points.indices)
        var bestDistance = Double.MAX_VALUE
        for (index in bestIndex until points.size) {
            val distance = RouteProgress.distanceMeters(points[index], target)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
            if (index > bestIndex + 80 && bestDistance < 30.0) break
        }
        return bestIndex
    }

    private fun instructionFor(type: String, modifier: String, roadName: String): String {
        val road = roadName.takeIf { it.isNotBlank() }?.let { " onto $it" }.orEmpty()
        val direction = modifier.replace('-', ' ').takeIf { it.isNotBlank() }.orEmpty()
        return when (type) {
            "depart" -> if (roadName.isBlank()) "Start route" else "Start on $roadName"
            "arrive" -> "Arrive at destination"
            "turn" -> "Turn $direction$road"
            "continue", "new name" -> "Continue $direction$road".replace("  ", " ")
            "merge" -> "Merge $direction$road".replace("  ", " ")
            "on ramp" -> "Take the ramp $direction$road".replace("  ", " ")
            "off ramp" -> "Take the exit $direction$road".replace("  ", " ")
            "fork" -> "Keep $direction$road".replace("  ", " ")
            "end of road" -> "At the end, turn $direction$road".replace("  ", " ")
            "roundabout", "rotary" -> "Enter the roundabout$road"
            else -> if (roadName.isBlank()) "Continue route" else "Continue on $roadName"
        }
    }
}

