package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work calls (crm-call-recording-plan.md CR2): which row of the call list is a work call, and that a private call is
 * never handed over, not even its number or its time.
 */
class CallRulesTest {

    private val since = 1_795_000_000_000L
    private val keys = setOf("711000111", "811000222")

    private fun row(id: Long, number: String?, type: Int = CallRules.OUTGOING, at: Long = since + id * 60_000L, seconds: Long = 65L) =
        CallRow(id, number, type, at, seconds)

    @Test
    fun theKeyIsTheLastDigitsOfTheNumberAndShortNumbersHaveNone() {
        assertEquals("711000111", CallRules.key("+880 1711-000111", 9))
        assertEquals("711000111", CallRules.key("01711000111", 9))
        assertNull(CallRules.key("16216", 9))
        assertNull(CallRules.key(null, 9))
        assertNull(CallRules.key("", 9))
        assertEquals(9, CallRules.digits(null))
        assertEquals(6, CallRules.digits(2))
        assertEquals(15, CallRules.digits(40))
    }

    @Test
    fun onlyKeysOfTheRightLengthAndOnlyDigitsAreTaken() {
        assertEquals(setOf("711000111"), CallRules.keys(listOf("711000111", "71100011", "71100011x", 711000111, null), 9))
    }

    @Test
    fun aPrivateCallIsNeverHandedOverButTheReadMovesPastIt() {
        val pick = CallRules.pick(listOf(row(1, "01911999999"), row(2, "01711000111"), row(3, "+8801999888777")), keys, 9, 0, since, 50)

        assertEquals(listOf("2-${since + 120_000L}"), pick.calls.map { it.callId })
        assertEquals("711000111", pick.calls[0].key)
        assertEquals("out", pick.calls[0].direction)
        assertEquals(3L, pick.lastId)
        assertFalse(pick.more)
        assertFalse(pick.calls.any { it.number.contains("999") })
    }

    @Test
    fun incomingMissedAndRejectedAreInAndVoicemailOrBlockedAreNotCalls() {
        val rows = listOf(
            row(1, "01711000111", CallRules.INCOMING),
            row(2, "01711000111", CallRules.MISSED, seconds = 0),
            row(3, "01711000111", CallRules.REJECTED, seconds = 0),
            row(4, "01711000111", 4),
            row(5, "01711000111", 6),
            row(6, "01811000222", CallRules.OUTGOING),
        )
        val pick = CallRules.pick(rows, keys, 9, 0, since, 50)

        assertEquals(listOf("in", "in", "in", "out"), pick.calls.map { it.direction })
        assertEquals(listOf(65L, 0L, 0L, 65L), pick.calls.map { it.durationSeconds })
        assertEquals(6L, pick.lastId)
    }

    @Test
    fun onlyCallsAfterTheLastReadAndNotBeforeTheYes() {
        val rows = listOf(row(1, "01711000111", at = since - 1), row(2, "01711000111"), row(3, "01811000222"))

        assertEquals(listOf(2L, 3L), CallRules.pick(rows, keys, 9, 0, since, 50).calls.map { it.callId.substringBefore('-').toLong() })
        assertEquals(listOf(3L), CallRules.pick(rows, keys, 9, 2, since, 50).calls.map { it.callId.substringBefore('-').toLong() })
        assertEquals(3L, CallRules.pick(rows, keys, 9, 3, since, 50).lastId)
    }

    @Test
    fun aFullReadStopsBeforeTheCallThatDidNotFitSoTheNextReadGetsIt() {
        val rows = listOf(row(1, "01711000111"), row(2, "01911999999"), row(3, "01811000222"), row(4, "01711000111"))
        val first = CallRules.pick(rows, keys, 9, 0, since, 1)
        val second = CallRules.pick(rows, keys, 9, first.lastId, since, 1)

        assertEquals(1, first.calls.size)
        assertEquals(2L, first.lastId)
        assertTrue(first.more)
        assertEquals("811000222", second.calls.single().key)
        assertEquals(3L, second.lastId)
    }

    @Test
    fun withoutKeysNothingIsHandedOver() {
        val pick = CallRules.pick(listOf(row(1, "01711000111"), row(2, null)), emptySet(), 9, 0, since, 50)

        assertTrue(pick.calls.isEmpty())
        assertEquals(2L, pick.lastId)
    }

    @Test
    fun aCallEndsWhenThePhoneIsIdleAfterRingingOrACall() {
        assertTrue(CallRules.ended("OFFHOOK", "IDLE"))
        assertTrue(CallRules.ended("RINGING", "IDLE"))
        assertFalse(CallRules.ended("IDLE", "IDLE"))
        assertFalse(CallRules.ended(null, "IDLE"))
        assertFalse(CallRules.ended("RINGING", "OFFHOOK"))
    }
}
