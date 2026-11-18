package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Timed notices: what a schedule call may carry, how it is kept, and when Later and a restart show it again. */
class NoticeRulesTest {

    private val now = 1_795_000_000_000L

    private fun fields(vararg changes: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "id" to "crm-f-1",
        "group" to "crm",
        "channel" to "crm_reminders",
        "channel_name" to "Reminders",
        "at_ms" to now + 3_600_000L,
        "title" to "Call Rahim Store",
        "body" to "Show samples · 15:00",
        "open" to "lead",
        "open_id" to "l-1",
        "call" to mapOf("label" to "Call"),
        "later" to mapOf(
            "label" to "Later",
            "options" to listOf(
                mapOf("key" to "15m", "label" to "In 15 min", "minutes" to 15),
                mapOf("key" to "1h", "label" to "In 1 hour", "minutes" to 60),
                mapOf("key" to "tomorrow", "label" to "Tomorrow", "at_ms" to now + 86_400_000L),
            ),
        ),
    ) + changes

    @Test
    fun aGoodNoticeIsReadWithItsButtons() {
        val spec = NoticeRules.parse(fields(), now)!!

        assertEquals("crm-f-1", spec.id)
        assertEquals("lead", spec.open)
        assertEquals("l-1", spec.openId)
        assertEquals("Call", spec.callLabel)
        assertEquals("Later", spec.laterLabel)
        assertEquals(listOf("15m", "1h", "tomorrow"), spec.later.map { it.key })
    }

    @Test
    fun aBadIdScreenOrTimeRefusesTheWholeNotice() {
        assertNull(NoticeRules.parse(fields("id" to "bad id"), now))
        assertNull(NoticeRules.parse(fields("open" to "Lead Card"), now))
        assertNull(NoticeRules.parse(fields("open_id" to "../x"), now))
        assertNull(NoticeRules.parse(fields("at_ms" to null), now))
        assertNull(NoticeRules.parse(fields("at_ms" to now + NoticeRules.MAX_AHEAD_MS + 1), now))
        assertNull(NoticeRules.parse(fields("title" to "", "body" to " "), now))
    }

    @Test
    fun withoutCallOrLaterTheNoticeHasNoButtonsAndTextsAreCut() {
        val spec = NoticeRules.parse(fields("call" to null, "later" to null, "title" to "x".repeat(500)), now)!!

        assertNull(spec.callLabel)
        assertNull(spec.laterLabel)
        assertEquals(NoticeRules.MAX_TITLE, spec.title.length)
    }

    @Test
    fun laterIsAfterTheMinutesOrAtTheFixedTimeWhenStillAhead() {
        val spec = NoticeRules.parse(fields(), now)!!

        assertEquals(now + 15 * 60_000L, NoticeRules.laterAt(spec.later[0], now))
        assertEquals(now + 86_400_000L, NoticeRules.laterAt(spec.later[2], now))
        assertEquals(now + 2 * 86_400_000L + 3_600_000L, NoticeRules.laterAt(spec.later[2], now + 2 * 86_400_000L))
    }

    @Test
    fun aKeptNoticeIsReadBackTheSame() {
        val spec = NoticeRules.parse(fields(), now)!!
        val again = NoticeRules.fromJson(NoticeRules.toJson(spec).toString())

        assertNotNull(again)
        assertEquals(spec, again)
    }

    @Test
    fun aRestartArmsWhatIsAheadShowsWhatWasJustMissedAndDropsTheOld() {
        val spec = NoticeRules.parse(fields(), now)!!

        assertEquals("arm", NoticeRules.onStart(spec, now))
        assertEquals("show", NoticeRules.onStart(spec, spec.atMs + 60_000L))
        assertEquals("drop", NoticeRules.onStart(spec, spec.atMs + NoticeRules.MISSED_MS + 1))
    }
}
