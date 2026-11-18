package com.rishadblack.plugins.sylloo_app_native

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONArray
import org.json.JSONObject

/** One "Later" choice: after [minutes], or at the fixed time [atMs] (the app's "Tomorrow", already in working time). */
data class NoticeLater(val key: String, val label: String, val minutes: Int?, val atMs: Long?)

/**
 * One timed notice (the `Notice.Schedule` contract). Every text is already in the person's language; the phone only
 * shows it. A tap opens the app with [open] / [openId]; Call opens the app with the action `call` (the app opens the
 * dialer itself, so it can ask for the call's result when the person comes back); Later shows it again.
 */
data class NoticeSpec(
    val id: String,
    val group: String,
    val channel: String,
    val channelName: String,
    val atMs: Long,
    val title: String,
    val body: String,
    val open: String,
    val openId: String?,
    val callLabel: String?,
    val laterLabel: String?,
    val later: List<NoticeLater>,
)

/**
 * The rules of a timed notice (pure, JVM-tested): what a schedule call may carry, how it is kept, and when "Later"
 * shows it again. Bad fields refuse the whole notice (`invalid`); texts are cut to a sane length.
 */
object NoticeRules {
    private val ID = Regex("^[A-Za-z0-9_\\-:.]{1,64}$")
    private val WORD = Regex("^[a-z0-9_.-]{1,40}$")

    const val MAX_TITLE = 120
    const val MAX_BODY = 400
    const val MAX_LABEL = 40
    const val MAX_LATER = 3

    /** A reminder set more than a year ahead is a mistake. */
    const val MAX_AHEAD_MS = 366L * 24 * 60 * 60 * 1000

    /** One missed while the phone was off is still shown when it is at most this old; older ones are dropped. */
    const val MISSED_MS = 12L * 60 * 60 * 1000

    fun isId(value: String?): Boolean = value != null && ID.matches(value)

    fun isWord(value: String?): Boolean = value != null && WORD.matches(value)

    fun text(value: Any?, max: Int): String = ((value as? String) ?: "").trim().take(max)

    /** The notice from a bridge call's fields, or null when it may not be set ([nowMs] null: a kept one, read again). */
    fun parse(fields: Map<String, Any?>, nowMs: Long?): NoticeSpec? {
        val id = fields["id"] as? String
        val open = fields["open"] as? String
        val atMs = (fields["at_ms"] as? Number)?.toLong()
        val group = (fields["group"] as? String) ?: "default"
        val channel = (fields["channel"] as? String) ?: "sylloo_reminders"
        val openId = fields["open_id"] as? String

        if (!isId(id) || !isWord(open) || !isWord(group) || !isWord(channel) || atMs == null) return null
        if (openId != null && !isId(openId)) return null
        if (nowMs != null && atMs > nowMs + MAX_AHEAD_MS) return null

        val title = text(fields["title"], MAX_TITLE)
        val body = text(fields["body"], MAX_BODY)
        if (title.isEmpty() && body.isEmpty()) return null

        val call = map(fields["call"])
        val laterBlock = map(fields["later"])
        val later = list(laterBlock?.get("options")).mapNotNull { option(map(it)) }.take(MAX_LATER)

        return NoticeSpec(
            id = id!!,
            group = group,
            channel = channel,
            channelName = text(fields["channel_name"], MAX_LABEL).ifEmpty { "Reminders" },
            atMs = atMs,
            title = title,
            body = body,
            open = open!!,
            openId = openId,
            callLabel = call?.let { text(it["label"], MAX_LABEL).ifEmpty { null } },
            laterLabel = if (later.isEmpty()) null else text(laterBlock?.get("label"), MAX_LABEL).ifEmpty { null },
            later = later,
        )
    }

    private fun option(fields: Map<String, Any?>?): NoticeLater? {
        if (fields == null) return null
        val key = fields["key"] as? String
        val label = text(fields["label"], MAX_LABEL)
        val minutes = (fields["minutes"] as? Number)?.toInt()?.takeIf { it in 1..(7 * 24 * 60) }
        val atMs = (fields["at_ms"] as? Number)?.toLong()
        if (!isWord(key) || label.isEmpty() || (minutes == null && atMs == null)) return null
        return NoticeLater(key!!, label, minutes, if (minutes == null) atMs else null)
    }

    /** When "Later" shows it again: after the minutes, or at the fixed time when that is still ahead (else in 1 h). */
    fun laterAt(choice: NoticeLater, nowMs: Long): Long = when {
        choice.minutes != null -> nowMs + choice.minutes * 60_000L
        choice.atMs != null && choice.atMs > nowMs -> choice.atMs
        else -> nowMs + 60 * 60_000L
    }

    /** At start-up (after a restart or an update): arm it when ahead, show it now when missed a little, else drop it. */
    fun onStart(spec: NoticeSpec, nowMs: Long): String = when {
        spec.atMs > nowMs -> "arm"
        nowMs - spec.atMs <= MISSED_MS -> "show"
        else -> "drop"
    }

    /** A stable whole number for the alarm and the shown notice of one id. */
    fun code(id: String): Int = id.hashCode() and 0x7fffffff

    fun toJson(spec: NoticeSpec): JSONObject = JSONObject().apply {
        put("id", spec.id)
        put("group", spec.group)
        put("channel", spec.channel)
        put("channel_name", spec.channelName)
        put("at_ms", spec.atMs)
        put("title", spec.title)
        put("body", spec.body)
        put("open", spec.open)
        spec.openId?.let { put("open_id", it) }
        spec.callLabel?.let { put("call", JSONObject().put("label", it)) }
        if (spec.later.isNotEmpty()) {
            val options = JSONArray()
            spec.later.forEach { choice ->
                options.put(JSONObject().apply {
                    put("key", choice.key)
                    put("label", choice.label)
                    choice.minutes?.let { put("minutes", it) }
                    choice.atMs?.let { put("at_ms", it) }
                })
            }
            put("later", JSONObject().put("label", spec.laterLabel ?: "").put("options", options))
        }
    }

    fun fromJson(json: String?): NoticeSpec? = try {
        if (json == null) null else parse(map(JSONObject(json)) ?: emptyMap(), null)
    } catch (e: Exception) {
        null
    }

    /** A nested field as a map, whether the bridge hands over a Map or a JSONObject. */
    fun map(value: Any?): Map<String, Any?>? = when (value) {
        is Map<*, *> -> value.entries.associate { (it.key as? String ?: "") to plain(it.value) }
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.opt(it)) }
        else -> null
    }

    fun list(value: Any?): List<Any?> = when (value) {
        is List<*> -> value.map { plain(it) }
        is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
        else -> emptyList()
    }

    private fun plain(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> map(value)
        is JSONArray -> list(value)
        else -> value
    }
}

/**
 * Notice.Schedule / Notice.Cancel / Notice.Opened (crm-employee-app-plan.md §4.4): the phone's own timed notices, shown
 * at their minute by an alarm, also offline and with the app closed. Off unless the app turns on
 * `features.notice_schedule` (then the build has the exact alarm, the restart receiver and the screens; otherwise none
 * of them and every call answers `not_set_up`). Quiet hours and working time are the app's rules (PHP): this only
 * shows each notice at the time it was given.
 */
object NoticeFunctions {
    const val FEATURE = "sylloo_feature_notice_schedule"

    fun enabled(context: Context): Boolean = SyllooFeatures.on(context, FEATURE)

    class Schedule(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!enabled(context)) return BridgeResponse.success(mapOf("scheduled" to false, "reason" to "not_set_up"))
            val spec = NoticeRules.parse(parameters, System.currentTimeMillis())
                ?: return BridgeResponse.success(mapOf("scheduled" to false, "reason" to "invalid"))

            NoticeStore.put(context, spec)
            val exact = NoticeAlarm.arm(context, spec)

            return BridgeResponse.success(mapOf(
                "scheduled" to true,
                "exact" to exact,
                "notifications" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
            ))
        }
    }

    class Cancel(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!enabled(context)) return BridgeResponse.success(mapOf("cancelled" to false, "reason" to "not_set_up"))
            val id = parameters["id"] as? String
            val group = parameters["group"] as? String

            val specs = when {
                NoticeRules.isId(id) -> listOfNotNull(NoticeStore.get(context, id!!))
                NoticeRules.isWord(group) -> NoticeStore.all(context).filter { it.group == group }
                else -> return BridgeResponse.success(mapOf("cancelled" to false, "reason" to "invalid"))
            }

            specs.forEach { NoticeAlarm.remove(context, it.id) }
            if (id != null && specs.isEmpty()) NoticeAlarm.remove(context, id)

            return BridgeResponse.success(mapOf("cancelled" to true, "count" to specs.size))
        }
    }

    class Opened(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val opened = NoticeStore.takeOpened(context)
                ?: return BridgeResponse.success(mapOf("found" to false))

            return BridgeResponse.success(buildMap {
                put("found", true)
                put("open", opened.optString("open"))
                if (opened.has("open_id")) put("open_id", opened.optString("open_id"))
                put("action", opened.optString("action", "open"))
            })
        }
    }
}

/** The notices still to show, kept so a restart can arm them again (no phone numbers, only what is shown). */
object NoticeStore {
    private const val PREFS = "sylloo_notices"
    private const val OPENED = "opened"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(context: Context, spec: NoticeSpec) {
        prefs(context).edit().putString("n:" + spec.id, NoticeRules.toJson(spec).toString()).apply()
    }

    fun get(context: Context, id: String): NoticeSpec? = NoticeRules.fromJson(prefs(context).getString("n:$id", null))

    fun all(context: Context): List<NoticeSpec> = prefs(context).all.entries
        .filter { it.key.startsWith("n:") }
        .mapNotNull { NoticeRules.fromJson(it.value as? String) }

    fun drop(context: Context, id: String) {
        prefs(context).edit().remove("n:$id").apply()
    }

    fun keepOpened(context: Context, spec: NoticeSpec, action: String) {
        val opened = JSONObject().put("open", spec.open).put("action", action)
        spec.openId?.let { opened.put("open_id", it) }
        prefs(context).edit().putString(OPENED, opened.toString()).apply()
        SyllooEvents.send("NoticeOpened", JSONObject(opened.toString()).put("id", spec.id))
    }

    fun takeOpened(context: Context): JSONObject? {
        val json = prefs(context).getString(OPENED, null) ?: return null
        prefs(context).edit().remove(OPENED).apply()
        return try { JSONObject(json) } catch (e: Exception) { null }
    }
}

/** The alarm of each notice (exact when Android allows it, else inexact) and the shown notice itself. */
object NoticeAlarm {
    const val EXTRA_ID = "notice_id"

    /** Arms the alarm; true when it is exact. */
    fun arm(context: Context, spec: NoticeSpec): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val at = maxOf(spec.atMs, System.currentTimeMillis() + 1_000L)
        val pending = alarmIntent(context, spec.id)

        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                true
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                false
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            false
        }
    }

    /** Takes a notice away: its alarm, its kept copy and the notice if it is showing. */
    fun remove(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, id))
        NotificationManagerCompat.from(context).cancel(NoticeRules.code(id))
        NoticeStore.drop(context, id)
    }

    fun show(context: Context, spec: NoticeSpec) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(spec.channel) == null) {
            manager.createNotificationChannel(NotificationChannel(spec.channel, spec.channelName, NotificationManager.IMPORTANCE_HIGH))
        }

        val code = NoticeRules.code(spec.id)
        val builder = NotificationCompat.Builder(context, spec.channel)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(activity(context, NoticeOpenActivity::class.java, spec.id, "open", code))

        spec.callLabel?.let {
            builder.addAction(0, it, activity(context, NoticeOpenActivity::class.java, spec.id, "call", code + 1))
        }
        spec.laterLabel?.let {
            builder.addAction(0, it, activity(context, NoticeLaterActivity::class.java, spec.id, "later", code + 2))
        }

        try {
            NotificationManagerCompat.from(context).notify(code, builder.build())
        } catch (e: SecurityException) {
            // Notices not allowed: nothing to show.
        }
    }

    private fun activity(context: Context, screen: Class<*>, id: String, action: String, code: Int): PendingIntent {
        val intent = Intent(context, screen).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_ID, id)
            putExtra("action", action)
        }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun alarmIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        NoticeRules.code(id),
        Intent(context, NoticeAlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The alarm went off: show the notice (it stays kept until it is tapped, cancelled or shown again by Later). */
class NoticeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NoticeFunctions.enabled(context)) return
        val spec = intent.getStringExtra(NoticeAlarm.EXTRA_ID)?.let { NoticeStore.get(context, it) } ?: return
        NoticeAlarm.show(context, spec)
    }
}

/** After a restart or an app update the alarms are gone: arm the kept notices again (a missed one shows now). */
class NoticeRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NoticeFunctions.enabled(context)) return
        val now = System.currentTimeMillis()

        NoticeStore.all(context).forEach { spec ->
            when (NoticeRules.onStart(spec, now)) {
                "arm" -> NoticeAlarm.arm(context, spec)
                "show" -> NoticeAlarm.show(context, spec)
                else -> NoticeStore.drop(context, spec.id)
            }
        }
    }
}

/** A tap or Call: keeps what to open for Notice.Opened (and tells a running app), takes the notice away, opens the app. */
class NoticeOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(NoticeAlarm.EXTRA_ID)
        val spec = id?.let { NoticeStore.get(this, it) }

        if (spec != null) {
            NoticeStore.keepOpened(this, spec, if (intent.getStringExtra("action") == "call") "call" else "open")
            NoticeAlarm.remove(this, spec.id)
        }

        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(it)
        }
        finish()
    }
}

/** Later: the person picks when (the app's choices, e.g. 15 min · 1 h · Tomorrow) and the notice is set again. */
class NoticeLaterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = intent.getStringExtra(NoticeAlarm.EXTRA_ID)?.let { NoticeStore.get(this, it) }

        if (spec == null || spec.later.isEmpty()) {
            finish()
            return
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(spec.laterLabel ?: spec.title)
            .setItems(spec.later.map { it.label }.toTypedArray()) { _, which ->
                NotificationManagerCompat.from(this).cancel(NoticeRules.code(spec.id))
                val again = spec.copy(atMs = NoticeRules.laterAt(spec.later[which], System.currentTimeMillis()))
                NoticeStore.put(this, again)
                NoticeAlarm.arm(this, again)
            }
            .setOnDismissListener { finish() }
            .show()
    }
}
