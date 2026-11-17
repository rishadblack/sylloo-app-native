package com.rishadblack.plugins.sylloo_app_native

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.bridge.LaravelEnvironment
import com.nativephp.mobile.bridge.PHPBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Which artisan command a background run may start (pure, JVM-tested): only a plain command name of the app (letters,
 * digits, `:`, `-`), never arguments, so nothing the PHP side sends can become a shell of its own.
 */
object SyncRules {
    const val DEFAULT_COMMAND = "sylloo-app-native:send-waiting"
    private val COMMAND = Regex("^[a-z][a-z0-9:-]{2,63}$")

    fun command(value: String?): String? {
        val name = value?.trim() ?: return DEFAULT_COMMAND
        return if (COMMAND.matches(name)) name else null
    }

    /** WorkManager's shortest period is 15 minutes; 0 or less = no repeating run. */
    fun periodMinutes(value: Number?): Long {
        val minutes = value?.toLong() ?: 0L
        return if (minutes <= 0L) 0L else minutes.coerceIn(15L, 24L * 60L)
    }
}

/**
 * Sync.Schedule / Sync.Cancel (plan §4.6, §7.7): sends what waits when the internet is back, also with the app closed.
 * Android's WorkManager runs {@see SendWaitingWorker} once the phone is online (one run waits at a time; a new
 * Schedule while one waits keeps the waiting one), and, with `period_minutes`, again every period. The worker boots the
 * app's own PHP without a screen (NativePHP's background runtime) and runs one artisan command (default
 * `sylloo-app-native:send-waiting`), which sends the waiting list in order with its one-use keys: a second run never
 * sends a write twice.
 */
object SyncFunctions {
    const val ONCE = "sylloo-send-waiting"
    const val REPEAT = "sylloo-send-waiting-repeat"

    class Schedule(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val command = SyncRules.command(parameters["command"] as? String)
                ?: return BridgeResponse.error("INVALID_PARAMETERS", "Not an allowed command")
            val period = SyncRules.periodMinutes(parameters["period_minutes"] as? Number)

            val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val input = Data.Builder().putString("command", command).build()
            val manager = WorkManager.getInstance(context.applicationContext)

            manager.enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SendWaitingWorker>()
                    .setConstraints(online)
                    .setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )

            if (period > 0L) {
                manager.enqueueUniquePeriodicWork(
                    REPEAT,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<SendWaitingWorker>(period, TimeUnit.MINUTES)
                        .setConstraints(online)
                        .setInputData(input)
                        .build()
                )
            }

            return BridgeResponse.success(mapOf("scheduled" to true, "command" to command, "period_minutes" to period))
        }
    }

    class Cancel(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(ONCE)
            manager.cancelUniqueWork(REPEAT)
            return BridgeResponse.success(mapOf("cancelled" to true))
        }
    }
}

/**
 * The background run of Sync.Schedule: NativePHP's background PHP (`initializeForBackground` + the ephemeral runtime),
 * the context-only bridge functions (so `Secret.Get` can open the phone's data key with no screen), one artisan
 * command. A failed run is tried again later by WorkManager.
 */
class SendWaitingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val command = SyncRules.command(inputData.getString("command")) ?: return@withContext Result.failure()

        lock.withLock {
            try {
                com.nativephp.mobile.bridge.plugins.registerContextOnlyBridgeFunctions(applicationContext)
                LaravelEnvironment(applicationContext).initializeForBackground()

                val bridge = PHPBridge(applicationContext)
                bridge.ensureRuntimeInitialized()
                val booted = bridge.nativeEphemeralBoot("${bridge.getLaravelPath()}/vendor/nativephp/mobile/bootstrap/android/persistent.php")
                if (booted != 0) {
                    Log.e(TAG, "Background PHP did not start ($booted)")
                    return@withContext Result.retry()
                }

                try {
                    val output = bridge.nativeEphemeralArtisan("$command --no-interaction")
                    Log.i(TAG, "Sent what waits: ${output.take(200)}")
                    if (output.contains("RETRY")) Result.retry() else Result.success()
                } finally {
                    bridge.nativeEphemeralShutdown()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Background send failed: ${e.message}", e)
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "SyllooSendWaiting"
        private val lock = ReentrantLock()
    }
}
