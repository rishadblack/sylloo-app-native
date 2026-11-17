package com.rishadblack.plugins.sylloo_app_native

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File

/**
 * Window.Clear (plan §7.6, device-api §3): removes what the cloud window keeps (cookies, page storage, cache) after a
 * release or a deactivation. Android has one web view store for the app, so it is all of it, as the Tauri app did.
 */
object WindowFunctions {
    class Clear(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            Handler(Looper.getMainLooper()).post {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                try {
                    WebView(context).apply { clearCache(true); destroy() }
                } catch (e: Exception) {
                    // No web view could be made: the cookies and storage are gone already.
                }
                File(context.cacheDir, "sylloo-uploads").deleteRecursively()
            }
            return BridgeResponse.success(mapOf("cleared" to true))
        }
    }
}
