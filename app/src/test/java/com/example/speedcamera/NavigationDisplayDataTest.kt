package com.example.speedcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDisplayDataTest {
    @Test
    fun formatsShortAndLongDistances() {
        assertEquals("350 m", NavigationDisplayData.formatDistance(350))
        assertEquals("1.3 km", NavigationDisplayData.formatDistance(1250))
    }

    @Test
    fun formatsDurations() {
        assertEquals("12 min", NavigationDisplayData.formatDuration(720))
        assertEquals("1 h 30 min", NavigationDisplayData.formatDuration(5400))
    }

    @Test
    fun calculatesDistanceBetweenNearbyPoints() {
        val distance = RouteProgress.distanceMeters(
            GeoPoint(18.4861, -69.9312),
            GeoPoint(18.4871, -69.9312)
        )
        assertEquals(111.0, distance, 2.0)
    }
}
