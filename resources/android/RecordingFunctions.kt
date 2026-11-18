package com.rishadblack.plugins.sylloo_app_native

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Calls.Folder / Calls.FolderStatus / Calls.Recording / Calls.RecordingDrop (crm-call-recording-plan.md §5 R2, CR3):
 * the phone's own call recorder writes a file per call; the person chooses that folder once (Android's folder chooser,
 * read access to that folder only, kept across restarts), and for each saved work call the app asks for its recording.
 *
 * Off unless the app turns `features.calls_recording` on: then every call answers `not_set_up`. It needs no manifest
 * permission at all (the folder chooser gives the read grant), so the switch adds nothing to the build but the boolean.
 *
 * Calls.Recording finds the call's file by [RecordingRules], and makes an app-private copy for the upload: AAC sound
 * (m4a, mp4, aac, 3gp) re-wrapped as `.mp4` with Android's MediaMuxer (the sound itself is not touched, only its box),
 * mp3 / wav / ogg copied as they are. AMR sound is answered `unsupported` (the ERP's file check knows no AMR yet). The
 * answer carries the copy's path, extension, size and SHA-256; nothing is sent from here: the app sends it. The person's
 * own files are never changed or removed.
 */
object RecordingFunctions {
    const val SWITCH = "sylloo_feature_calls_recording"
    const val TAG = "SyllooRecordings"
    private const val PREFS = "sylloo_call_recordings"
    private const val DIR = "sylloo_recordings"
    private const val MAX_LISTED = 3000

    fun on(context: Context): Boolean = SyllooFeatures.on(context, SWITCH)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? {
        val tree = prefs(context).getString("tree", null) ?: return null
        val uri = Uri.parse(tree)
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

        return if (granted) uri else null
    }

    fun folderName(context: Context): String? = if (folder(context) != null) prefs(context).getString("name", null) else null

    fun copies(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** `{id?}` opens the folder chooser; `{forget: true}` lets the folder go. Answer as CallsFolderAnswered. */
    class Folder(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!on(context)) return BridgeResponse.success(mapOf("status" to "not_set_up"))
            val id = parameters["id"] as? String

            if (parameters["forget"] == true) {
                forget(context)
                return BridgeResponse.success(mapOf("status" to "forgotten"))
            }

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            folder(context)?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }

            val started = SyllooCoordinator.run { coordinator, _ ->
                coordinator.startForResult(intent) { result -> SyllooEvents.send("CallsFolderAnswered", answered(context, id, result.resultCode, result.data?.data)) }
            }

            return BridgeResponse.success(mapOf("status" to if (started) "asking" else "failed"))
        }
    }

    private fun answered(context: Context, id: String?, code: Int, uri: Uri?) = try {
        if (code != Activity.RESULT_OK || uri == null) {
            SyllooEvents.payload(id, "cancelled").put("name", folderName(context) ?: JSONNull)
        } else {
            forget(context, dropCopies = false)
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = (DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { "/" }).take(120)
            prefs(context).edit().putString("tree", uri.toString()).putString("name", name).apply()
            SyllooEvents.payload(id, "picked").put("name", name)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Folder not kept: ${e.javaClass.simpleName}")
        SyllooEvents.payload(id, "failed")
    }

    private val JSONNull = org.json.JSONObject.NULL

    /** Lets the folder go; with [dropCopies] also the copies not sent yet (calls switched off, signed out). */
    fun forget(context: Context, dropCopies: Boolean = true) {
        val tree = prefs(context).getString("tree", null)
        if (tree != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Already gone (the person took it back in Android's settings).
            }
        }
        prefs(context).edit().clear().apply()
        if (dropCopies) copies(context).listFiles()?.forEach { it.delete() }
    }

    class FolderStatus(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!on(context)) return BridgeResponse.success(mapOf("on" to false, "folder" to false))
            val name = folderName(context)

            return BridgeResponse.success(if (name != null) mapOf("on" to true, "folder" to true, "name" to name) else mapOf("on" to true, "folder" to false))
        }
    }

    /**
     * `{call_id, started_at_ms, duration_seconds, key?, max_minutes?, max_bytes?}`: the call's recording as an
     * app-private copy. `status` found (with path, extension, size, sha256) | none | ambiguous | too_long | no_call |
     * unsupported | no_folder | not_set_up | invalid | failed.
     */
    class Recording(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!on(context)) return status("not_set_up")
            val tree = folder(context) ?: return status("no_folder")

            val callId = parameters["call_id"] as? String
            val startedAt = (parameters["started_at_ms"] as? Number)?.toLong() ?: return status("invalid")
            val seconds = (parameters["duration_seconds"] as? Number)?.toLong() ?: return status("invalid")
            val key = (parameters["key"] as? String)?.filter { it in '0'..'9' }?.takeIf { it.length in 6..15 }
            val maxMinutes = ((parameters["max_minutes"] as? Number)?.toInt() ?: RecordingRules.DEFAULT_MAX_MINUTES).coerceIn(1, 240)
            val maxBytes = ((parameters["max_bytes"] as? Number)?.toLong() ?: RecordingRules.DEFAULT_MAX_BYTES).coerceIn(1024L, 200L * 1024 * 1024)
            if (RecordingRules.copyName(callId, "mp4") == null) return status("invalid")

            return try {
                val listed = list(tree, startedAt - 3_600_000L)
                val match = RecordingRules.match(listed.map { it.first }, startedAt, seconds, key, TimeZone.getDefault().getOffset(startedAt).toLong(), maxMinutes, maxBytes)
                if (match.status != "found" || match.file == null) return status(match.status)

                val source = listed.first { it.first == match.file }.second
                val extension = RecordingRules.target(RecordingRules.extension(match.file.name)) ?: return status("unsupported")
                val copy = File(copies(context), RecordingRules.copyName(callId, extension)!!)

                val made = if (extension == "mp4") rewrap(source, copy) else copyAsIs(source, copy)
                if (!made || copy.length() <= 0 || copy.length() > maxBytes) {
                    copy.delete()
                    return status(if (made) "too_long" else "unsupported")
                }

                BridgeResponse.success(mapOf(
                    "status" to "found",
                    "path" to copy.absolutePath,
                    "extension" to extension,
                    "size" to copy.length(),
                    "sha256" to sha256(copy),
                ))
            } catch (e: SecurityException) {
                status("no_folder")
            } catch (e: Exception) {
                Log.e(TAG, "Recording not read: ${e.javaClass.simpleName}")
                status("failed")
            }
        }

        private fun status(value: String) = BridgeResponse.success(mapOf("status" to value))

        /** The folder's files (and one level of folders below it), changed since [sinceMs]: name, time, size and where. */
        private fun list(tree: Uri, sinceMs: Long): List<Pair<RecordingFile, Uri>> {
            val found = ArrayList<Pair<RecordingFile, Uri>>()
            val root = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val folders = ArrayList<String>()

            fun read(children: Uri, depth: Int) {
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    while (cursor.moveToNext() && found.size < MAX_LISTED) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)

                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth == 0) folders.add(id)
                            continue
                        }
                        if (modified < sinceMs) continue

                        found.add(RecordingFile(name, modified, if (cursor.isNull(4)) 0L else cursor.getLong(4)) to DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }

            read(root, 0)
            folders.take(20).forEach { read(DocumentsContract.buildChildDocumentsUriUsingTree(tree, it), 1) }

            return found
        }

        private fun copyAsIs(source: Uri, copy: File): Boolean {
            context.contentResolver.openInputStream(source)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: return false
            return true
        }

        /** The AAC sound track into a new .mp4 box, sample by sample (no re-encoding). False when it has no AAC track. */
        private fun rewrap(source: Uri, copy: File): Boolean {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null

            try {
                extractor.setDataSource(context, source, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                } ?: return false

                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                muxer = MediaMuxer(copy.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val out = muxer.addTrack(format)
                muxer.start()

                val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
                val buffer = ByteBuffer.allocate(size.coerceAtLeast(64 * 1024))
                val info = MediaCodec.BufferInfo()

                while (true) {
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) break
                    info.set(0, read, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(out, buffer, info)
                    extractor.advance()
                }

                muxer.stop()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Recording not re-wrapped: ${e.javaClass.simpleName}")
                copy.delete()
                return false
            } finally {
                try { muxer?.release() } catch (e: Exception) { }
                extractor.release()
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }

            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** `{call_id}`: removes the app's copy of that call's recording (sent, refused, or the call undone). */
    class RecordingDrop(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!on(context)) return BridgeResponse.success(mapOf("dropped" to false))
            val callId = parameters["call_id"] as? String
            var dropped = false

            for (extension in setOf("mp4", "mp3", "wav", "ogg")) {
                val name = RecordingRules.copyName(callId, extension) ?: break
                dropped = File(copies(context), name).delete() || dropped
            }

            return BridgeResponse.success(mapOf("dropped" to dropped))
        }
    }
}
