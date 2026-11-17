package com.rishadblack.plugins.sylloo_app_native

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The checks of an update (pure, JVM-tested): only https from GitHub's release hosts, a 64-hex SHA-256, the size cap.
 */
object UpdateRules {
    const val MAX_BYTES = 200L * 1024 * 1024
    private val HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val SHA = Regex("^[0-9a-f]{64}$")

    fun isAllowedUrl(value: String?): Boolean {
        val url = try { URL(value ?: return false) } catch (e: Exception) { return false }
        return url.protocol == "https" && url.userInfo == null && url.host.lowercase() in HOSTS
    }

    fun sha(value: String?): String? = value?.trim()?.lowercase()?.takeIf { SHA.matches(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Update.Install (plan §4.6, A13, app-plan §3.2): downloads the APK named by the signed update feed (the PHP side read
 * and checked the feed), checks its SHA-256, and hands it to Android's installer. Android itself refuses an APK not
 * signed with the installed app's key. The answer is the event `UpdateAnswered`: `status` `installing` (the installer
 * is open) | `permission_needed` (the person must allow "Install unknown apps" once; that settings page is opened) |
 * `checksum` (the file did not match, deleted) | `failed`.
 */
object UpdateFunctions {

    class Install(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            val url = parameters["url"] as? String
            val sha = UpdateRules.sha(parameters["sha256"] as? String)

            if (!UpdateRules.isAllowedUrl(url)) return BridgeResponse.error("INVALID_PARAMETERS", "Not a release address")
            if (sha == null) return BridgeResponse.error("INVALID_PARAMETERS", "No SHA-256")

            Thread {
                val payload = try {
                    install(url!!, sha, id)
                } catch (e: Exception) {
                    SyllooEvents.payload(id, "failed").put("message", e.message ?: "")
                }
                SyllooEvents.send("UpdateAnswered", payload)
            }.start()

            return BridgeResponse.success(mapOf("started" to true))
        }

        private fun install(url: String, sha: String, id: String?): org.json.JSONObject {
            val folder = File(context.cacheDir, "sylloo-update").apply { mkdirs() }
            val apk = File(folder, "sylloo-app.apk")
            apk.delete()

            var connection = URL(url).openConnection() as HttpURLConnection
            var hops = 0
            while (true) {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (code in 300..399 && hops < 5) {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (!UpdateRules.isAllowedUrl(next)) return SyllooEvents.payload(id, "failed").put("message", "redirect")
                    connection = URL(next).openConnection() as HttpURLConnection
                    hops++
                    continue
                }
                if (code != 200) return SyllooEvents.payload(id, "failed").put("message", "http $code")
                break
            }

            var total = 0L
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > UpdateRules.MAX_BYTES) throw IllegalStateException("too big")
                        output.write(buffer, 0, read)
                    }
                }
            }
            connection.disconnect()

            val actual = apk.inputStream().use { UpdateRules.sha256(it) }
            if (actual != sha) {
                apk.delete()
                return SyllooEvents.payload(id, "checksum")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return SyllooEvents.payload(id, "permission_needed")
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return SyllooEvents.payload(id, "installing")
        }
    }
}
