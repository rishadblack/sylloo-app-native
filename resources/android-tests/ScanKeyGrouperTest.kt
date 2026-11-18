package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The typing scanner's grouping (till plan §3.4, §7.2), checked on the computer: `gradlew testDebugUnitTest`. */
class ScanKeyGrouperTest {

    private fun ScanKeyGrouper.type(text: String, start: Long, every: Long, field: Boolean = false): List<ScanKeyGrouper.Step> =
        text.mapIndexed { i, c -> key(ScanKeyGrouper.Key.Typed(c), start + i * every, field) }

    @Test
    fun fastKeysEndingInEnterAreOneCodeAndTheEnterIsKept() {
        val grouper = ScanKeyGrouper()
        val steps = grouper.type("8901234567890", 1_000, 8)

        assertTrue(steps.all { it.code == null && !it.consume })
        val enter = grouper.key(ScanKeyGrouper.Key.Enter, 1_000 + 13 * 8)
        assertEquals("8901234567890", enter.code)
        assertTrue(enter.consume)
        assertEquals(0, grouper.pending)
    }

    @Test
    fun shiftForCapitalsNeitherAddsNorBreaksTheCode() {
        val grouper = ScanKeyGrouper()
        grouper.key(ScanKeyGrouper.Key.Modifier, 990)
        grouper.type("AB", 1_000, 10)
        grouper.key(ScanKeyGrouper.Key.Modifier, 1_015)
        grouper.type("-12", 1_020, 10)

        assertEquals("AB-12", grouper.key(ScanKeyGrouper.Key.Enter, 1_050).code)
    }

    @Test
    fun slowTypingIsNeverACodeAndItsEnterReachesTheApp() {
        val grouper = ScanKeyGrouper()
        val steps = grouper.type("12345", 1_000, 180)

        assertTrue(steps.all { it.code == null })
        val enter = grouper.key(ScanKeyGrouper.Key.Enter, 1_000 + 5 * 180)
        assertNull(enter.code)
        assertFalse(enter.consume)
    }

    @Test
    fun aShortBurstIsNoCode() {
        val grouper = ScanKeyGrouper(minLength = 4)
        grouper.type("12", 1_000, 5)

        val enter = grouper.key(ScanKeyGrouper.Key.Enter, 1_012)
        assertNull(enter.code)
        assertFalse(enter.consume)
    }

    @Test
    fun aPersonsKeyJustBeforeAScanIsLeftOut() {
        val grouper = ScanKeyGrouper()
        grouper.type("x", 1_000, 0)
        grouper.type("4006381333931", 1_400, 7)

        assertEquals("4006381333931", grouper.key(ScanKeyGrouper.Key.Enter, 1_400 + 13 * 7).code)
    }

    @Test
    fun aScannerWithoutEnterEndsByTheQuietGap() {
        val grouper = ScanKeyGrouper(gapMs = 60, endMs = 150)
        grouper.type("ABC123", 1_000, 10)

        assertNull(grouper.flush(1_050 + 100)) // 100 ms quiet: not yet
        assertEquals("ABC123", grouper.flush(1_050 + 150))
        assertEquals(0, grouper.pending)
        assertNull(grouper.flush(5_000))
    }

    @Test
    fun aGapInsideTheKeysEndsTheFirstCodeAndStartsTheNext() {
        val grouper = ScanKeyGrouper(gapMs = 60, endMs = 150)
        grouper.type("1111", 1_000, 10)

        val next = grouper.key(ScanKeyGrouper.Key.Typed('2'), 1_030 + 100)
        assertEquals("1111", next.code)
        assertFalse(next.consume)
        assertEquals(1, grouper.pending)
    }

    @Test
    fun withoutEndOnGapOnlyEnterEndsACode() {
        val grouper = ScanKeyGrouper(endOnGap = false)
        grouper.type("ABC123", 1_000, 10)

        assertNull(grouper.flush(2_000))
        assertNull(grouper.key(ScanKeyGrouper.Key.Enter, 2_001).code)
    }

    @Test
    fun anEnterLateAfterTheLastKeyIsNoLongerTheScannersWithoutEndOnGap() {
        val grouper = ScanKeyGrouper(gapMs = 60, endMs = 150, endOnGap = false)
        grouper.type("ABC123", 1_000, 10)

        val enter = grouper.key(ScanKeyGrouper.Key.Enter, 1_050 + 151)
        assertNull(enter.code)
        assertFalse(enter.consume)
    }

    @Test
    fun anEnterThatComesBeforeTheQuietTimerRanStillEndsTheCode() {
        val grouper = ScanKeyGrouper(gapMs = 60, endMs = 150)
        grouper.type("ABC123", 1_000, 10)

        val enter = grouper.key(ScanKeyGrouper.Key.Enter, 1_050 + 400)
        assertEquals("ABC123", enter.code)
        assertTrue(enter.consume)
        assertEquals(0, grouper.pending)
    }

    @Test
    fun theLateEnterOfACodeTheGapEndedIsKeptFromTheAppAndTheNextCodeStillCounts() {
        val grouper = ScanKeyGrouper(gapMs = 60, endMs = 150)
        grouper.type("2000000000001", 1_000, 5)
        assertEquals("2000000000001", grouper.flush(1_060 + 155))

        val late = grouper.key(ScanKeyGrouper.Key.Enter, 1_060 + 500)
        assertNull(late.code)
        assertTrue(late.consume)

        // A second Enter is a person's again.
        assertFalse(grouper.key(ScanKeyGrouper.Key.Enter, 1_060 + 520).consume)

        grouper.type("2000000000002", 3_000, 5)
        assertEquals("2000000000002", grouper.flush(3_060 + 155))
    }

    @Test
    fun anEnterLongAfterTheCodeOrAfterNewKeysIsAPersons() {
        val grouper = ScanKeyGrouper(gapMs = 60, endMs = 150)
        grouper.type("2000000000001", 1_000, 5)
        grouper.flush(1_215)
        assertFalse(grouper.key(ScanKeyGrouper.Key.Enter, 1_215 + ScanKeyGrouper.LATE_ENTER_MS + 1).consume)

        grouper.type("2000000000001", 5_000, 5)
        grouper.flush(5_215)
        grouper.key(ScanKeyGrouper.Key.Typed('x'), 5_300)
        assertFalse(grouper.key(ScanKeyGrouper.Key.Enter, 5_600).consume)
    }

    @Test
    fun nothingIsGroupedWhileATextFieldHasTheCursor() {
        val grouper = ScanKeyGrouper()
        val steps = grouper.type("8901234567890", 1_000, 8, field = true)
        val enter = grouper.key(ScanKeyGrouper.Key.Enter, 1_110, typingInField = true)

        assertTrue(steps.all { it.code == null && !it.consume })
        assertNull(enter.code)
        assertFalse(enter.consume)
        assertEquals(0, grouper.pending)
    }

    @Test
    fun otherKeysBreakAGroup() {
        val grouper = ScanKeyGrouper()
        grouper.type("1234", 1_000, 5)
        grouper.key(ScanKeyGrouper.Key.Other, 1_021)

        assertNull(grouper.key(ScanKeyGrouper.Key.Enter, 1_025).code)
    }

    @Test
    fun aRunawayGroupLongerThanAnyCodeIsDropped() {
        val grouper = ScanKeyGrouper(maxLength = 8)
        grouper.type("123456789", 1_000, 2)

        assertEquals(1, grouper.pending)
        assertNull(grouper.key(ScanKeyGrouper.Key.Enter, 1_020).code)
        assertEquals(0, grouper.pending)
    }

    @Test
    fun theSettingsFromPhpAreKeptSane() {
        val defaults = ScanKeyGrouper.from(null, null, null, null)
        assertEquals(60L, defaults.gapMs)
        assertEquals(150L, defaults.endMs)
        assertEquals(4, defaults.minLength)
        assertTrue(defaults.endOnGap)

        val odd = ScanKeyGrouper.from(1, 5, 0, false)
        assertEquals(10L, odd.gapMs)
        assertEquals(10L, odd.endMs)
        assertEquals(1, odd.minLength)
        assertFalse(odd.endOnGap)
    }
}
