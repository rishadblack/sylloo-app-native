package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Messages.Work's loop with a pretend ERP and a pretend SIM: one at a time, the chosen SIM, the pace, the daily limit,
 * pause after failures, Android's window, reports kept when the ERP is not reached, and nothing kept after sending.
 */
class MessagesLoopTest {

    private fun id(n: Int) = "01JD8R9X1M" + n.toString().padStart(16, '0')

    private class FakeOffice(
        var inboxes: MutableList<OfficeReply<Inbox>>,
        val claims: MutableMap<String, OfficeReply<Claimed>> = mutableMapOf(),
    ) : MessagesOffice {
        val calls = mutableListOf<String>()
        val reports = mutableListOf<PendingReport>()
        var reportsReach = true

        override fun inbox(): OfficeReply<Inbox> {
            calls += "inbox"
            return if (inboxes.size > 1) inboxes.removeAt(0) else inboxes.first()
        }

        override fun claim(id: String): OfficeReply<Claimed> {
            calls += "claim $id"
            return claims[id] ?: OfficeReply.Ok(Claimed(id, "+8801712345678", "Secret order text $id", 1))
        }

        override fun report(id: String, state: String, reason: String?): OfficeReply<Unit> {
            calls += "report $id $state${reason?.let { " $it" } ?: ""}"
            if (!reportsReach) return OfficeReply.Unreachable
            reports += PendingReport(id, state, reason)
            return OfficeReply.Ok(Unit)
        }
    }

    private class FakeSim(val outcomes: MutableList<SendOutcome> = mutableListOf(), var notReady: String? = null) : SmsOut {
        val sent = mutableListOf<Pair<String, Int>>()
        override fun readiness(slot: Int): String? = notReady
        override fun send(message: Claimed, slot: Int): SendOutcome {
            sent += message.id to slot
            return if (outcomes.isEmpty()) SendOutcome.SENT else outcomes.removeAt(0)
        }
    }

    /** Records everything written, to prove no number or text is ever kept. */
    private class FakeMemory : MessagesMemory {
        val reports = mutableListOf<PendingReport>()
        val times = mutableListOf<Long>()
        val written = mutableListOf<String>()
        var sent = 0
        var failed = 0
        var lastStop: String? = null

        override fun pendingReports() = reports.toList()
        override fun keepReport(report: PendingReport) { reports.removeAll { it.id == report.id }; reports += report; written += report.toString() }
        override fun dropReport(id: String) { reports.removeAll { it.id == id } }
        override fun sendTimes() = times.toList()
        override fun addSendTime(at: Long) { times += at; written += at.toString() }
        override fun count(sent: Boolean, at: Long) { if (sent) this.sent++ else failed++; written += "$sent@$at" }
        override fun stopped(reason: String, at: Long) { lastStop = reason; written += reason }
    }

    private class FakeWaiter(var clock: Long = 1_700_000_000_000L) : Waiter {
        val sleeps = mutableListOf<Long>()
        override fun now() = clock
        override fun sleep(ms: Long) { sleeps += ms; clock += ms }
    }

    private fun inbox(vararg ids: String, slot: Int? = 1, sentToday: Int = 0, limit: Int = 100, pace: Int = 10, paused: Boolean = false, connected: Boolean = true) =
        OfficeReply.Ok(Inbox(connected, null, paused, if (paused) "member" else null, slot, "SIM ${(slot ?: 0) + 1}", limit, sentToday, pace, ids.map { WaitingMessage(it, 1) }))

    @Test
    fun sendsEachWaitingMessageFromTheChosenSimAtThePaceThenAsksAgain() {
        val office = FakeOffice(mutableListOf(inbox(id(1), id(2)), inbox()))
        val sim = FakeSim()
        val waiter = FakeWaiter()

        val result = MessagesLoop(office, sim, FakeMemory(), waiter).run()

        assertEquals(RunResult(2, 0, Stop.NOTHING), result)
        assertEquals(listOf(id(1) to 1, id(2) to 1), sim.sent)
        assertEquals(listOf(10_000L, 10_000L), waiter.sleeps)
        assertEquals(
            listOf("inbox", "claim ${id(1)}", "report ${id(1)} sent", "claim ${id(2)}", "report ${id(2)} sent", "inbox"),
            office.calls,
        )
    }

    @Test
    fun neverWaitsLessThanTheSafePaceEvenWhenTheErpSaysZero() {
        val waiter = FakeWaiter()
        MessagesLoop(FakeOffice(mutableListOf(inbox(id(1), pace = 0), inbox())), FakeSim(), FakeMemory(), waiter).run()
        assertEquals(listOf(3_000L), waiter.sleeps)
    }

    @Test
    fun stopsAtTheDailyLimit() {
        val office = FakeOffice(mutableListOf(inbox(id(1), id(2), id(3), sentToday = 98, limit = 100)))
        val sim = FakeSim()

        val result = MessagesLoop(office, sim, FakeMemory(), FakeWaiter()).run()

        assertEquals(Stop.DAILY_LIMIT, result.stop)
        assertEquals(2, sim.sent.size)
        assertFalse(office.calls.contains("claim ${id(3)}"))
    }

    @Test
    fun claimsNothingWhileTheErpPausedThePhoneOrItIsNotConnected() {
        val paused = FakeOffice(mutableListOf(inbox(id(1), paused = true)))
        assertEquals(Stop.PAUSED, MessagesLoop(paused, FakeSim(), FakeMemory(), FakeWaiter()).run().stop)
        assertEquals(listOf("inbox"), paused.calls)

        val notConnected = FakeOffice(mutableListOf(inbox(id(1), connected = false)))
        assertEquals(Stop.NOT_CONNECTED, MessagesLoop(notConnected, FakeSim(), FakeMemory(), FakeWaiter()).run().stop)
        assertEquals(listOf("inbox"), notConnected.calls)
    }

    @Test
    fun stopsWhenTheErpPausesItAfterFailures() {
        val office = FakeOffice(
            mutableListOf(inbox(id(1), id(2), id(3))),
            mutableMapOf(id(2) to OfficeReply.Refused(409, "sending_paused")),
        )
        val sim = FakeSim(mutableListOf(SendOutcome.failed("no_balance")))

        val result = MessagesLoop(office, sim, FakeMemory(), FakeWaiter()).run()

        assertEquals(RunResult(0, 1, Stop.PAUSED), result)
        assertTrue(office.calls.contains("report ${id(1)} failed no_balance"))
        assertFalse(office.calls.contains("claim ${id(3)}"))
    }

    @Test
    fun pausesItselfAfterFiveFailuresInARowSoAnEmptySimDoesNotBurnTheQueue() {
        val ids = (1..8).map { id(it) }.toTypedArray()
        val office = FakeOffice(mutableListOf(inbox(*ids)))
        val sim = FakeSim(MutableList(8) { SendOutcome.failed("no_balance") })

        val result = MessagesLoop(office, sim, FakeMemory(), FakeWaiter()).run()

        assertEquals(RunResult(0, 5, Stop.FAILURES), result)
        assertEquals(5, sim.sent.size)
    }

    @Test
    fun aSentMessageBetweenFailuresStartsTheCountAgain() {
        val ids = (1..7).map { id(it) }.toTypedArray()
        val outcomes = mutableListOf(
            SendOutcome.failed("no_signal"), SendOutcome.failed("no_signal"), SendOutcome.failed("no_signal"), SendOutcome.failed("no_signal"),
            SendOutcome.SENT, SendOutcome.failed("no_signal"), SendOutcome.failed("no_signal"),
        )
        val result = MessagesLoop(FakeOffice(mutableListOf(inbox(*ids), inbox())), FakeSim(outcomes), FakeMemory(), FakeWaiter()).run()

        assertEquals(RunResult(1, 6, Stop.NOTHING), result)
    }

    @Test
    fun claimsNothingWhenTheSimCannotSendNow() {
        val office = FakeOffice(mutableListOf(inbox(id(1))))
        val memory = FakeMemory()

        val result = MessagesLoop(office, FakeSim(notReady = "no_signal"), memory, FakeWaiter()).run()

        assertEquals("no_signal", result.stop)
        assertEquals(listOf("inbox"), office.calls)
        assertEquals("no_signal", memory.lastStop)
    }

    @Test
    fun claimsNothingWithoutAChosenSim() {
        val office = FakeOffice(mutableListOf(inbox(id(1), slot = null)))
        assertEquals(Stop.NO_SIM, MessagesLoop(office, FakeSim(), FakeMemory(), FakeWaiter()).run().stop)
        assertEquals(listOf("inbox"), office.calls)
    }

    @Test
    fun skipsAMessageTheFallbackOrAnotherClaimTook() {
        val office = FakeOffice(
            mutableListOf(inbox(id(1), id(2)), inbox()),
            mutableMapOf(id(1) to OfficeReply.Refused(409, "message_taken")),
        )
        val sim = FakeSim()

        MessagesLoop(office, sim, FakeMemory(), FakeWaiter()).run()

        assertEquals(listOf(id(2) to 1), sim.sent)
    }

    @Test
    fun waitsWhenAndroidsWindowIsFullAndSaysWhenItFrees() {
        val memory = FakeMemory()
        val waiter = FakeWaiter()
        repeat(30) { memory.times += waiter.clock - (29 - it) * 60_000L }

        val result = MessagesLoop(FakeOffice(mutableListOf(inbox(id(1)))), FakeSim(), memory, waiter).run()

        assertEquals(Stop.ANDROID_LIMIT, result.stop)
        assertEquals(waiter.clock - 29 * 60_000L + SendWindow.WINDOW_MS, result.retryAt)
    }

    @Test
    fun aReportTheErpDidNotGetIsKeptAsIdAndResultAndSentNextRun() {
        val office = FakeOffice(mutableListOf(inbox(id(1)), inbox()))
        office.reportsReach = false
        val memory = FakeMemory()

        MessagesLoop(office, FakeSim(), memory, FakeWaiter()).run()
        assertEquals(listOf(PendingReport(id(1), "sent", null)), memory.reports)

        office.reportsReach = true
        office.calls.clear()
        MessagesLoop(office, FakeSim(), memory, FakeWaiter()).run()
        assertEquals(listOf("report ${id(1)} sent", "inbox"), office.calls)
        assertTrue(memory.reports.isEmpty())
    }

    @Test
    fun noAnswerFromAndroidReportsNothingSoTheMessageIsNeverSentTwice() {
        val office = FakeOffice(mutableListOf(inbox(id(1)), inbox()))
        val result = MessagesLoop(office, FakeSim(mutableListOf(SendOutcome.NO_ANSWER)), FakeMemory(), FakeWaiter()).run()

        assertEquals(RunResult(0, 0, Stop.NOTHING), result)
        assertFalse(office.calls.any { it.startsWith("report") })
    }

    @Test
    fun aReleasedDeviceOrNoInternetEndsTheRun() {
        assertEquals(Stop.RELEASED, MessagesLoop(FakeOffice(mutableListOf(OfficeReply.Refused(401, "device_released"))), FakeSim(), FakeMemory(), FakeWaiter()).run().stop)
        assertEquals(Stop.NO_INTERNET, MessagesLoop(FakeOffice(mutableListOf(OfficeReply.Unreachable)), FakeSim(), FakeMemory(), FakeWaiter()).run().stop)
        assertEquals(Stop.NOT_MESSAGING_PHONE, MessagesLoop(FakeOffice(mutableListOf(OfficeReply.Refused(403, "feature_off"))), FakeSim(), FakeMemory(), FakeWaiter()).run().stop)
    }

    @Test
    fun nothingOfTheNumberOrTheTextIsKeptAfterSending() {
        val office = FakeOffice(mutableListOf(inbox(id(1), id(2)), inbox()))
        office.reportsReach = false
        val memory = FakeMemory()

        MessagesLoop(office, FakeSim(mutableListOf(SendOutcome.SENT, SendOutcome.failed("wrong_number"))), memory, FakeWaiter()).run()

        val kept = memory.written.joinToString("|") + memory.reports.joinToString("|")
        assertTrue(kept.contains(id(1)))
        assertFalse(kept.contains("8801712345678"))
        assertFalse(kept.contains("Secret order text"))
        assertEquals(1, memory.sent)
        assertEquals(1, memory.failed)
    }
}
