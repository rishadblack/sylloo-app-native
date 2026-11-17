package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse

/**
 * The pure rules of a location reading (JVM-tested): the time limit, whether a fix is fresh, which of two fixes wins.
 */
object LocationRules {
    const val MIN_TIMEOUT_MS = 2_000L
    const val MAX_TIMEOUT_MS = 60_000L

    fun timeout(requestedMs: Long?): Long = (requestedMs ?: 10_000L).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /** A fix made after the press (its own running-timer stamp not older than the request, one second of slack). */
    fun isFresh(fixElapsedMs: Long, requestedAtElapsedMs: Long): Boolean = fixElapsedMs >= requestedAtElapsedMs - 1_000L

    /** Whether fix a beats fix b: more accurate; a GPS fix beats a network one of the same accuracy. */
    fun better(aAccuracy: Float, aGps: Boolean, bAccuracy: Float, bGps: Boolean): Boolean =
        aAccuracy < bAccuracy || (aAccuracy == bAccuracy && aGps && !bGps)
}

/**
 * Location.Fix (plan §4.6, §7.4): one **fresh** reading with a time limit, from Android's own LocationManager (no
 * Google services), the GPS and the network provider at once; the first GPS fix ends it, else the best fix when the
 * time is up. Never a last-known position. The answer is the event `LocationAnswered`: `status` `ok` | `timeout` |
 * `denied` | `off`, with latitude, longitude, accuracy (m), `fake` (Android's mock mark), `gpsTimeMs` (the fix's own
 * time: satellite time for a GPS fix), `fixElapsedMs` (the running timer at the fix), `elapsedMs` (the running timer
 * now), `bootCount`, `provider`.
 *
 * Asks for the location permission (while the app is open only) when it is not granted yet. Never background location.
 */
object LocationFunctions {

    class Fix(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            val timeoutMs = LocationRules.timeout((parameters["timeout_ms"] as? Number)?.toLong())

            val started = SyllooCoordinator.run { coordinator, activity ->
                coordinator.withPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted ->
                    if (!granted && !SyllooCoordinator.hasPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        SyllooEvents.send("LocationAnswered", SyllooEvents.payload(id, "denied"))
                    } else {
                        Reading(activity.applicationContext, id, timeoutMs).start()
                    }
                }
            }

            return BridgeResponse.success(mapOf("started" to started))
        }
    }

    private class Reading(private val context: Context, private val id: String?, private val timeoutMs: Long) {
        private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        private val handler = Handler(Looper.getMainLooper())
        private val requestedAt = SystemClock.elapsedRealtime()
        private val signals = mutableListOf<CancellationSignal>()
        private val listeners = mutableListOf<LocationListener>()
        private var best: Location? = null
        private var done = false

        @SuppressLint("MissingPermission")
        fun start() {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                .filter { it != LocationManager.GPS_PROVIDER || SyllooCoordinator.hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) }

            if (providers.isEmpty()) {
                finish("off")
                return
            }

            for (provider in providers) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    signals.add(signal)
                    manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location -> take(location) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) = take(location)

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                        override fun onProviderEnabled(provider: String) {}

                        override fun onProviderDisabled(provider: String) {}
                    }
                    listeners.add(listener)
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }

            handler.postDelayed({ finish(if (best != null) "ok" else "timeout") }, timeoutMs)
        }

        private fun take(location: Location?) {
            if (done || location == null) return
            val fixElapsedMs = location.elapsedRealtimeNanos / 1_000_000L
            if (!LocationRules.isFresh(fixElapsedMs, requestedAt)) return

            val current = best
            val isGps = location.provider == LocationManager.GPS_PROVIDER
            if (current == null || LocationRules.better(location.accuracy, isGps, current.accuracy, current.provider == LocationManager.GPS_PROVIDER)) {
                best = location
            }

            if (isGps) finish("ok")
        }

        private fun finish(status: String) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            signals.forEach { it.cancel() }
            listeners.forEach { runCatching { manager.removeUpdates(it) } }

            val payload = SyllooEvents.payload(id, status)
            val location = best
            if (status == "ok" && location != null) {
                payload.put("latitude", location.latitude)
                payload.put("longitude", location.longitude)
                payload.put("accuracy", location.accuracy.toDouble())
                payload.put("fake", isMock(location))
                payload.put("gpsTimeMs", location.time)
                payload.put("fixElapsedMs", location.elapsedRealtimeNanos / 1_000_000L)
                payload.put("provider", location.provider ?: "")
            }
            payload.put("elapsedMs", SystemClock.elapsedRealtime())
            payload.put("bootCount", bootCount())

            SyllooEvents.send("LocationAnswered", payload)
        }

        @Suppress("DEPRECATION")
        private fun isMock(location: Location): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

        private fun bootCount(): Int = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }
    }
}
