package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * The names of saved files (pure, JVM-tested): no folders, no hidden files, a sane length, an extension from the type.
 */
object WindowFileNames {
    fun safe(name: String?, fallback: String = "download"): String {
        val base = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_")
            .trim().trimStart('.')
        return (if (base.isEmpty()) fallback else base).take(120)
    }

    /** The type and bytes of a `data:` address; null when it is not base64 data or too big (25 MB). */
    fun decodeData(dataUrl: String?): Pair<String, ByteArray>? {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val head = dataUrl.substring(5, comma)
        if (!head.endsWith(";base64")) return null
        if (dataUrl.length - comma > 34 * 1024 * 1024) return null
        val mime = head.removeSuffix(";base64").ifEmpty { "application/octet-stream" }
        return try {
            Pair(mime, Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Uploads in the cloud window: Android's file chooser, with the camera offered beside it for pictures (a page that
 * asks for a photo, `capture`, opens the camera at once).
 */
object WindowFiles {
    fun choose(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        val accepts = params.acceptTypes.filter { it.isNotBlank() }
        val pictures = accepts.isEmpty() || accepts.any { it.startsWith("image/") || it in setOf(".jpg", ".jpeg", ".png", ".webp", ".heic") || it == "*/*" }
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val started = SyllooCoordinator.run { coordinator, activity ->
            val pick = { cameraFile: File? ->
                val cameraIntent = cameraFile?.let { file ->
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val intent = if (params.isCaptureEnabled && cameraIntent != null) {
                    cameraIntent
                } else {
                    val content = params.createIntent().apply { if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
                    Intent.createChooser(content, params.title ?: "").apply {
                        if (cameraIntent != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                }

                try {
                    coordinator.startForResult(intent) { result ->
                        val data = result.data
                        val uris: Array<Uri>? = when {
                            result.resultCode != Activity.RESULT_OK -> null
                            data?.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
                            data?.data != null -> arrayOf(data.data!!)
                            cameraFile != null && cameraFile.length() > 0 -> arrayOf(FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", cameraFile))
                            else -> null
                        }
                        callback.onReceiveValue(uris)
                    }
                } catch (e: Exception) {
                    callback.onReceiveValue(null)
                }
            }

            if (!pictures) {
                pick(null)
            } else {
                coordinator.withPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                    val file = if (granted) File(File(activity.cacheDir, "sylloo-uploads").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg") else null
                    pick(file)
                }
            }
        }

        if (!started) callback.onReceiveValue(null)
        return true
    }
}

/**
 * Links for the phone's own apps (WindowFilter.opensPhoneApp): one ACTION_VIEW intent, never `intent:` or an app the
 * page names. False when no app takes it (the caller then hands the address to the normal browser).
 */
object WindowExternal {
    fun open(context: Context, url: String): Boolean {
        if (!WindowFilter.opensPhoneApp(url)) return false

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.selector = null
            intent.component = null
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Downloads of the cloud window: files of the business go to Downloads through Android's download manager with the
 * window's cookies (its notice has "Open"); a page-made file is saved there directly.
 */
object WindowDownloads {
    fun download(context: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, name: String?) {
        val fileName = WindowFileNames.safe(name ?: URLUtil.guessFileName(url, contentDisposition, mimeType))
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", userAgent ?: "")
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(context, fileName, Toast.LENGTH_SHORT).show()
    }

    fun saveData(context: Context, dataUrl: String?, name: String?) {
        val (mime, bytes) = WindowFileNames.decodeData(dataUrl) ?: return
        val fileName = WindowFileNames.safe(name)

        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@Thread
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@Thread
                    File(folder, fileName).writeBytes(bytes)
                }
                Handler(Looper.getMainLooper()).post { Toast.makeText(context, fileName, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                // Not saved: the page stays as it was.
            }
        }.start()
    }
}

/**
 * Printing from the cloud window: Android's print screen (any printer the phone knows, or Save as PDF). The office's
 * print page is loaded in a hidden print-only web view (no scripts of the app, no file access, never leaves the page's
 * host) and dropped after the hand-over.
 */
object WindowPrint {
    fun printWindow(webView: WebView, title: String?) {
        val name = WindowFileNames.safe(title, "Sylloo")
        val manager = webView.context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(name, webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
    }

    fun printPage(context: Context, url: String, title: String?) {
        val printer = WebView(context)
        printer.settings.javaScriptEnabled = true
        printer.settings.allowFileAccess = false
        printer.settings.allowContentAccess = false
        val host = Uri.parse(url).host
        printer.webViewClient = object : WebViewClient() {
            private var printed = false

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                !request.url.host.equals(host, ignoreCase = true)

            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                if (printed) return
                printed = true
                printWindow(view, title ?: view.title)
                Handler(Looper.getMainLooper()).postDelayed({ view.destroy() }, 60_000L)
            }
        }
        printer.loadUrl(url)
    }
}
