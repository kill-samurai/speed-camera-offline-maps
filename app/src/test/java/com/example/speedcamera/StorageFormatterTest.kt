package com.example.speedcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageFormatterTest {
    @Test
    fun formatsStorageUsingBinaryUnits() {
        assertEquals("500 B", StorageFormatter.display(500))
        assertEquals("1.5 KB", StorageFormatter.display(1536))
        assertEquals("25.5 MB", StorageFormatter.display(26_738_688))
        assertEquals("1.5 GB", StorageFormatter.display(1_610_612_736))
    }
}
