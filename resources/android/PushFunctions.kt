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
    fun text(value: String?, max: Int): String = (value ?: "").trim().take(max)

    fun address(value: String?): String? {
        val url = value?.trim() ?: return null
        return if (url.startsWith("https://") && url.length <= 2048 && !url.contains(' ')) url else null
    }
}

/**
 * Push.Permission / Push.Token / Push.Opened (plan §4.6, §7.9): Firebase Cloud Messaging. The Firebase settings come
 * from the app's `google-services.json` (never in git), turned into Android resources at build time by this package's
 * copy-assets hook; without them push is "not set up" and nothing else breaks.
 *
 * Answers: `PushPermissionAnswered` (`granted` | `denied`), `PushTokenAnswered` (`ok` with `token`, `changed` with
 * `token` when Firebase renews it, `not_set_up`, `failed`); a tapped notice keeps its address for Push.Opened and also
 * sends `PushOpened` (`url`) when the app is running.
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
            prefs.edit().remove("opened_url").apply()

            return BridgeResponse.success(if (url == null) mapOf("found" to false) else mapOf("found" to true, "url" to url))
        }
    }
}

/**
 * Firebase's messages to this phone. The office sends data messages (`title`, `body`, `url`); the phone shows them
 * itself, so a tap always comes through {@see PushOpenActivity} with its address (never private numbers in the text:
 * the office's rule, plan §7.9).
 */
class SyllooMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        SyllooEvents.send("PushTokenAnswered", JSONObject().put("status", "changed").put("token", token))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = PushRules.text(data["title"] ?: message.notification?.title, 120)
        val body = PushRules.text(data["body"] ?: message.notification?.body, 400)
        if (title.isEmpty() && body.isEmpty()) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sylloo", NotificationManager.IMPORTANCE_DEFAULT))
        }

        val open = Intent(this, PushOpenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            PushRules.address(data["url"])?.let { putExtra("url", it) }
        }
        val pending = PendingIntent.getActivity(this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notice)
        } catch (e: SecurityException) {
            // Notifications not allowed: nothing to show.
        }
    }

    companion object {
        const val CHANNEL = "sylloo_notices"
    }
}

/**
 * A tapped notice: keeps its address for Push.Opened, tells a running app (`PushOpened`), and opens the app.
 */
class PushOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = PushRules.address(intent.getStringExtra("url"))
        if (url != null) {
            getSharedPreferences(PushFunctions.PREFS, Context.MODE_PRIVATE).edit().putString("opened_url", url).apply()
            SyllooEvents.send("PushOpened", JSONObject().put("url", url))
        }

        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(it)
        }
        finish()
    }
}
