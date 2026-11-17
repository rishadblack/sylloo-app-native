package com.rishadblack.plugins.sylloo_app_native

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.ui.MainActivity
import com.nativephp.mobile.ui.nativerender.NativeElementBridge
import org.json.JSONObject

/**
 * Sends a job's answer back to PHP as a native event (`#[On(Event::class)]` on a native screen). The payload's keys are
 * the event class's constructor parameters (camelCase).
 */
object SyllooEvents {
    const val NS = "Rishadblack\\SyllooAppNative\\Events\\"

    fun send(event: String, payload: JSONObject) {
        val name = if (event.contains("\\")) event else NS + event
        Handler(Looper.getMainLooper()).post {
            try {
                NativeElementBridge.sendNativeEvent(name, payload.toString())
            } catch (e: Throwable) {
                Log.e("SyllooEvents", "Could not send $name: ${e.message}")
            }
        }
    }

    fun payload(id: String?, status: String): JSONObject = JSONObject().apply {
        put("status", status)
        if (id != null) put("id", id)
    }
}

/**
 * A headless fragment on NativePHP's activity that asks for runtime permissions and starts screens that answer
 * (the camera, the QR scanner, the file chooser), one request of each kind at a time. Launchers are registered in
 * onCreate, as AndroidX requires.
 */
class SyllooCoordinator : Fragment() {

    private var permissionCallback: ((Map<String, Boolean>) -> Unit)? = null
    private var resultCallback: ((ActivityResult) -> Unit)? = null

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var resultLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val callback = permissionCallback
            permissionCallback = null
            callback?.invoke(granted)
        }

        resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = resultCallback
            resultCallback = null
            callback?.invoke(result)
        }
    }

    /**
     * Ask for the permissions not granted yet; the callback gets true when every one is granted.
     */
    fun withPermissions(permissions: Array<String>, callback: (Boolean) -> Unit) {
        val context = requireContext()
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            callback(true)
            return
        }

        permissionCallback?.invoke(emptyMap())
        permissionCallback = { granted -> callback(missing.all { granted[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
        permissionLauncher.launch(missing.toTypedArray())
    }

    fun startForResult(intent: Intent, callback: (ActivityResult) -> Unit) {
        resultCallback?.invoke(ActivityResult(android.app.Activity.RESULT_CANCELED, null))
        resultCallback = callback
        resultLauncher.launch(intent)
    }

    companion object {
        private const val TAG = "SyllooCoordinator"

        fun install(activity: FragmentActivity): SyllooCoordinator {
            val manager = activity.supportFragmentManager
            (manager.findFragmentByTag(TAG) as? SyllooCoordinator)?.let { return it }

            val coordinator = SyllooCoordinator()
            manager.beginTransaction().add(coordinator, TAG).commitNowAllowingStateLoss()
            return coordinator
        }

        /**
         * Run on the main thread with the coordinator of NativePHP's activity; false when no activity is open.
         */
        fun run(block: (SyllooCoordinator, FragmentActivity) -> Unit): Boolean {
            val activity = MainActivity.instance ?: return false
            Handler(Looper.getMainLooper()).post {
                try {
                    block(install(activity), activity)
                } catch (e: Throwable) {
                    Log.e(TAG, "Coordinator call failed: ${e.message}", e)
                }
            }
            return true
        }

        fun hasPermission(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
