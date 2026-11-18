package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.util.concurrent.TimeUnit

/** One row of the phone's call list, as read (the number never leaves this file unless it is one of the app's keys). */
data class CallRow(val id: Long, val number: String?, val type: Int, val dateMs: Long, val durationSeconds: Long)

/** One work call: a row whose number ends in one of the app's compare keys. */
data class WorkCall(
    val callId: String,
    val number: String,
    val key: String,
    val direction: String,
    val startedAtMs: Long,
    val durationSeconds: Long,
)

/** What one read of the call list gives: the work calls, the last row id read (the next read starts after it), more. */
data class CallPick(val calls: List<WorkCall>, val lastId: Long, val more: Boolean)

/**
 * The rules of the work calls (pure, JVM-tested; crm-call-recording-plan.md §3): which row of the call list is a work
 * call and what of it is handed to the app. The app gives the compare keys (the last [digits] digits of the numbers of
 * the employee's own leads and customers); a row whose number ends in none of them is a private call and is never
 * handed over, not even its number, its name or its time. Rows are read in id order after the last read, so a call
 * that ended later (its row is written at its end) is never skipped.
 */
object CallRules {
    const val DEFAULT_DIGITS = 9
    const val MIN_DIGITS = 6
    const val MAX_DIGITS = 15
    const val MAX_LIMIT = 100
    const val MAX_KEYS = 5000

    /** A call row's own id plus its start, so a call list that was cleared and counts again never repeats a call id. */
    fun callId(row: CallRow): String = "${row.id}-${row.dateMs}"

    fun digits(value: Number?): Int = (value?.toInt() ?: DEFAULT_DIGITS).coerceIn(MIN_DIGITS, MAX_DIGITS)

    /** The compare key of a number: its last [digits] digits, or null for a number with fewer (a short code, hidden). */
    fun key(number: String?, digits: Int): String? {
        val plain = (number ?: "").filter { it in '0'..'9' }
        return if (plain.length < digits) null else plain.takeLast(digits)
    }

    /** The app's keys, each only digits and exactly [digits] long (anything else is dropped). */
    fun keys(values: List<Any?>, digits: Int): Set<String> = values
        .mapNotNull { it as? String }
        .filter { it.length == digits && it.all { c -> c in '0'..'9' } }
        .take(MAX_KEYS)
        .toSet()

    /**
     * in | out from the call list's type: incoming, missed and rejected are `in` (a missed one is 0 seconds: "no
     * answer"), outgoing is `out`. Voicemail, blocked and answered elsewhere are not calls with the person.
     */
    fun direction(type: Int): String? = when (type) {
        INCOMING, MISSED, REJECTED -> "in"
        OUTGOING -> "out"
        else -> null
    }

    // CallLog.Calls.*_TYPE and TelephonyManager.EXTRA_STATE_* (written out, so the JVM tests need no Android).
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val REJECTED = 5
    const val IDLE = "IDLE"
    const val OFFHOOK = "OFFHOOK"
    const val RINGING = "RINGING"

    /**
     * The work calls among [rows] (read in id order): after [afterId], not before [sinceMs], at most [limit]. The last id
     * read moves on over private calls too, so they are never read again; it stops at the row that did not fit.
     */
    fun pick(rows: List<CallRow>, keys: Set<String>, digits: Int, afterId: Long, sinceMs: Long, limit: Int): CallPick {
        val calls = ArrayList<WorkCall>()
        var lastId = afterId
        val max = limit.coerceIn(1, MAX_LIMIT)

        for (row in rows.sortedBy { it.id }) {
            if (row.id <= afterId) continue

            val key = key(row.number, digits)
            val direction = direction(row.type)
            val work = key != null && direction != null && row.dateMs >= sinceMs && keys.contains(key)

            if (work && calls.size >= max) return CallPick(calls, lastId, true)

            if (work) {
                calls.add(WorkCall(callId(row), row.number!!.trim().take(30), key!!, direction!!, row.dateMs, row.durationSeconds.coerceIn(0, 86_400)))
            }
            lastId = row.id
        }

        return CallPick(calls, lastId, false)
    }

    /** Whether a phone state change is the end of a call: back to idle after ringing or a call. */
    fun ended(previous: String?, now: String?): Boolean =
        now == IDLE && (previous == OFFHOOK || previous == RINGING)
}

/**
 * Calls.Recent / Calls.Ended / Calls.Permission / Calls.Status (crm-call-recording-plan.md §6, CR2): the work calls of
 * the call list, and a wake of the app's worker when a call ends. Each job is off unless the app turns it on
 * (`features.calls_recent`, `features.calls_ended`): off, the build has neither READ_CALL_LOG nor READ_PHONE_STATE nor
 * the receiver (the build hook), and every call answers `not_set_up`. Nothing here sends anything: the app decides.
 */
object CallFunctions {
    const val RECENT = "sylloo_feature_calls_recent"
    const val ENDED = "sylloo_feature_calls_ended"

    fun recentOn(context: Context): Boolean = SyllooFeatures.on(context, RECENT)

    fun endedOn(context: Context): Boolean = SyllooFeatures.on(context, ENDED)

    fun canReadLog(context: Context): Boolean = SyllooCoordinator.hasPermission(context, Manifest.permission.READ_CALL_LOG)

    fun canHearState(context: Context): Boolean = SyllooCoordinator.hasPermission(context, Manifest.permission.READ_PHONE_STATE)

    /** The permissions of the switched-on jobs. */
    fun wanted(context: Context): Array<String> = listOfNotNull(
        if (recentOn(context)) Manifest.permission.READ_CALL_LOG else null,
        if (endedOn(context)) Manifest.permission.READ_PHONE_STATE else null,
    ).toTypedArray()

    class Recent(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!recentOn(context)) return BridgeResponse.success(mapOf("status" to "not_set_up"))
            if (!canReadLog(context)) return BridgeResponse.success(mapOf("status" to "no_permission"))

            val digits = CallRules.digits(parameters["digits"] as? Number)
            val keys = CallRules.keys(NoticeRules.list(parameters["keys"]), digits)
            val afterId = (parameters["after_id"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            val sinceMs = (parameters["since_ms"] as? Number)?.toLong() ?: 0L
            val limit = (parameters["limit"] as? Number)?.toInt() ?: 50

            return try {
                val pick = CallRules.pick(CallLogReader.rows(context, afterId, sinceMs), keys, digits, afterId, sinceMs, limit)

                BridgeResponse.success(mapOf(
                    "status" to "ok",
                    "last_id" to pick.lastId,
                    "more" to pick.more,
                    "calls" to pick.calls.map {
                        mapOf(
                            "call_id" to it.callId,
                            "number" to it.number,
                            "key" to it.key,
                            "direction" to it.direction,
                            "started_at_ms" to it.startedAtMs,
                            "duration_seconds" to it.durationSeconds,
                        )
                    },
                ))
            } catch (e: SecurityException) {
                BridgeResponse.success(mapOf("status" to "no_permission"))
            } catch (e: Exception) {
                Log.e(TAG, "Call list not read: ${e.javaClass.simpleName}")
                BridgeResponse.success(mapOf("status" to "failed"))
            }
        }
    }

    /** `{on: true, command?}` arms the wake at the end of a call; `{on: false}` disarms it. */
    class Ended(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!endedOn(context)) return BridgeResponse.success(mapOf("watching" to false, "reason" to "not_set_up"))

            if (parameters["on"] != true) {
                CallWatch.stop(context)
                return BridgeResponse.success(mapOf("watching" to false))
            }

            val command = SyncRules.command(parameters["command"] as? String)
                ?: return BridgeResponse.success(mapOf("watching" to false, "reason" to "invalid"))

            CallWatch.start(context, command)
            return BridgeResponse.success(mapOf("watching" to true, "phone_state" to canHearState(context)))
        }
    }

    class Permission(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            val wanted = wanted(context)
            if (wanted.isEmpty()) return BridgeResponse.success(mapOf("status" to "not_set_up"))

            if (wanted.all { SyllooCoordinator.hasPermission(context, it) }) {
                SyllooEvents.send("CallsPermissionAnswered", answer(id))
                return BridgeResponse.success(mapOf("status" to "granted"))
            }

            val started = SyllooCoordinator.run { coordinator, _ ->
                coordinator.withPermissions(wanted) { _ -> SyllooEvents.send("CallsPermissionAnswered", answer(id)) }
            }

            return BridgeResponse.success(mapOf("status" to if (started) "asking" else "failed"))
        }

        private fun answer(id: String?) = SyllooEvents.payload(id, if (wanted(context).all { SyllooCoordinator.hasPermission(context, it) }) "granted" else "denied")
            .put("call_log", canReadLog(context))
            .put("phone_state", canHearState(context))
    }

    class Status(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = BridgeResponse.success(mapOf(
            "recent" to recentOn(context),
            "ended" to endedOn(context),
            "call_log" to (recentOn(context) && canReadLog(context)),
            "phone_state" to (endedOn(context) && canHearState(context)),
            "watching" to (endedOn(context) && CallWatch.armed(context)),
        ))
    }

    const val TAG = "SyllooCalls"
}

/** Reads the call list's rows after an id (only the columns the rules need: no cached name, no contact). */
object CallLogReader {
    fun rows(context: Context, afterId: Long, sinceMs: Long): List<CallRow> {
        val rows = ArrayList<CallRow>()
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(afterId.toString(), sinceMs.toString()),
            "${CallLog.Calls._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < 1000) {
                rows.add(CallRow(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getLong(3), cursor.getLong(4)))
            }
        }

        return rows
    }
}

/**
 * The wake when a call ends: the PHONE_STATE receiver (Android delivers it to a manifest receiver also with the app
 * closed) and, as a second way, a WorkManager trigger on the call list itself. Both run the app's artisan command with
 * NativePHP's background PHP ({@see SendWaitingWorker}) a few seconds after the call, so its row is written.
 */
object CallWatch {
    private const val PREFS = "sylloo_calls"
    const val RUN = "sylloo-calls-ended"
    const val TRIGGER = "sylloo-calls-trigger"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun armed(context: Context): Boolean = prefs(context).getBoolean("armed", false)

    fun start(context: Context, command: String) {
        prefs(context).edit().putBoolean("armed", true).putString("command", command).apply()
        trigger(context)
    }

    fun stop(context: Context) {
        prefs(context).edit().clear().apply()
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(RUN)
        manager.cancelUniqueWork(TRIGGER)
    }

    fun lastState(context: Context): String? = prefs(context).getString("state", null)

    fun keepState(context: Context, state: String?) {
        prefs(context).edit().putString("state", state).apply()
    }

    /** Run the app's command soon (one waits at a time; one that runs now is followed by this one). */
    fun wake(context: Context) {
        if (!CallFunctions.endedOn(context) || !armed(context)) return
        val command = SyncRules.command(prefs(context).getString("command", null)) ?: return

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RUN,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SendWaitingWorker>()
                .setInitialDelay(4, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString("command", command).build())
                .build(),
        )
    }

    /** The second way: a one-shot trigger on a change of the call list, armed again each time it fires. */
    fun trigger(context: Context) {
        if (!CallFunctions.endedOn(context) || !armed(context) || !CallFunctions.canReadLog(context)) return

        try {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                TRIGGER,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CallListChangedWorker>()
                    .setConstraints(Constraints.Builder().addContentUriTrigger(CallLog.Calls.CONTENT_URI, true).build())
                    .build(),
            )
        } catch (e: Exception) {
            Log.e(CallFunctions.TAG, "Call list trigger not armed: ${e.javaClass.simpleName}")
        }
    }
}

/** A call ended (PHONE_STATE back to idle): wake the app's worker. Reads no number. */
class CallEndedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED || !CallFunctions.endedOn(context) || !CallWatch.armed(context)) return

        val now = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val previous = CallWatch.lastState(context)
        CallWatch.keepState(context, now)

        if (CallRules.ended(previous, now)) CallWatch.wake(context)
    }
}

/** The call list changed: wake the app's worker and arm the trigger again. */
class CallListChangedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CallWatch.wake(applicationContext)
        CallWatch.trigger(applicationContext)
        return Result.success()
    }
}
