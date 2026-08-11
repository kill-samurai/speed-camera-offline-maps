package com.example.speedcamera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class RoutePersistence(context: Context) {
    private val routeFile = context.filesDir.resolve("active_route.json.gz")

    fun save(route: RouteData) {
        val json = JSONObject().apply {
            put("destinationLabel", route.destinationLabel)
            put("destination", pointJson(route.destination))
            put("totalDistanceMeters", route.totalDistanceMeters)
            put("totalDurationSeconds", route.totalDurationSeconds)
            put("points", JSONArray().apply { route.points.forEach { put(pointJson(it)) } })
            put("cumulativeMeters", JSONArray().apply { route.cumulativeMeters.forEach(::put) })
            put("steps", JSONArray().apply {
                route.steps.forEach { step ->
                    put(
                        JSONObject().apply {
                            put("point", pointJson(step.point))
                            put("instruction", step.instruction)
                            put("routeIndex", step.routeIndex)
                        }
                    )
                }
            })
        }
        GZIPOutputStream(routeFile.outputStream().buffered()).bufferedWriter().use { it.write(json.toString()) }
    }

    fun load(): RouteData? = runCatching {
        if (!routeFile.isFile) return null
        val json = GZIPInputStream(routeFile.inputStream().buffered()).bufferedReader().use {
            JSONObject(it.readText())
        }
        val pointsJson = json.getJSONArray("points")
        val points = buildList {
            for (index in 0 until pointsJson.length()) add(parsePoint(pointsJson.getJSONObject(index)))
        }
        val cumulativeJson = json.getJSONArray("cumulativeMeters")
        val cumulative = buildList {
            for (index in 0 until cumulativeJson.length()) add(cumulativeJson.getDouble(index))
        }
        val stepsJson = json.getJSONArray("steps")
        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(index)
                add(
                    RouteStep(
                        point = parsePoint(step.getJSONObject("point")),
                        instruction = step.getString("instruction"),
                        routeIndex = step.getInt("routeIndex")
                    )
                )
            }
        }
        RouteData(
            points = points,
            steps = steps,
            cumulativeMeters = cumulative,
            totalDistanceMeters = json.getDouble("totalDistanceMeters"),
            totalDurationSeconds = json.getDouble("totalDurationSeconds"),
            destination = parsePoint(json.getJSONObject("destination")),
            destinationLabel = json.getString("destinationLabel")
        ).takeIf { it.points.size >= 2 && it.cumulativeMeters.size == it.points.size }
    }.getOrNull()

    fun clear() {
        routeFile.delete()
    }

    private fun pointJson(point: GeoPoint) = JSONObject().apply {
        put("latitude", point.latitude)
        put("longitude", point.longitude)
    }

    private fun parsePoint(json: JSONObject) =
        GeoPoint(json.getDouble("latitude"), json.getDouble("longitude"))
}
