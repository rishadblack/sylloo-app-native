package com.rishadblack.plugins.sylloo_app_native

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse

/**
 * Clock.Elapsed: the running timer and the restart count (plan §4.6).
 *
 * elapsed_ms  : SystemClock.elapsedRealtime(), milliseconds since the phone started, sleep included.
 *               It cannot be changed by the user, unlike the wall clock.
 * boot_count  : Settings.Global.BOOT_COUNT, how many times the phone has started (API 24+);
 *               -1 when the phone does not report it.
 */
object ClockFunctions {

    class Elapsed(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val bootCount = try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
            } catch (e: Settings.SettingNotFoundException) {
                -1
            }

            return BridgeResponse.success(mapOf(
                "elapsed_ms" to SystemClock.elapsedRealtime(),
                "boot_count" to bootCount,
                "wall_ms" to System.currentTimeMillis()
            ))
        }
    }
}
