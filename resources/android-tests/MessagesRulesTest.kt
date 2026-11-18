package com.rishadblack.plugins.sylloo_app_native

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone SMS: the parts of a text, the chosen SIM, Android's answers as the ERP's reasons, the office's answers. */
class MessagesRulesTest {

    @Test
    fun aShortEnglishTextIsOnePartAndALongOneIsCutAt153() {
        assertEquals(1, SmsText.parts("Your order 1042 is ready."))
        assertEquals(1, SmsText.parts("x".repeat(160)))
        assertEquals(2, SmsText.parts("x".repeat(161)))
        assertEquals(listOf(153, 8), SmsText.split("x".repeat(161)).map { it.length })
        assertEquals(3, SmsText.parts("x".repeat(307)))
        assertEquals("x".repeat(400), SmsText.split("x".repeat(400)).joinToString(""))
    }

    @Test
    fun anExtensionLetterCountsTwoAndIsNeverCut() {
        assertTrue(SmsText.isGsm("Price: 10€ {ok}"))
        assertEquals(1, SmsText.parts("€".repeat(80)))
        assertEquals(2, SmsText.parts("€".repeat(81)))
        val parts = SmsText.split("a" + "€".repeat(100))
        assertEquals(listOf(77, 24), parts.map { it.length })
        assertEquals("a" + "€".repeat(100), parts.joinToString(""))
    }

    @Test
    fun aBanglaTextIsUcs2Of70AndLongOnesAreCutAt67() {
        val bangla = "আপনার অর্ডার তৈরি। ধন্যবাদ।"
        assertFalse(SmsText.isGsm(bangla))
        assertEquals(1, SmsText.parts("অ".repeat(70)))
        assertEquals(2, SmsText.parts("অ".repeat(71)))
        val long = "অ".repeat(190)
        assertEquals(3, SmsText.parts(long))
        assertEquals(listOf(67, 67, 56), SmsText.split(long).map { it.length })
        assertEquals(long, SmsText.split(long).joinToString(""))
    }

    @Test
    fun anEmojiIsNeverCutInHalf() {
        val text = "a".repeat(66) + "😀" + "b".repeat(10)
        val parts = SmsText.split(text)
        assertEquals(2, parts.size)
        assertEquals(66, parts[0].length)
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun androidsAnswersBecomeTheErpsReasons() {
        assertNull(SmsFailure.reason(SmsFailure.OK))
        assertEquals("radio_off", SmsFailure.reason(2))
        assertEquals("no_service", SmsFailure.reason(4))
        assertEquals("blocked", SmsFailure.reason(5))
        assertEquals("wrong_number", SmsFailure.reason(11))
        assertEquals("no_signal", SmsFailure.reason(17))
        assertEquals("permission", SmsFailure.reason(33))
        assertEquals("no_signal", SmsFailure.reason(101))
        assertEquals("no_service", SmsFailure.reason(120))
        assertEquals("no_balance", SmsFailure.reason(1, 8))
        assertEquals("wrong_number", SmsFailure.reason(1, 1))
        assertEquals("other", SmsFailure.reason(1, null))
        assertEquals("other", SmsFailure.reason(1, 999))
        assertEquals("other", SmsFailure.reason(3))
        SmsFailure.REASONS.forEach { assertTrue(it.matches(Regex("^[a-z_]+$"))) }
        assertEquals(8, SmsFailure.REASONS.size)
    }

    @Test
    fun theFirstFailedPartDecidesTheMessage() {
        assertNull(SmsFailure.first(listOf(null, null)))
        assertEquals("no_signal", SmsFailure.first(listOf(null, "no_signal", "other")))
    }

    @Test
    fun theChosenSimIsTheOneInItsSlot() {
        val sims = listOf(SimInfo(0, "DNA", 11), SimInfo(1, "Saunalahti", 12))
        assertEquals(12, SimChoice.pick(sims, 1)?.subscriptionId)
        assertEquals(11, SimChoice.pick(sims, 0)?.subscriptionId)
        assertNull(SimChoice.pick(sims, 2))
        assertNull(SimChoice.pick(sims, null))
        assertNull(SimChoice.pick(listOf(SimInfo(0, "DNA", 11)), 1))
        assertEquals("SIM 2 · Saunalahti", SimChoice.label(1, "Saunalahti"))
        assertEquals("SIM 1", SimChoice.label(0, " "))
    }

    @Test
    fun thePhoneClaimsOnlyWhenItCanSend() {
        assertNull(SmsReadiness.reason(true, true, false, 0))
        assertNull(SmsReadiness.reason(true, true, false, null))
        assertEquals("permission", SmsReadiness.reason(false, true, false, 0))
        assertEquals("radio_off", SmsReadiness.reason(true, true, true, 0))
        assertEquals("no_sim", SmsReadiness.reason(true, false, false, null))
        assertEquals("no_signal", SmsReadiness.reason(true, true, false, 1))
        assertEquals("no_service", SmsReadiness.reason(true, true, false, 2))
        assertEquals("radio_off", SmsReadiness.reason(true, true, false, 3))
    }

    @Test
    fun androidsThirtyInThirtyMinutesWindow() {
        val now = 10_000_000L
        val times = (0 until 30).map { now - it * 60_000L }
        assertTrue(SendWindow.full(times, now))
        assertEquals(now - 29 * 60_000L + SendWindow.WINDOW_MS, SendWindow.freeAt(times, now))
        assertFalse(SendWindow.full(times.drop(1), now))
        assertFalse(SendWindow.full(times.map { it - SendWindow.WINDOW_MS }, now))
        assertEquals(now, SendWindow.freeAt(emptyList(), now))
    }

    @Test
    fun aMessageIsDeliveredWhenEveryPartIs() {
        assertTrue(DeliveryTally.isDone(0))
        assertFalse(DeliveryTally.isDone(32))
        assertFalse(DeliveryTally.isDone(64))
        assertFalse(DeliveryTally.allDelivered(3, 2))
        assertTrue(DeliveryTally.allDelivered(3, 3))
    }

    @Test
    fun readsWhatWaitsAndTheClaim() {
        val inbox = Inbox.parse(JSONObject("""{"connected":true,"pairing":null,"paused":false,"paused_reason":null,
            "sim":{"slot":1,"label":"SIM 2 · Robi"},"daily_limit":100,"sent_today":4,"pace_seconds":10,
            "messages":[{"id":"01JD8R9X1MAAAAAAAAAAAAAAAA","parts":2,"waiting_since":"2026-11-18T09:00:00Z"},{"id":"bad","parts":1}]}"""))
        assertTrue(inbox.connected)
        assertEquals(1, inbox.simSlot)
        assertEquals(100, inbox.dailyLimit)
        assertEquals(4, inbox.sentToday)
        assertEquals(10, inbox.paceSeconds)
        assertEquals(listOf(WaitingMessage("01JD8R9X1MAAAAAAAAAAAAAAAA", 2)), inbox.messages)

        val asked = Inbox.parse(JSONObject("""{"connected":false,"pairing":{"state":"requested"},"paused":false,"sim":null,"messages":[]}"""))
        assertEquals("requested", asked.pairing)
        assertNull(asked.simSlot)

        val claim = Claimed.parse(JSONObject("""{"id":"01JD8R9X1MAAAAAAAAAAAAAAAA","to":"+8801712345678","text":"Hi","parts":1}"""))
        assertEquals("+8801712345678", claim?.to)
        assertFalse(claim.toString().contains("8801712345678"))
        assertNull(Claimed.parse(JSONObject("""{"id":"01JD8R9X1MAAAAAAAAAAAAAAAA","to":"017123","text":"Hi"}""")))
    }

    @Test
    fun theSettingsPhpHandsOver() {
        assertEquals("https://sylloo.app", MessagesRules.baseUrl("https://sylloo.app/"))
        assertNull(MessagesRules.baseUrl("http://sylloo.app"))
        assertEquals("http://10.0.2.2:8000", MessagesRules.baseUrl("http://10.0.2.2:8000", allowHttp = true))
        assertNull(MessagesRules.baseUrl("https://user@evil.example"))
        assertNull(MessagesRules.deviceKey("short"))
        assertEquals("pT4wXz8QmL2vR6bN0cY3kH7sJ5dF9gA1", MessagesRules.deviceKey("pT4wXz8QmL2vR6bN0cY3kH7sJ5dF9gA1"))
        assertEquals(5L, MessagesRules.checkMinutes(null))
        assertEquals(2L, MessagesRules.checkMinutes(0))
        assertEquals(60L, MessagesRules.checkMinutes(600))
        assertTrue(MessagesRules.isWake(mapOf("wake" to "messages")))
        assertFalse(MessagesRules.isWake(mapOf("title" to "Hi")))
    }
}
