package com.example.speedcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedFormatterTest {
    @Test
    fun convertsMetersPerSecondToKilometersPerHour() {
        assertEquals(36f, SpeedFormatter.kilometersPerHour(10f), 0.001f)
    }

    @Test
    fun clampsNegativeSpeeds() {
        assertEquals("0 km/h", SpeedFormatter.display(-12f))
    }
}

