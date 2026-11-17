package com.rishadblack.plugins.sylloo_app_native

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The rules of a secret's name and value (pure, JVM-tested): 1–64 of `a-z 0-9 . _ -`, a value of 1–4096 characters.
 * A name becomes a file name only through its SHA-256, so no name can reach outside the folder.
 */
object SecretRules {
    private val NAME = Regex("^[a-z0-9._-]{1,64}$")
    const val MAX_LENGTH = 4096

    fun isName(name: String?): Boolean = name != null && NAME.matches(name)

    fun isValue(value: String?): Boolean = value != null && value.isNotEmpty() && value.length <= MAX_LENGTH

    fun fileName(name: String): String =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".bin"
}

/**
 * The phone's secret vault: each secret AES-GCM encrypted under a Keystore key (`sylloo_app_secrets`) that never leaves
 * the Keystore, kept in the app's no-backup folder (a backup never carries it; the Keystore key would not travel
 * anyway). Plan §4.6 `Secret.*`.
 */
class SecretVault(context: Context) {
    private val folder = File(context.noBackupFilesDir, "sylloo-secrets")

    fun get(name: String): String? {
        val file = File(folder, SecretRules.fileName(name))
        if (!file.exists()) return null

        val key = keystore().getKey(ALIAS, null) as? SecretKey ?: return null
        val blob = file.readBytes()
        if (blob.size <= IV_LENGTH) return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH))
            String(cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    fun set(name: String, value: String) {
        folder.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyOrNew())
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))

        val target = File(folder, SecretRules.fileName(name))
        val tmp = File(folder, target.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("Could not keep the secret")
        }
    }

    fun delete(name: String): Boolean {
        val file = File(folder, SecretRules.fileName(name))
        return !file.exists() || file.delete()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun keyOrNew(): SecretKey {
        (keystore().getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "sylloo_app_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}

/**
 * Secret.Set / Secret.Get / Secret.Delete (plan §4.6). Context-only (`android_params: context`), so a background run
 * (WorkManager, `Sync.Schedule`) can read the phone's data key with no screen open.
 */
object SecretFunctions {

    class Set(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val name = parameters["name"] as? String
            val value = parameters["value"] as? String
            if (!SecretRules.isName(name)) return BridgeResponse.error("INVALID_PARAMETERS", "Bad secret name")
            if (!SecretRules.isValue(value)) return BridgeResponse.error("INVALID_PARAMETERS", "Bad secret value")

            return try {
                SecretVault(context).set(name!!, value!!)
                BridgeResponse.success(mapOf("kept" to true))
            } catch (e: Exception) {
                BridgeResponse.error("EXECUTION_FAILED", e.message ?: "Keystore error")
            }
        }
    }

    class Get(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val name = parameters["name"] as? String
            if (!SecretRules.isName(name)) return BridgeResponse.error("INVALID_PARAMETERS", "Bad secret name")

            return try {
                val value = SecretVault(context).get(name!!)
                if (value == null) BridgeResponse.success(mapOf("found" to false))
                else BridgeResponse.success(mapOf("found" to true, "value" to value))
            } catch (e: Exception) {
                BridgeResponse.error("EXECUTION_FAILED", e.message ?: "Keystore error")
            }
        }
    }

    class Delete(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val name = parameters["name"] as? String
            if (!SecretRules.isName(name)) return BridgeResponse.error("INVALID_PARAMETERS", "Bad secret name")

            return if (SecretVault(context).delete(name!!)) BridgeResponse.success(mapOf("deleted" to true))
            else BridgeResponse.error("EXECUTION_FAILED", "Could not remove the secret")
        }
    }
}
