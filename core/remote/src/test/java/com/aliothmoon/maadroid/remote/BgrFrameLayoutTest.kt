package com.aliothmoon.maadroid.remote

import org.junit.Assert.*
import org.junit.Test

class BgrFrameLayoutTest {
    @Test fun acceptsActualBgrFrameAndPaddedStride() {
        assertEquals(24, BgrFrameLayout.byteCount(longArrayOf(3, 2, 12, 1), 3, 2, 24))
    }

    @Test fun rejectsWrongDimensionsEmptyFrameAndTruncatedMapping() {
        val invalid = listOf(
            longArrayOf(2, 3, 6, 1), longArrayOf(3, 2, 9, 0),
            longArrayOf(3, 2, 8, 1), longArrayOf(3, 2, 12, 1), longArrayOf(3, 2),
            longArrayOf(3, 2, Long.MAX_VALUE, 1),
        )
        invalid.forEach { meta ->
            assertThrows(IllegalArgumentException::class.java) { BgrFrameLayout.byteCount(meta, 3, 2, 18) }
        }
    }
}
