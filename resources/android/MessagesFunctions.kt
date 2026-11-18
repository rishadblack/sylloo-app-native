package com.rishadblack.plugins.sylloo_app_native

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * What Messages.Work needs to run with the app closed, where the phone's PHP does not run:
 *
 * - the device key: in the package's Keystore vault ({@see SecretVault}, the same one `Secret.*` and the app's data
 *   key use), under its own name; PHP hands it over with Messages.Start while the app is open (and again at every
 *   return to the app), the background run reads it there. Never in a plain file, never in the backup.
 * - the ERP's address, the app version, the notice's texts and the check time: plain settings
 *   (SharedPreferences `sylloo_app_native_messages`).
 */
data class MessagesSettings(val baseUrl: String, val deviceKey: String, val appVersion: String, val checkMinutes: Long)

object MessagesStore {
    const val PREFS = "sylloo_app_native_messages"
    const val KEY_SECRET = "sylloo.messages.device-key"

    fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, baseUrl: String, deviceKey: String, appVersion: String, checkMinutes: Long, noticeTitle: String, noticeText: String) {
        SecretVault(context.applicationContext).set(KEY_SECRET, deviceKey)
        prefs(context).edit()
            .putString("base_url", baseUrl)
            .putString("app_version", appVersion.take(40))
            .putLong("check_minutes", checkMinutes)
            .putString("notice_title", noticeTitle.take(80))
            .putString("notice_text", noticeText.take(160))
            .apply()
    }

    fun settings(context: Context): MessagesSettings? {
        val prefs = prefs(context)
        val baseUrl = prefs.getString("base_url", null) ?: return null
        val key = try {
            SecretVault(context.applicationContext).get(KEY_SECRET)
        } catch (e: Exception) {
            null
        } ?: return null
        return MessagesSettings(baseUrl, key, prefs.getString("app_version", "") ?: "", prefs.getLong("check_minutes", MessagesRules.CHECK_MINUTES_DEFAULT))
    }

    fun notice(context: Context): Pair<String, String> {
        val prefs = prefs(context)
        return Pair(
            prefs.getString("notice_title", null) ?: "Sending the shop's messages",
            prefs.getString("notice_text", null) ?: "",
        )
    }

    /** Everything of the job: the key, the settings, the reports and the counts (Messages.Stop, a released device). */
    fun forget(context: Context) {
        try {
            SecretVault(context.applicationContext).delete(KEY_SECRET)
        } catch (e: Exception) {
            // nothing kept, or the Keystore is gone with the app data
        }
        prefs(context).edit().clear().apply()
    }
}

/** {@see MessagesMemory} in the job's SharedPreferences: ids and results, times and counts; never a number or a text. */
class PrefsMemory(context: Context) : MessagesMemory {
    private val prefs = MessagesStore.prefs(context)

    override fun pendingReports(): List<PendingReport> {
        val list = JSONArray(prefs.getString("pending_reports", "[]") ?: "[]")
        return (0 until list.length()).mapNotNull { i ->
            val row = list.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id", "")
            if (Inbox.isId(id)) PendingReport(id, row.optString("state", "failed"), if (row.isNull("reason")) null else row.optString("reason")) else null
        }
    }

    override fun keepReport(report: PendingReport) {
        val rows = pendingReports().filter { it.id != report.id } + report
        writeReports(rows.takeLast(200))
    }

    override fun dropReport(id: String) = writeReports(pendingReports().filter { it.id != id })

    private fun writeReports(rows: List<PendingReport>) {
        val list = JSONArray()
        rows.forEach { list.put(JSONObject().put("id", it.id).put("state", it.state).put("reason", it.reason ?: JSONObject.NULL)) }
        prefs.edit().putString("pending_reports", list.toString()).apply()
    }

    override fun sendTimes(): List<Long> =
        (prefs.getString("send_times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    override fun addSendTime(at: Long) {
        val times = SendWindow.recent(sendTimes() + at, at)
        prefs.edit().putString("send_times", times.joinToString(",")).apply()
    }

    override fun count(sent: Boolean, at: Long) {
        val day = day(at)
        val sameDay = prefs.getString("count_day", null) == day
        val sentToday = if (sameDay) prefs.getInt("sent_today", 0) else 0
        val failedToday = if (sameDay) prefs.getInt("failed_today", 0) else 0
        prefs.edit()
            .putString("count_day", day)
            .putInt("sent_today", sentToday + if (sent) 1 else 0)
            .putInt("failed_today", failedToday + if (sent) 0 else 1)
            .apply()
    }

    override fun stopped(reason: String, at: Long) {
        prefs.edit().putString("last_stop", reason).putLong("last_run_at", at).apply()
    }

    fun status(now: Long): Map<String, Any?> {
        val sameDay = prefs.getString("count_day", null) == day(now)
        return mapOf(
            "last_run_at" to prefs.getLong("last_run_at", 0L).takeIf { it > 0 }?.let { it / 1000 },
            "last_stop" to prefs.getString("last_stop", null),
            "sent_today" to if (sameDay) prefs.getInt("sent_today", 0) else 0,
            "failed_today" to if (sameDay) prefs.getInt("failed_today", 0) else 0,
            "reports_waiting" to pendingReports().size,
        )
    }

    private fun day(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
}

/**
 * The delivery reports counted per message (id → parts and parts done, kept at most 3 days), so a message of three
 * parts is `delivered` only when all three arrived.
 */
object MessagesDeliveries {
    private const val PREFS = "sylloo_app_native_deliveries"
    private const val KEEP_MS = 3L * 24 * 3600 * 1000

    /** True when this part made the message complete (once). */
    @Synchronized
    fun partDone(context: Context, id: String, part: Int, parts: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            val at = (value as? String)?.substringAfterLast(':')?.toLongOrNull() ?: 0L
            if (now - at > KEEP_MS) editor.remove(key)
        }

        val seen = (prefs.getString(id, null)?.substringBefore(':') ?: "").split('.').mapNotNull { it.toIntOrNull() }.toMutableSet()
        if (part in seen) {
            editor.apply()
            return false
        }
        seen.add(part)

        return if (DeliveryTally.allDelivered(parts, seen.size)) {
            editor.remove(id).apply()
            true
        } else {
            editor.putString(id, seen.joinToString(".") + ":" + now).apply()
            false
        }
    }
}

/**
 * The device-key calls of device-api.md §7 over HTTPS (`X-Device-Key`, `X-Sylloo-App`). No body or address goes to
 * the log.
 */
class HttpMessagesOffice(private val settings: MessagesSettings) : MessagesOffice {

    override fun inbox(): OfficeReply<Inbox> = when (val reply = call("GET", "messages", null)) {
        is OfficeReply.Ok -> OfficeReply.Ok(Inbox.parse(reply.value))
        is OfficeReply.Refused -> reply
        OfficeReply.Unreachable -> OfficeReply.Unreachable
    }

    override fun claim(id: String): OfficeReply<Claimed> = when (val reply = call("POST", "messages/$id/claim", JSONObject())) {
        is OfficeReply.Ok -> Claimed.parse(reply.value)?.let { OfficeReply.Ok(it) } ?: OfficeReply.Refused(200, "unexpected")
        is OfficeReply.Refused -> reply
        OfficeReply.Unreachable -> OfficeReply.Unreachable
    }

    override fun report(id: String, state: String, reason: String?): OfficeReply<Unit> {
        val body = JSONObject().put("state", state)
        if (reason != null) body.put("reason", reason)
        return when (val reply = call("POST", "messages/$id/report", body)) {
            is OfficeReply.Ok -> OfficeReply.Ok(Unit)
            is OfficeReply.Refused -> reply
            OfficeReply.Unreachable -> OfficeReply.Unreachable
        }
    }

    private fun call(method: String, path: String, body: JSONObject?): OfficeReply<JSONObject> {
        val connection = try {
            URL("${settings.baseUrl}/device/api/v1/$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return OfficeReply.Unreachable
        }

        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Device-Key", settings.deviceKey)
            connection.setRequestProperty("X-Sylloo-App", settings.appVersion)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }

            when {
                status in 200..299 -> OfficeReply.Ok(json)
                status >= 500 -> OfficeReply.Unreachable
                else -> OfficeReply.Refused(status, json.optString("code", "").ifEmpty { null })
            }
        } catch (e: IOException) {
            OfficeReply.Unreachable
        } finally {
            connection.disconnect()
        }
    }
}

/** The SIM side of the loop on a real phone. */
class AndroidSms(private val context: Context) : SmsOut {
    override fun readiness(slot: Int): String? = SmsPhone.readiness(context, slot)

    override fun send(message: Claimed, slot: Int): SendOutcome {
        val sim = SimChoice.pick(SmsPhone.sims(context), slot) ?: return SendOutcome.failed(SmsFailure.NO_SERVICE)
        return SmsSender(context).send(message.to, message.text, sim, message.id)
    }
}

/**
 * When Messages.Work runs (phone-messaging-plan.md §3 "three ways", and phase 0: WorkManager alone waits for Doze to
 * end, an exact alarm does not):
 *
 * - now: Messages.Start / Messages.Work from PHP (the accept screen, every return to the app) and the wake push
 *   (`wake: messages`), as an expedited WorkManager job;
 * - an exact alarm (`setExactAndAllowWhileIdle`) every `check_minutes` (and when Android's 30-in-30-minutes window
 *   frees), also in Doze and with battery saver on;
 * - a 15-minute WorkManager check as a safety net (it also arms the alarm again after a restart of the phone);
 * - when the internet comes back after a run that found none.
 */
object MessagesWork {
    const val NOW = "sylloo-messages"
    const val ONLINE = "sylloo-messages-online"
    const val REPEAT = "sylloo-messages-repeat"
    private const val ALARM_CODE = 7302

    fun ready(context: Context): Boolean = SmsPhone.enabled(context) && MessagesStore.prefs(context).contains("base_url")

    /** Run now (expedited; queued after a run in progress, so a wake during a run is not lost). */
    fun wake(context: Context): Boolean {
        if (!ready(context)) return false
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MessagesWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
        )
        return true
    }

    fun whenOnline(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONLINE,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MessagesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun repeat(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            REPEAT,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MessagesWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    /** The next exact alarm: at [at] (wall clock ms) or after the check time. Inexact when exact alarms are not allowed. */
    fun arm(context: Context, at: Long? = null) {
        if (!ready(context)) return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val minutes = MessagesStore.prefs(context).getLong("check_minutes", MessagesRules.CHECK_MINUTES_DEFAULT)
        val delay = ((at ?: 0L) - System.currentTimeMillis()).takeIf { at != null && it > 0 } ?: (minutes * 60_000L)
        val trigger = SystemClock.elapsedRealtime() + delay
        val pending = alarmIntent(context)

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
        }
    }

    fun cancel(context: Context) {
        val work = WorkManager.getInstance(context.applicationContext)
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(ONLINE)
        work.cancelUniqueWork(REPEAT)
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_CODE,
        Intent(context, MessagesAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A delivery report of an ERP message (all parts arrived): `delivered`, or kept for the next run. */
    fun reportDelivered(context: Context, id: String) {
        val settings = MessagesStore.settings(context) ?: return
        val reply = HttpMessagesOffice(settings).report(id, "delivered", null)
        if (reply is OfficeReply.Unreachable) PrefsMemory(context).keepReport(PendingReport(id, "delivered", null))
    }

    fun online(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** The exact alarm: run now and arm the next one. Declared (not exported) by the build hook only with `sms_send` on. */
class MessagesAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (MessagesWork.wake(context)) MessagesWork.arm(context)
    }
}

/**
 * One run of Messages.Work ({@see MessagesLoop}) with the visible notice "Sending the shop's messages" while it works.
 * Only one run at a time in the process; the next one queued by WorkManager follows.
 */
class MessagesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notice(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!SmsPhone.enabled(context)) return@withContext Result.success()
        val settings = MessagesStore.settings(context) ?: run {
            MessagesWork.cancel(context)
            return@withContext Result.success()
        }

        if (!lock.tryLock()) return@withContext Result.success()
        try {
            try {
                setForeground(notice(context))
            } catch (e: Throwable) {
                // Android refused a foreground notice now (started from the background without an exemption): the run
                // still goes on as an expedited job.
            }

            val worker = this@MessagesWorker
            val waiter = object : Waiter {
                override fun now(): Long = System.currentTimeMillis()
                override fun sleep(ms: Long) = Thread.sleep(ms)
                override fun cancelled(): Boolean = worker.isStopped
            }

            val result = MessagesLoop(HttpMessagesOffice(settings), AndroidSms(context), PrefsMemory(context), waiter).run()

            when (result.stop) {
                Stop.RELEASED -> {
                    MessagesWork.cancel(context)
                    MessagesStore.forget(context)
                }
                Stop.NO_INTERNET -> {
                    MessagesWork.whenOnline(context)
                    MessagesWork.arm(context)
                }
                else -> MessagesWork.arm(context, result.retryAt)
            }
            Result.success()
        } catch (e: Throwable) {
            MessagesWork.arm(context)
            Result.success()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private val lock = ReentrantLock()
        const val CHANNEL = "sylloo_messages"
        const val NOTICE_ID = 7301

        // The dataSync service line is in the manifest only when features.sms_send is on (FeatureManifest); with it off
        // the job refuses on the phone and this never runs, but release lint still checks the compiled call.
        @SuppressLint("SpecifyForegroundServiceType")
        fun notice(context: Context): ForegroundInfo {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL) == null) {
                val (title, _) = MessagesStore.notice(context)
                manager?.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW))
            }
            val (title, text) = MessagesStore.notice(context)
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (text.isNotEmpty()) builder.setContentText(text)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                builder.setContentIntent(PendingIntent.getActivity(context, NOTICE_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            val notification = builder.build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(NOTICE_ID, notification)
        }
    }
}

/**
 * Messages.Start / Messages.Work / Messages.Stop / Messages.Status (phone-messaging-plan.md §5.1), off unless
 * `features.sms_send`.
 *
 * - Start `{base_url, device_key, app_version, check_minutes?, notice_title, notice_text, allow_http?}`: keeps the key in
 *   the Keystore vault and the rest as settings, then runs now, arms the exact alarm and the 15-minute check. PHP calls
 *   it after the phone said yes and at every return to the app (the key may have changed).
 * - Work: run now (at every return to the app; the wake push does the same from Kotlin).
 * - Stop: cancels every run and alarm and forgets the key, the settings, the reports and the counts.
 * - Status: `{set_up, can_send, sims, online, service, last_run_at, last_stop, sent_today, failed_today, reports_waiting}`
 *   for the Messages screen (what is missing: internet or signal).
 */
object MessagesFunctions {
    private fun off(): Map<String, Any> = BridgeResponse.success(mapOf("status" to "not_set_up"))

    class Start(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SmsPhone.enabled(context)) return off()
            val baseUrl = MessagesRules.baseUrl(parameters["base_url"] as? String, parameters["allow_http"] == true)
                ?: return BridgeResponse.error("INVALID_PARAMETERS", "The ERP address must use https")
            val key = MessagesRules.deviceKey(parameters["device_key"] as? String)
                ?: return BridgeResponse.error("INVALID_PARAMETERS", "No device key")

            return try {
                MessagesStore.save(
                    context,
                    baseUrl,
                    key,
                    (parameters["app_version"] as? String) ?: "",
                    MessagesRules.checkMinutes(parameters["check_minutes"] as? Number),
                    (parameters["notice_title"] as? String)?.ifBlank { null } ?: "Sending the shop's messages",
                    (parameters["notice_text"] as? String) ?: "",
                )
                MessagesWork.repeat(context)
                MessagesWork.wake(context)
                MessagesWork.arm(context)
                BridgeResponse.success(mapOf("status" to "started"))
            } catch (e: Exception) {
                BridgeResponse.error("EXECUTION_FAILED", "Could not keep the settings")
            }
        }
    }

    class Work(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SmsPhone.enabled(context)) return off()
            if (!MessagesWork.wake(context)) return BridgeResponse.success(mapOf("status" to "not_started"))
            MessagesWork.arm(context)
            return BridgeResponse.success(mapOf("status" to "started"))
        }
    }

    class Stop(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            MessagesWork.cancel(context)
            MessagesStore.forget(context)
            return BridgeResponse.success(mapOf("status" to "stopped"))
        }
    }

    class Status(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SmsPhone.enabled(context)) return off()
            val slot = (parameters["slot"] as? Number)?.toInt()
            val sim = slot?.let { SimChoice.pick(SmsPhone.sims(context), it) }

            val answer = mutableMapOf<String, Any?>(
                "status" to "ok",
                "set_up" to MessagesWork.ready(context),
                "can_send" to (SmsPhone.canSend(context) && SmsPhone.canListSims(context)),
                "online" to MessagesWork.online(context),
                "airplane" to SmsPhone.airplane(context),
                "sim_present" to (sim != null),
                "service" to slot?.let { SmsPhone.readiness(context, it) },
            )
            answer.putAll(PrefsMemory(context).status(System.currentTimeMillis()))

            return BridgeResponse.success(answer.filterValues { it != null }.mapValues { it.value!! })
        }
    }
}
