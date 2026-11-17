package dev.enov.ytm

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test fun formatsUnknownAndLongDuration() {
        assertEquals("0:00", time(0))
        assertEquals("3:07", time(187_000))
        assertEquals("61:02", time(3_662_000))
    }
}
