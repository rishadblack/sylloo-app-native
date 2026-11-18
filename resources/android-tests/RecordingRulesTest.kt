package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call's recording (crm-call-recording-plan.md §5 R2, CR3): which file of the chosen folder is the recording of one
 * saved work call, and that another person's call or a doubtful file is never taken.
 */
class RecordingRulesTest {

    // Monday 2 March 2026 08:50:00 UTC; the phone is in Dhaka (UTC+6), so its recorder names the file 14:50:00.
    private val start = 1_772_441_400_000L
    private val dhaka = 6 * 3_600_000L
    private val key = "711000111"
    private val end = start + 245_000L

    private fun file(name: String, modified: Long = end + 2_000L, size: Long = 500_000L) = RecordingFile(name, modified, size)

    @Test
    fun theTimeInANameIsReadAsThePhonesLocalTime() {
        assertEquals(start, RecordingRules.nameTime("Call recording Karim_20260302_145000.m4a", dhaka))
        assertEquals(start, RecordingRules.nameTime("260302_145000.amr", dhaka))
        assertEquals(start, RecordingRules.nameTime("2026-03-02 14-50-00 01711000111.mp3", dhaka))
        assertEquals(start + dhaka, RecordingRules.nameTime("20260302145000.m4a", 0))
        assertNull(RecordingRules.nameTime("Karim.m4a", dhaka))
        assertNull(RecordingRules.nameTime("20261302_145000.m4a", dhaka))
    }

    @Test
    fun aLongNumberThatIsNotTheCallsIsAnotherPersonsCall() {
        assertFalse(RecordingRules.otherNumber("01711000111_20260302_145000.m4a", key))
        assertFalse(RecordingRules.otherNumber("Karim Stores 260302_145000.m4a", key))
        assertTrue(RecordingRules.otherNumber("01911999999_20260302_145000.m4a", key))
        assertTrue(RecordingRules.otherNumber("+8801911999999.m4a", null))
    }

    @Test
    fun theFileNamedForTheCallsStartIsTaken() {
        val match = RecordingRules.match(listOf(
            file("Call_01711000111_20260302_145030.m4a"),
            file("Call_01711000111_20260302_120000.m4a"),
            file("notes.txt"),
        ), start, 245, key, dhaka)

        assertEquals("found", match.status)
        assertEquals("Call_01711000111_20260302_145030.m4a", match.file?.name)
    }

    @Test
    fun withoutATimeInTheNameTheFileClosedAtTheCallsEndIsTaken() {
        val match = RecordingFile("Karim Stores.m4a", end + 3_000L, 400_000L)

        assertEquals(match, RecordingRules.match(listOf(match, file("Old.m4a", end - 600_000L)), start, 245, key, dhaka).file)
        assertEquals("none", RecordingRules.match(listOf(file("Old.m4a", end - 600_000L), file("Later.m4a", end + 600_000L)), start, 245, key, dhaka).status)
    }

    @Test
    fun anotherPersonsCallOrADoubtIsNeverTaken() {
        assertEquals("none", RecordingRules.match(listOf(file("01911999999_20260302_145000.m4a")), start, 245, key, dhaka).status)
        assertEquals("ambiguous", RecordingRules.match(listOf(file("A 20260302_145000.m4a"), file("B 20260302_145020.m4a")), start, 245, key, dhaka).status)
        assertEquals("ambiguous", RecordingRules.match(listOf(file("A.m4a"), file("B.m4a", end + 10_000L)), start, 245, key, dhaka).status)
        // A name with another time is not this call, even when the file closed at the call's end.
        assertEquals("none", RecordingRules.match(listOf(file("C 20260302_110000.m4a")), start, 245, key, dhaka).status)
    }

    @Test
    fun aCallWithoutSecondsATooLongCallAndBadFilesHaveNoRecording() {
        assertEquals("no_call", RecordingRules.match(listOf(file("A.m4a")), start, 0, key, dhaka).status)
        assertEquals("too_long", RecordingRules.match(listOf(file("A.m4a")), start, 61 * 60L, key, dhaka, maxMinutes = 60).status)
        assertEquals("none", RecordingRules.match(listOf(file("A.m4a", size = 0)), start, 245, key, dhaka).status)
        assertEquals("none", RecordingRules.match(listOf(file("A.m4a", size = 50L * 1024 * 1024)), start, 245, key, dhaka).status)
        assertEquals("none", RecordingRules.match(listOf(file("A.pdf")), start, 245, key, dhaka).status)
    }

    @Test
    fun aacIsReWrappedAsMp4AndAmrIsNotKept() {
        assertEquals("mp4", RecordingRules.target("m4a"))
        assertEquals("mp4", RecordingRules.target("aac"))
        assertEquals("mp4", RecordingRules.target("3gp"))
        assertEquals("mp3", RecordingRules.target("mp3"))
        assertNull(RecordingRules.target("amr"))
        assertNull(RecordingRules.target("opus"))
    }

    @Test
    fun theCopyIsNamedOnlyFromASafeCallId() {
        assertEquals("7_41-1772441400000.mp4", RecordingRules.copyName("7:41-1772441400000", "mp4"))
        assertNull(RecordingRules.copyName("../etc/passwd", "mp4"))
        assertNull(RecordingRules.copyName("", "mp4"))
        assertNull(RecordingRules.copyName(null, "mp4"))
    }
}
