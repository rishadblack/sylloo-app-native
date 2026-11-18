package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONObject

/**
 * What a notice may carry (pure, JVM-tested): a title and a text, cut to a sane length, and an address that is only
 * kept when it is https (the PHP side checks it again against the business's hosts and allowed paths before opening).
 */
object PushRules {
    private val WORD = Regex("^[a-z0-9_.-]{1,40}$")
    private val ID = Regex("^[A-Za-z0-9_\\-:.]{1,64}$")

    const val MAX_ACTIONS = 2
    const val MAX_LABEL = 40
    const val MAX_LABELS = 50

    fun text(value: String?, max: Int): String = (value ?: "").trim().take(max)

    fun address(value: String?): String? {
        val url = value?.trim() ?: return null
        return if (url.startsWith("https://") && url.length <= 2048 && !url.contains(' ')) url else null
    }

    /** A screen name (`open`), a button key or a label key: `[a-z0-9_.-]{1,40}`, else null. */
    fun word(value: String?): String? = value?.trim()?.takeIf { WORD.matches(it) }

    /** A record id (`open_id`): `[A-Za-z0-9_-:.]{1,64}`, else null. */
    fun id(value: String?): String? = value?.trim()?.takeIf { ID.matches(it) }

    /**
     * The app's label map (`Push.Labels`, e.g. `{"crm.call": "Call"}`, already in the person's language): only good
     * keys and short texts are kept.
     */
    fun labels(value: Any?): Map<String, String> = (NoticeRules.map(value) ?: emptyMap()).entries
        .mapNotNull { (key, label) ->
            val word = word(key)
            val text = text(label as? String, MAX_LABEL)
            if (word == null || text.isEmpty()) null else word to text
        }
        .take(MAX_LABELS)
        .toMap()

    /**
     * The buttons of a data message (`actions`, a JSON text `[{"key": "call", "label": "crm.call"}]`, device-api.md
     * §1.1): each one's label is looked up in the app's map; a button whose label the app did not give is left out (no
     * raw key is ever shown). At most two.
     */
    fun actions(json: String?, labels: Map<String, String>): List<Pair<String, String>> {
        val list = try {
            if (json.isNullOrBlank()) emptyList() else NoticeRules.list(org.json.JSONArray(json))
        } catch (e: Exception) {
            emptyList()
        }

        return list.mapNotNull { NoticeRules.map(it) }
            .mapNotNull { action ->
                val key = word(action["key"] as? String)
                val label = word(action["label"] as? String)?.let { labels[it] }
                if (key == null || label == null) null else key to label
            }
            .distinctBy { it.first }
            .take(MAX_ACTIONS)
    }

    /** What a tap keeps for Push.Opened: the address and / or the screen, its record and the button (`open` for a tap). */
    fun opened(url: String?, open: String?, openId: String?, action: String?): JSONObject? {
        val address = address(url)
        val screen = word(open)
        if (address == null && screen == null) return null

        return JSONObject().apply {
            address?.let { put("url", it) }
            screen?.let {
                put("open", it)
                id(openId)?.let { record -> put("open_id", record) }
                put("action", word(action) ?: "open")
            }
        }
    }
}

/**
 * Push.Permission / Push.Token / Push.Opened (plan §4.6, §7.9): Firebase Cloud Messaging. The Firebase settings come
 * from the app's `google-services.json` (never in git), turned into Android resources at build time by this package's
 * copy-assets hook; without them push is "not set up" and nothing else breaks.
 *
 * Answers: `PushPermissionAnswered` (`granted` | `denied`), `PushTokenAnswered` (`ok` with `token`, `changed` with
 * `token` when Firebase renews it, `not_set_up`, `failed`); a tapped notice keeps its address and its screen (`open`,
 * `open_id`, the pressed button as `action`) for Push.Opened, and when the app is running also sends `PushOpened`
 * (`url`) and, for a screen, `NoticeOpened` (`open`, `open_id`, `action`). Push.Labels keeps the app's button labels.
 */
object PushFunctions {
    const val PREFS = "sylloo_push"

    class Permission(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                SyllooEvents.send("PushPermissionAnswered", SyllooEvents.payload(id, if (enabled) "granted" else "denied"))
                return BridgeResponse.success(mapOf("started" to true))
            }

            val started = SyllooCoordinator.run { coordinator, _ ->
                coordinator.withPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { granted ->
                    SyllooEvents.send("PushPermissionAnswered", SyllooEvents.payload(id, if (granted) "granted" else "denied"))
                }
            }

            return BridgeResponse.success(mapOf("started" to started))
        }
    }

    class Token(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String

            if (FirebaseApp.getApps(context).isEmpty()) {
                SyllooEvents.send("PushTokenAnswered", SyllooEvents.payload(id, "not_set_up"))
                return BridgeResponse.success(mapOf("started" to false))
            }

            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val payload = if (task.isSuccessful && !task.result.isNullOrEmpty()) {
                    SyllooEvents.payload(id, "ok").put("token", task.result)
                } else {
                    SyllooEvents.payload(id, "failed")
                }
                SyllooEvents.send("PushTokenAnswered", payload)
            }

            return BridgeResponse.success(mapOf("started" to true))
        }
    }

    class Opened(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = prefs.getString("opened_url", null)
            val opened = try {
                prefs.getString("opened", null)?.let { JSONObject(it) }
            } catch (e: Exception) {
                null
            }
            prefs.edit().remove("opened_url").remove("opened").apply()

            if (url == null && opened == null) return BridgeResponse.success(mapOf("found" to false))

            return BridgeResponse.success(buildMap {
                put("found", true)
                url?.let { put("url", it) }
                opened?.optString("open")?.takeIf { it.isNotEmpty() }?.let { open ->
                    put("open", open)
                    opened.optString("open_id").takeIf { it.isNotEmpty() }?.let { put("open_id", it) }
                    put("action", opened.optString("action", "open"))
                }
            })
        }
    }

    /** `{labels: {"crm.call": "Call", …}}`: the texts of the buttons a push may ask for, in the person's language. */
    class Labels(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val labels = PushRules.labels(parameters["labels"])
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("labels", JSONObject(labels).toString()).apply()

            return BridgeResponse.success(mapOf("saved" to labels.size))
        }
    }

    fun labels(context: Context): Map<String, String> = try {
        PushRules.labels(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("labels", null)?.let { JSONObject(it) })
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * Firebase's messages to this phone. The office sends data messages (`title`, `body`, `url`, and optionally `open`,
 * `open_id`, `actions`, device-api.md §1.1); the phone shows them itself, so a tap always comes through
 * {@see PushOpenActivity} with its address or screen (never private numbers in the text: the office's rule, plan §7.9).
 * A button is shown only when the app gave its label (Push.Labels); it opens the app with its key as the action.
 */
class SyllooMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        SyllooEvents.send("PushTokenAnswered", JSONObject().put("status", "changed").put("token", token))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // A silent push that asks for work (device-api.md §1.1): `wake: messages` runs Messages.Work now, shows nothing.
        if (MessagesRules.isWake(data)) {
            MessagesWork.wake(applicationContext)
            return
        }

        val title = PushRules.text(data["title"] ?: message.notification?.title, 120)
        val body = PushRules.text(data["body"] ?: message.notification?.body, 400)
        if (title.isEmpty() && body.isEmpty()) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sylloo", NotificationManager.IMPORTANCE_DEFAULT))
        }

        val code = (System.currentTimeMillis() % (Int.MAX_VALUE / 4)).toInt() * 4
        val tap = { action: String, request: Int ->
            val open = Intent(this, PushOpenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                PushRules.address(data["url"])?.let { putExtra("url", it) }
                PushRules.word(data["open"])?.let { putExtra("open", it) }
                PushRules.id(data["open_id"])?.let { putExtra("open_id", it) }
                putExtra("action", action)
                putExtra("notice", code)
            }
            PendingIntent.getActivity(this, request, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap("open", code))

        // Buttons only with a screen to open (a button on an address-only notice would do the same as the tap).
        if (PushRules.word(data["open"]) != null) {
            PushRules.actions(data["actions"], PushFunctions.labels(this)).forEachIndexed { index, (key, label) ->
                builder.addAction(0, label, tap(key, code + 1 + index))
            }
        }

        try {
            NotificationManagerCompat.from(this).notify(code, builder.build())
        } catch (e: SecurityException) {
            // Notifications not allowed: nothing to show.
        }
    }

    companion object {
        const val CHANNEL = "sylloo_notices"
    }
}

/**
 * A tapped notice or one of its buttons: keeps its address and screen for Push.Opened, tells a running app
 * (`PushOpened` for an address, `NoticeOpened` for a screen), takes the notice away and opens the app.
 */
class PushOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val opened = PushRules.opened(intent.getStringExtra("url"), intent.getStringExtra("open"), intent.getStringExtra("open_id"), intent.getStringExtra("action"))
        if (opened != null) {
            val prefs = getSharedPreferences(PushFunctions.PREFS, Context.MODE_PRIVATE).edit()
            if (opened.has("url")) prefs.putString("opened_url", opened.getString("url")) else prefs.remove("opened_url")
            if (opened.has("open")) prefs.putString("opened", opened.toString()) else prefs.remove("opened")
            prefs.apply()

            if (opened.has("url")) SyllooEvents.send("PushOpened", JSONObject().put("url", opened.getString("url")))
            if (opened.has("open")) {
                val event = JSONObject().put("open", opened.getString("open")).put("action", opened.getString("action"))
                if (opened.has("open_id")) event.put("open_id", opened.getString("open_id"))
                SyllooEvents.send("NoticeOpened", event)
            }
        }

        // A button does not take its notice away by itself.
        intent.getIntExtra("notice", -1).takeIf { it >= 0 }?.let { NotificationManagerCompat.from(this).cancel(it) }

        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(it)
        }
        finish()
    }
}
