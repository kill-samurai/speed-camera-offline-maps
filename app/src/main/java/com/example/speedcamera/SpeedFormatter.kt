package com.example.speedcamera

import java.util.Locale
import kotlin.math.max

object SpeedFormatter {
    private const val METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR = 3.6f

    fun kilometersPerHour(metersPerSecond: Float): Float =
        max(0f, metersPerSecond) * METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR

    fun display(kilometersPerHour: Float): String =
        String.format(Locale.US, "%.0f km/h", max(0f, kilometersPerHour))
}

