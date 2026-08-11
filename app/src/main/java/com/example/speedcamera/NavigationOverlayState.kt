package com.example.speedcamera

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latitude: Double, val longitude: Double)

data class RouteStep(
    val point: GeoPoint,
    val instruction: String,
    val routeIndex: Int
)

data class RouteData(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val cumulativeMeters: List<Double>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val destination: GeoPoint,
    val destinationLabel: String
)

data class NavigationDisplayData(
    val route: RouteData? = null,
    val currentLocation: GeoPoint? = null,
    val currentRouteIndex: Int = 0,
    val headingDegrees: Float = 0f,
    val instruction: String? = null,
    val distanceToTurnMeters: Int? = null,
    val distanceToDestinationMeters: Int? = null,
    val timeToDestinationSeconds: Int? = null,
    val isRecalculating: Boolean = false
) {
    val isNavigating: Boolean get() = route != null

    fun primaryLine(): String? {
        if (isRecalculating) return "Recalculating route…"
        val distance = distanceToTurnMeters?.let(::formatDistance)
        return listOfNotNull(distance, instruction).joinToString("  ").ifBlank { null }
    }

    fun secondaryLine(): String? {
        val eta = timeToDestinationSeconds?.let(::formatDuration)
        val remaining = distanceToDestinationMeters?.let(::formatDistance)
        return listOfNotNull(eta, remaining).joinToString(" · ").ifBlank { null }
    }

    companion object {
        fun formatDistance(meters: Int): String = when {
            meters < 1000 -> "${meters.coerceAtLeast(0)} m"
            else -> String.format(Locale.US, "%.1f km", meters / 1000f)
        }

        fun formatDuration(seconds: Int): String {
            val minutes = (seconds.coerceAtLeast(0) + 30) / 60
            return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
        }
    }
}

object NavigationOverlayState {
    @Volatile
    var current = NavigationDisplayData()
        private set

    fun update(data: NavigationDisplayData) {
        current = data
    }

    fun clear() = update(NavigationDisplayData())
}

object RouteProgress {
    data class Match(val index: Int, val distanceMeters: Double)

    fun closestPoint(route: RouteData, location: GeoPoint): Match {
        var closestIndex = 0
        var closestDistance = Double.MAX_VALUE
        route.points.forEachIndexed { index, point ->
            val distance = distanceMeters(location, point)
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }
        return Match(closestIndex, closestDistance)
    }

    fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return earthRadius * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

