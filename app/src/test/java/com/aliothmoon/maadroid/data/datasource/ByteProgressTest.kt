package com.aliothmoon.maadroid.data.datasource

import org.junit.Assert.*
import org.junit.Test

class ByteProgressTest {
    @Test fun unknownLengthNeverInventsPercentOrTotal() {
        for (total in listOf(0L, -1L)) {
            val bytes = ByteProgress(3L * 1024 * 1024, total)
            assertNull(bytes.fraction)
            assertNull(bytes.percent)
            assertNull(bytes.totalText)
            assertEquals("3.0 MB", bytes.downloadedText)
        }
    }

    @Test fun knownLengthUsesBytesEvenBeforeWholePercentChanges() {
        val bytes = ByteProgress(1, 1000)
        assertEquals(0, bytes.percent)
        assertEquals(0.001f, bytes.fraction!!, 0.00001f)
        assertEquals("1 B", bytes.downloadedText)
        assertEquals("1000 B", bytes.totalText)
    }

    @Test fun fractionClampsAndLargeByteCountsDoNotOverflow() {
        assertEquals(1f, ByteProgress(20, 10).fraction)
        assertEquals(100, ByteProgress(Long.MAX_VALUE, Long.MAX_VALUE).percent)
        assertEquals(0f, ByteProgress(-1, 10).fraction)
    }
}
