package com.rishadblack.plugins.sylloo_app_native

import org.json.JSONArray
import org.json.JSONObject

/*
 * The pure rules of phone SMS (phone-messaging-plan.md §3, §5, §7; device-api.md §7), checked on the computer
 * (`MessagesRulesTest`, `MessagesLoopTest`). No Android class here: the Android parts (SmsManager, WorkManager, the
 * alarm) live in SmsFunctions.kt and MessagesFunctions.kt and only feed these rules.
 */

/**
 * How many parts a text takes, as the network counts them: GSM 7-bit text 160 letters in one part, 153 per part when
 * long (an extension letter such as `€` or `{` counts two and is never cut); anything else (Bangla, emoji) as UCS-2,
 * 70 in one part, 67 per part (a letter made of two UTF-16 units is never cut). Android's own `divideMessage` is what
 * the phone sends with (it may use a national table); this is the count the ERP and the tests agree on.
 */
object SmsText {
    const val GSM_SINGLE = 160
    const val GSM_PART = 153
    const val UCS2_SINGLE = 70
    const val UCS2_PART = 67

    private const val BASIC = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val EXTENSION = "^{}\\[~]|€\u000C"

    fun isGsm(text: String): Boolean = text.all { BASIC.indexOf(it) >= 0 || EXTENSION.indexOf(it) >= 0 }

    fun parts(text: String): Int = split(text).size

    /** The text cut into its parts (one part for an empty text). */
    fun split(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        return if (isGsm(text)) splitGsm(text) else splitUcs2(text)
    }

    private fun septets(c: Char): Int = if (EXTENSION.indexOf(c) >= 0) 2 else 1

    private fun splitGsm(text: String): List<String> {
        if (text.sumOf { septets(it) } <= GSM_SINGLE) return listOf(text)
        val parts = mutableListOf<String>()
        val part = StringBuilder()
        var used = 0
        for (c in text) {
            val size = septets(c)
            if (used + size > GSM_PART) {
                parts.add(part.toString()); part.setLength(0); used = 0
            }
            part.append(c); used += size
        }
        if (part.isNotEmpty()) parts.add(part.toString())
        return parts
    }

    private fun splitUcs2(text: String): List<String> {
        if (text.length <= UCS2_SINGLE) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + UCS2_PART, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end -= 1
            parts.add(text.substring(start, end))
            start = end
        }
        return parts
    }
}

/**
 * Android's answer to one SMS part, in the ERP's plain reasons (device-api.md §7 `report`): `no_signal`, `no_service`,
 * `no_balance`, `wrong_number`, `radio_off`, `blocked`, `permission`, `other`. The codes are SmsManager's `RESULT_*`
 * (checked against android.jar 36); `errorCode` is the extra the network's own cause comes in, read only for a
 * generic failure. "No balance" can only be guessed: operators answer an empty prepaid SIM with "operator determined
 * barring" (cause 8); anything else unclear stays `other`.
 */
object SmsFailure {
    const val NO_SIGNAL = "no_signal"
    const val NO_SERVICE = "no_service"
    const val NO_BALANCE = "no_balance"
    const val WRONG_NUMBER = "wrong_number"
    const val RADIO_OFF = "radio_off"
    const val BLOCKED = "blocked"
    const val PERMISSION = "permission"
    const val OTHER = "other"

    val REASONS = listOf(NO_SIGNAL, NO_SERVICE, NO_BALANCE, WRONG_NUMBER, RADIO_OFF, BLOCKED, PERMISSION, OTHER)

    /** Activity.RESULT_OK: the network took the part. */
    const val OK = -1

    private val BY_CODE = mapOf(
        2 to RADIO_OFF,          // RESULT_ERROR_RADIO_OFF
        4 to NO_SERVICE,         // RESULT_ERROR_NO_SERVICE
        5 to BLOCKED,            // RESULT_ERROR_LIMIT_EXCEEDED (Android's own "many SMS" check said no)
        6 to BLOCKED,            // RESULT_ERROR_FDN_CHECK_FAILURE
        7 to BLOCKED,            // RESULT_ERROR_SHORT_CODE_NOT_ALLOWED
        8 to BLOCKED,            // RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED
        9 to RADIO_OFF,          // RESULT_RADIO_NOT_AVAILABLE
        10 to NO_SERVICE,        // RESULT_NETWORK_REJECT
        11 to WRONG_NUMBER,      // RESULT_INVALID_ARGUMENTS
        17 to NO_SIGNAL,         // RESULT_NETWORK_ERROR
        20 to BLOCKED,           // RESULT_OPERATION_NOT_ALLOWED
        29 to BLOCKED,           // RESULT_SMS_BLOCKED_DURING_EMERGENCY
        30 to NO_SIGNAL,         // RESULT_SMS_SEND_RETRY_FAILED
        32 to PERMISSION,        // RESULT_NO_DEFAULT_SMS_APP
        33 to PERMISSION,        // RESULT_USER_NOT_ALLOWED
        100 to RADIO_OFF,        // RESULT_RIL_RADIO_NOT_AVAILABLE
        101 to NO_SIGNAL,        // RESULT_RIL_SMS_SEND_FAIL_RETRY
        102 to NO_SERVICE,       // RESULT_RIL_NETWORK_REJECT
        104 to WRONG_NUMBER,     // RESULT_RIL_INVALID_ARGUMENTS
        106 to BLOCKED,          // RESULT_RIL_REQUEST_RATE_LIMITED
        112 to NO_SIGNAL,        // RESULT_RIL_NETWORK_ERR
        116 to NO_SIGNAL,        // RESULT_RIL_NETWORK_NOT_READY
        117 to BLOCKED,          // RESULT_RIL_OPERATION_NOT_ALLOWED
        120 to NO_SERVICE,       // RESULT_RIL_SIM_ABSENT
        122 to BLOCKED,          // RESULT_RIL_ACCESS_BARRED
        128 to NO_SERVICE,       // RESULT_RIL_SUBSCRIPTION_NOT_AVAILABLE
        129 to NO_SERVICE,       // RESULT_RIL_SIM_ERROR
        130 to NO_SERVICE,       // RESULT_RIL_INVALID_SIM_STATE
        134 to NO_SERVICE,       // RESULT_RIL_NO_SUBSCRIPTION
        135 to NO_SIGNAL,        // RESULT_RIL_NO_NETWORK_FOUND
    )

    /** The network's cause (3GPP TS 24.011 RP-cause) behind a generic failure. */
    private val BY_CAUSE = mapOf(
        1 to WRONG_NUMBER,       // unassigned number
        8 to NO_BALANCE,         // operator determined barring (an empty prepaid SIM)
        10 to BLOCKED,           // call barred
        21 to BLOCKED,           // short message transfer rejected
        28 to WRONG_NUMBER,      // unidentified subscriber
        29 to BLOCKED,           // facility rejected
        38 to NO_SIGNAL,         // network out of order
        41 to NO_SIGNAL,         // temporary failure
        42 to NO_SIGNAL,         // congestion
        47 to NO_SIGNAL,         // resources unavailable
        50 to BLOCKED,           // requested facility not subscribed
    )

    /** null when the part was sent. */
    fun reason(resultCode: Int, errorCode: Int? = null): String? {
        if (resultCode == OK) return null
        BY_CODE[resultCode]?.let { return it }
        if (errorCode != null) BY_CAUSE[errorCode]?.let { return it }
        return OTHER
    }

    /** One reason for a message of many parts: the first part that failed decides; all sent = null. */
    fun first(reasons: List<String?>): String? = reasons.firstOrNull { it != null }
}

/** One SIM of the phone: Android's slot (0 = SIM 1) and the operator's name. Android gives no SIM number to an app. */
data class SimInfo(val slot: Int, val label: String, val subscriptionId: Int)

object SimChoice {
    /** The SIM in the chosen slot; null when that slot has no SIM now (taken out, switched off). */
    fun pick(sims: List<SimInfo>, slot: Int?): SimInfo? = if (slot == null) null else sims.firstOrNull { it.slot == slot }

    /** What the accept screen and the ERP show for a SIM: "SIM 1 · Grameenphone" (the operator, never a number). */
    fun label(slot: Int, operator: String?): String {
        val name = operator?.trim()?.take(40).orEmpty()
        return if (name.isEmpty()) "SIM ${slot + 1}" else "SIM ${slot + 1} · $name"
    }
}

/**
 * Whether the phone can send now, checked before a message is claimed (a claimed message never falls back, so the
 * phone only claims what it can send; what it leaves waits for the ERP's fallback). null = ready. The service states
 * are ServiceState's: 0 in service, 1 out of service (no signal), 2 emergency only, 3 power off.
 */
object SmsReadiness {
    fun reason(canSend: Boolean, simPresent: Boolean, airplane: Boolean, serviceState: Int?): String? = when {
        !canSend -> SmsFailure.PERMISSION
        airplane -> SmsFailure.RADIO_OFF
        !simPresent -> "no_sim"
        serviceState == 3 -> SmsFailure.RADIO_OFF
        serviceState == 1 -> SmsFailure.NO_SIGNAL
        serviceState == 2 -> SmsFailure.NO_SERVICE
        else -> null
    }
}

/**
 * Android asks the person before an app sends more than 30 SMS in 30 minutes (its default `sms_outgoing_check`); with
 * the app closed nobody can answer, and the SMS fails. So the phone keeps the times it sent (only times) and waits
 * when the window is full. The numbers are Android's defaults; the forty-in-a-row line of phase 0 confirms them.
 */
object SendWindow {
    const val MAX = 30
    const val WINDOW_MS = 30L * 60_000L

    fun recent(times: List<Long>, now: Long): List<Long> = times.filter { now - it in 0 until WINDOW_MS }.sorted()

    fun full(times: List<Long>, now: Long): Boolean = recent(times, now).size >= MAX

    /** When the next send fits again (now when it fits now). */
    fun freeAt(times: List<Long>, now: Long): Long {
        val recent = recent(times, now)
        return if (recent.size < MAX) now else recent[recent.size - MAX] + WINDOW_MS
    }
}

/**
 * The delivery reports of one message (one per part). TP-Status as Android gives it: 0–31 done, 32–63 the network
 * still tries, 64+ failed. The message is `delivered` once every part is done; a failed delivery changes nothing (the
 * ERP keeps "sent").
 */
object DeliveryTally {
    fun isDone(status: Int): Boolean = status in 0..31

    fun allDelivered(parts: Int, done: Int): Boolean = parts > 0 && done >= parts
}

/** A waiting message as `GET messages` lists it: the id and the parts only (the text comes with the claim). */
data class WaitingMessage(val id: String, val parts: Int)

/** `GET messages` (device-api.md §7). */
data class Inbox(
    val connected: Boolean,
    val pairing: String?,
    val paused: Boolean,
    val pausedReason: String?,
    val simSlot: Int?,
    val simLabel: String?,
    val dailyLimit: Int,
    val sentToday: Int,
    val paceSeconds: Int,
    val messages: List<WaitingMessage>,
) {
    companion object {
        private val ID = Regex("^[0-9A-Za-z]{26}$")

        fun isId(value: String?): Boolean = value != null && ID.matches(value)

        fun parse(json: JSONObject): Inbox {
            val sim = json.optJSONObject("sim")
            val list = json.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until list.length()).mapNotNull { i ->
                val row = list.optJSONObject(i) ?: return@mapNotNull null
                val id = row.optString("id", "")
                if (isId(id)) WaitingMessage(id, row.optInt("parts", 1).coerceAtLeast(1)) else null
            }
            return Inbox(
                connected = json.optBoolean("connected", false),
                pairing = json.optJSONObject("pairing")?.optString("state", "")?.ifEmpty { null },
                paused = json.optBoolean("paused", false),
                pausedReason = if (json.isNull("paused_reason")) null else json.optString("paused_reason", "").ifEmpty { null },
                simSlot = sim?.let { if (it.has("slot")) it.optInt("slot", -1).takeIf { slot -> slot in 0..7 } else null },
                simLabel = sim?.optString("label", "")?.ifEmpty { null },
                dailyLimit = json.optInt("daily_limit", 0).coerceAtLeast(0),
                sentToday = json.optInt("sent_today", 0).coerceAtLeast(0),
                paceSeconds = json.optInt("pace_seconds", 0).coerceAtLeast(0),
                messages = messages,
            )
        }
    }
}

/** `POST messages/{id}/claim`: the only place the number and the text exist on the phone, in memory, until reported. */
class Claimed(val id: String, val to: String, val text: String, val parts: Int) {
    override fun toString(): String = "Claimed($id, $parts parts)"

    companion object {
        private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")

        fun parse(json: JSONObject): Claimed? {
            val id = json.optString("id", "")
            val to = json.optString("to", "")
            val text = json.optString("text", "")
            if (!Inbox.isId(id) || !E164.matches(to) || text.isEmpty()) return null
            return Claimed(id, to, text, json.optInt("parts", 1).coerceAtLeast(1))
        }
    }
}

/** An office answer: done, refused with the ERP's code, or not reached (no internet, the ERP down). */
sealed class OfficeReply<out T> {
    data class Ok<T>(val value: T) : OfficeReply<T>()
    data class Refused(val status: Int, val code: String?) : OfficeReply<Nothing>()
    object Unreachable : OfficeReply<Nothing>()
}

/** The five device-key calls the loop makes (`GET messages`, `claim`, `report`); the HTTP side is MessagesFunctions'. */
interface MessagesOffice {
    fun inbox(): OfficeReply<Inbox>
    fun claim(id: String): OfficeReply<Claimed>
    fun report(id: String, state: String, reason: String?): OfficeReply<Unit>
}

/** The answer of one send: sent (the network took every part), failed with a reason, or no answer from Android in time. */
data class SendOutcome(val sent: Boolean, val reason: String?, val answered: Boolean = true) {
    companion object {
        val SENT = SendOutcome(true, null)
        fun failed(reason: String) = SendOutcome(false, reason)
        val NO_ANSWER = SendOutcome(false, null, answered = false)
    }
}

/** The phone's SIM side of the loop. */
interface SmsOut {
    /** null = ready to send from this slot; else why not ({@see SmsReadiness}). */
    fun readiness(slot: Int): String?

    fun send(message: Claimed, slot: Int): SendOutcome
}

/**
 * What the phone keeps between runs, and only this: reports not delivered yet (id, state, reason), the times it sent
 * (for the 30-in-30-minutes window), today's counts and why the last run stopped. Never a number or a text.
 */
interface MessagesMemory {
    fun pendingReports(): List<PendingReport>
    fun keepReport(report: PendingReport)
    fun dropReport(id: String)
    fun sendTimes(): List<Long>
    fun addSendTime(at: Long)
    fun count(sent: Boolean, at: Long)
    fun stopped(reason: String, at: Long)
}

data class PendingReport(val id: String, val state: String, val reason: String?)

/** Waits between sends; the test's waits are counted, never slept. */
interface Waiter {
    fun now(): Long
    fun sleep(ms: Long)

    /** True when the run should end now (WorkManager stopped the worker). */
    fun cancelled(): Boolean = false
}

/** Why a run ended. */
object Stop {
    const val NOTHING = "nothing_waiting"
    const val NOT_CONNECTED = "not_connected"
    const val PAUSED = "paused"
    const val DAILY_LIMIT = "daily_limit"
    const val ANDROID_LIMIT = "android_limit"
    const val NO_INTERNET = "no_internet"
    const val RELEASED = "released"
    const val NOT_MESSAGING_PHONE = "not_messaging_phone"
    const val NO_SIM = "no_sim"
    const val FAILURES = "failures"
    const val RUN_LIMIT = "run_limit"
    const val CANCELLED = "cancelled"
}

data class RunResult(val sent: Int, val failed: Int, val stop: String, val retryAt: Long? = null)

/**
 * Messages.Work's loop (device-api.md §7 "Rules for the phone"): send what waits from the chosen SIM, one at a time —
 * ask `GET messages`, claim one, send it, report, wait `pace_seconds`, next — until nothing waits, the ERP pauses the
 * phone, `daily_limit` is reached, Android's 30-in-30-minutes window is full, or the phone cannot send.
 *
 * - The phone claims only when it can send now (permission, the SIM in its slot, signal): a claimed message never
 *   falls back, an unclaimed one goes to Sylloo SMS after the wait time.
 * - The claimed number and text live in this run's memory only; after the report nothing of them is kept. A report the
 *   ERP did not get is kept as id + result and sent at the next run's start.
 * - The ERP pauses the phone after its failures in a row (`sending_paused`); the phone also ends its run after
 *   [failuresInARow] failures, so an empty SIM does not burn the queue before the ERP's pause arrives.
 * - No answer from Android in time: nothing is reported (the ERP shows "sent from the phone, not confirmed"): one
 *   missing message is better than a customer getting two.
 */
class MessagesLoop(
    private val office: MessagesOffice,
    private val sms: SmsOut,
    private val memory: MessagesMemory,
    private val waiter: Waiter,
    private val maxPerRun: Int = 100,
    private val failuresInARow: Int = 5,
    private val minPaceSeconds: Int = 3,
) {
    fun run(): RunResult {
        var sent = 0
        var failed = 0
        var failuresNow = 0

        if (!sendPendingReports()) return finish(RunResult(0, 0, Stop.NO_INTERNET))

        while (true) {
            if (waiter.cancelled()) return finish(RunResult(sent, failed, Stop.CANCELLED))

            val inbox = when (val reply = office.inbox()) {
                is OfficeReply.Ok -> reply.value
                is OfficeReply.Refused -> return finish(RunResult(sent, failed, refusedStop(reply)))
                OfficeReply.Unreachable -> return finish(RunResult(sent, failed, Stop.NO_INTERNET))
            }

            if (!inbox.connected) return finish(RunResult(sent, failed, Stop.NOT_CONNECTED))
            if (inbox.paused) return finish(RunResult(sent, failed, Stop.PAUSED))
            if (inbox.messages.isEmpty()) return finish(RunResult(sent, failed, Stop.NOTHING))
            val slot = inbox.simSlot ?: return finish(RunResult(sent, failed, Stop.NO_SIM))

            var sentToday = inbox.sentToday
            var claimedAny = false

            for (waiting in inbox.messages) {
                if (waiter.cancelled()) return finish(RunResult(sent, failed, Stop.CANCELLED))
                if (inbox.dailyLimit in 1..sentToday || inbox.dailyLimit == 0) return finish(RunResult(sent, failed, Stop.DAILY_LIMIT))
                if (sent + failed >= maxPerRun) return finish(RunResult(sent, failed, Stop.RUN_LIMIT))

                val now = waiter.now()
                if (SendWindow.full(memory.sendTimes(), now)) {
                    return finish(RunResult(sent, failed, Stop.ANDROID_LIMIT, SendWindow.freeAt(memory.sendTimes(), now)))
                }

                sms.readiness(slot)?.let { return finish(RunResult(sent, failed, it)) }

                val claimed = when (val reply = office.claim(waiting.id)) {
                    is OfficeReply.Ok -> reply.value
                    is OfficeReply.Refused -> when (reply.code) {
                        "message_taken", "not_found" -> continue
                        else -> return finish(RunResult(sent, failed, refusedStop(reply)))
                    }
                    OfficeReply.Unreachable -> return finish(RunResult(sent, failed, Stop.NO_INTERNET))
                }
                claimedAny = true

                val outcome = sms.send(claimed, slot)
                val at = waiter.now()
                memory.addSendTime(at)

                if (outcome.answered) {
                    val state = if (outcome.sent) "sent" else "failed"
                    val reason = if (outcome.sent) null else (outcome.reason ?: SmsFailure.OTHER)
                    report(PendingReport(claimed.id, state, reason))
                    memory.count(outcome.sent, at)
                    if (outcome.sent) { sent++; sentToday++; failuresNow = 0 } else { failed++; failuresNow++ }
                }

                if (failuresNow >= failuresInARow) return finish(RunResult(sent, failed, Stop.FAILURES))

                waiter.sleep(maxOf(inbox.paceSeconds, minPaceSeconds) * 1000L)
            }

            // Every listed message was taken by someone else: ask again only when something was claimed this round.
            if (!claimedAny) return finish(RunResult(sent, failed, Stop.NOTHING))
        }
    }

    /** Reports kept from an earlier run; false when the ERP is not reached (the run waits for the internet). */
    private fun sendPendingReports(): Boolean {
        for (pending in memory.pendingReports()) {
            when (val reply = office.report(pending.id, pending.state, pending.reason)) {
                is OfficeReply.Ok -> memory.dropReport(pending.id)
                is OfficeReply.Refused -> if (reply.status in 400..499 && reply.status != 429) memory.dropReport(pending.id)
                OfficeReply.Unreachable -> return false
            }
        }
        return true
    }

    private fun report(report: PendingReport) {
        when (val reply = office.report(report.id, report.state, report.reason)) {
            is OfficeReply.Ok -> Unit
            is OfficeReply.Refused -> if (reply.status !in 400..499 || reply.status == 429) memory.keepReport(report)
            OfficeReply.Unreachable -> memory.keepReport(report)
        }
    }

    private fun refusedStop(reply: OfficeReply.Refused): String = when (reply.code) {
        "device_released" -> Stop.RELEASED
        "sending_paused" -> Stop.PAUSED
        "not_messaging_phone", "feature_off" -> Stop.NOT_MESSAGING_PHONE
        else -> if (reply.status == 401) Stop.RELEASED else Stop.NO_INTERNET
    }

    private fun finish(result: RunResult): RunResult {
        memory.stopped(result.stop, waiter.now())
        return result
    }
}

/** Plain rules of the job's settings handed over by PHP (Messages.Start). */
object MessagesRules {
    const val CHECK_MINUTES_DEFAULT = 5L

    /** The ERP's address: https only (http only for a developer build that says so), no spaces, no user info. */
    fun baseUrl(value: String?, allowHttp: Boolean = false): String? {
        val url = value?.trim()?.trimEnd('/') ?: return null
        val ok = url.startsWith("https://") || (allowHttp && url.startsWith("http://"))
        if (!ok || url.length > 300 || url.contains(' ') || url.substringAfter("://").substringBefore('/').contains('@')) return null
        return url
    }

    fun deviceKey(value: String?): String? {
        val key = value?.trim() ?: return null
        return if (key.length in 16..200 && key.all { it.isLetterOrDigit() || it in "-_.:" }) key else null
    }

    /** Minutes between the phone's own checks (the exact alarm): 2 to 60, default 5. Android stretches it in Doze. */
    fun checkMinutes(value: Number?): Long = (value?.toLong() ?: CHECK_MINUTES_DEFAULT).coerceIn(2L, 60L)

    /** A silent push asks the phone to work: `wake` = `messages` (device-api.md §1.1). */
    fun isWake(data: Map<String, String>): Boolean = data["wake"] == "messages"
}
