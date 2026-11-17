package com.rishadblack.plugins.sylloo_app_native

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse

/**
 * Lock.Status / Lock.Prompt (plan §4.6, B3): Android's own fingerprint / face prompt (androidx.biometric), a shortcut
 * for the PIN, never instead of it. The answer comes back as the event `LockAnswered` with `status`:
 * `ok`, `cancelled` (the person chose "Use PIN" or closed it), `failed` (too many tries, locked out), `unavailable`.
 */
object LockFunctions {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** `available` | `none_enrolled` | `no_hardware` | `unavailable`. */
    fun status(context: Context): String = when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> "available"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
        else -> "unavailable"
    }

    class Status(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            BridgeResponse.success(mapOf("status" to status(context)))
    }

    class Prompt(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            val title = (parameters["title"] as? String)?.take(80) ?: "Sylloo App"
            val subtitle = (parameters["subtitle"] as? String)?.take(120)
            val cancel = (parameters["cancel"] as? String)?.take(40) ?: "Use PIN"

            if (status(context) != "available") {
                SyllooEvents.send("LockAnswered", SyllooEvents.payload(id, "unavailable"))
                return BridgeResponse.success(mapOf("started" to false))
            }

            val started = SyllooCoordinator.run { _, activity ->
                val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        SyllooEvents.send("LockAnswered", SyllooEvents.payload(id, "ok"))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val status = when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> "cancelled"
                            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "failed"
                            else -> "unavailable"
                        }
                        SyllooEvents.send("LockAnswered", SyllooEvents.payload(id, status))
                    }
                    // One failed try keeps the prompt open: Android counts the tries itself.
                })

                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { if (subtitle != null) setSubtitle(subtitle) }
                    .setNegativeButtonText(cancel)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .setConfirmationRequired(false)
                    .build()

                prompt.authenticate(info)
            }

            return BridgeResponse.success(mapOf("started" to started))
        }
    }
}
