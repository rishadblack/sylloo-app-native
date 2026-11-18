package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's one line back to the open camera screen (remote-scanner.md §5): colour, one short line, the right screen. */
class ScanAnswerTest {

    @Test
    fun theKindDecidesTheColourAndAnUnknownKindIsOk() {
        assertEquals(ScanAnswer.GREEN, ScanAnswer.color("ok"))
        assertEquals(ScanAnswer.AMBER, ScanAnswer.color("warn"))
        assertEquals(ScanAnswer.RED, ScanAnswer.color("error"))
        assertEquals("ok", ScanAnswer.kind("purple"))
        assertEquals("ok", ScanAnswer.kind(null))
    }

    @Test
    fun theTextIsOneShortLine() {
        assertEquals("Added: Rice 5 kg × 2", ScanAnswer.text("  Added: Rice 5 kg × 2 "))
        assertEquals("No product with code 123", ScanAnswer.text("No product\nwith   code 123"))
        assertNull(ScanAnswer.text("   "))
        assertNull(ScanAnswer.text(null))
        val long = ScanAnswer.text("যোগ হয়েছে: " + "চাল ".repeat(40))!!
        assertEquals(ScanAnswer.MAX, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals(2000L, ScanAnswer.SHOW_MS)
    }

    @Test
    fun anAnswerForAnotherScreenIsDropped() {
        assertTrue(ScanAnswer.forScreen("sell", "sell"))
        assertTrue(ScanAnswer.forScreen(null, "sell"))
        assertTrue(ScanAnswer.forScreen("sell", null))
        assertFalse(ScanAnswer.forScreen("find", "sell"))
    }
}
