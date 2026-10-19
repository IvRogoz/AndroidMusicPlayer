package com.example.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeInputParserTest {

    @Test
    fun parsesSecondsMinutesAndHours() {
        assertEquals(42_000L, TimeInputParser.parseMilliseconds("42"))
        assertEquals(125_000L, TimeInputParser.parseMilliseconds("2:05"))
        assertEquals(3_723_000L, TimeInputParser.parseMilliseconds("1:02:03"))
    }

    @Test
    fun acceptsWhitespace() {
        assertEquals(62_000L, TimeInputParser.parseMilliseconds(" 1 : 02 "))
    }

    @Test
    fun rejectsNegativeOrOutOfRangeComponents() {
        assertNull(TimeInputParser.parseMilliseconds("-1"))
        assertNull(TimeInputParser.parseMilliseconds("1:60"))
        assertNull(TimeInputParser.parseMilliseconds("1:60:00"))
    }

    @Test
    fun rejectsMalformedAndOverflowingValues() {
        assertNull(TimeInputParser.parseMilliseconds(""))
        assertNull(TimeInputParser.parseMilliseconds("1::2"))
        assertNull(TimeInputParser.parseMilliseconds("1:2:3:4"))
        assertNull(TimeInputParser.parseMilliseconds(Long.MAX_VALUE.toString()))
    }
}
