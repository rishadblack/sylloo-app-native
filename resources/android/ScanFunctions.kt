package com.rishadblack.plugins.sylloo_app_native

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.ui.MainActivity
import org.json.JSONObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Scan.Keys (till plan §3.4, §7.2), off unless the app turns `features.scan_keys` on: a scanner that types (USB or
 * Bluetooth keyboard mode) while no field has the cursor. The key strokes of NativePHP's screen are watched (its
 * window's callback is wrapped; nothing else changes), fast ones are grouped by [ScanKeyGrouper], and each code is
 * handed to PHP as the event `ScanReceived` (`code`, `source` `keys`). The scanner's Enter is kept from the app;
 * every other key reaches the screen as before. While a text field has the cursor nothing is grouped.
 *
 * Needs no permission. `on: false` stops it (the wrap stays and passes everything through).
 */
object ScanFunctions {

    class Keys(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SyllooFeatures.on(context, SyllooFeatures.SCAN_KEYS)) return mapOf("status" to "not_set_up")

            val on = parameters["on"] as? Boolean ?: true
            if (!on) {
                ScanKeys.stop()
                return mapOf("status" to "off")
            }

            val grouper = ScanKeyGrouper.from(
                parameters["gap_ms"] as? Number,
                parameters["end_ms"] as? Number,
                parameters["min_length"] as? Number,
                parameters["end_on_gap"] as? Boolean,
            )
            ScanKeys.start(context, grouper)
            return mapOf("status" to "on")
        }
    }
}

/**
 * The app-wide key watch behind Scan.Keys. Main thread only (key events and the quiet timer both run there).
 */
object ScanKeys {
    private const val TAG = "SyllooScanKeys"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var grouper: ScanKeyGrouper? = null
    private var lifecycleWatched = false
    private var swallowEnterUp = false

    private val quiet = Runnable {
        val code = grouper?.flush(SystemClock.uptimeMillis())
        if (code != null) deliver(code)
    }

    fun start(context: Context, settings: ScanKeyGrouper) {
        grouper = settings
        main.post {
            watchActivities(context)
            MainActivity.instance?.let { wrap(it) }
        }
    }

    fun stop() {
        grouper = null
        main.post {
            main.removeCallbacks(quiet)
            swallowEnterUp = false
        }
    }

    /** NativePHP's activity is made again (rotation, return from the background): wrap each new one. */
    private fun watchActivities(context: Context) {
        if (lifecycleWatched) return
        val app = context.applicationContext as? Application ?: return
        lifecycleWatched = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity && grouper != null) wrap(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private class Watch(val original: Window.Callback, val activity: Activity) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "dispatchKeyEvent" && args?.size == 1) {
                val event = args[0] as? KeyEvent
                if (event != null && onKey(activity, event)) return true
            }
            return try {
                if (args == null) method.invoke(original) else method.invoke(original, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun wrap(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (Proxy.isProxyClass(current.javaClass) && Proxy.getInvocationHandler(current) is Watch) return

        window.callback = Proxy.newProxyInstance(
            Window.Callback::class.java.classLoader,
            arrayOf(Window.Callback::class.java),
            Watch(current, activity),
        ) as Window.Callback
    }

    /** True when the app must not see this key (the Enter that ended a code). */
    private fun onKey(activity: Activity, event: KeyEvent): Boolean {
        val grouper = grouper ?: return false
        val enter = event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        if (event.action == KeyEvent.ACTION_UP) {
            if (enter && swallowEnterUp) {
                swallowEnterUp = false
                return true
            }
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        val typingInField = try { activity.currentFocus?.onCheckIsTextEditor() == true } catch (e: Exception) { false }
        val unicode = event.unicodeChar
        val key = when {
            enter -> ScanKeyGrouper.Key.Enter
            KeyEvent.isModifierKey(event.keyCode) -> ScanKeyGrouper.Key.Modifier
            unicode > 0 && !Character.isISOControl(unicode) -> ScanKeyGrouper.Key.Typed(unicode.toChar())
            else -> ScanKeyGrouper.Key.Other
        }

        val step = grouper.key(key, event.eventTime, typingInField)
        main.removeCallbacks(quiet)
        if (grouper.pending > 0 && grouper.endOnGap) main.postDelayed(quiet, grouper.endMs + 5)
        step.code?.let { deliver(it) }
        if (step.consume) swallowEnterUp = true
        return step.consume
    }

    private fun deliver(code: String) {
        Log.d(TAG, "Code of ${code.length} characters")
        SyllooEvents.send("ScanReceived", JSONObject().put("code", code).put("source", "keys"))
    }
}
