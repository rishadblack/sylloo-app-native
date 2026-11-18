package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads a product barcode or a QR code from a camera frame's brightness plane (pure, JVM-tested). ZXing core
 * (Apache 2.0, offline, no Google services), the same library as [QrDecoder].
 *
 * The camera's frames lie on their side when the phone is held upright, so a barcode held level on the screen is
 * upright in the frame: when nothing is read, the frame is tried again turned a quarter.
 */
object BarcodeDecoder {
    /** The formats a till reads, with the names PHP gets (`format`). */
    val FORMATS: Map<BarcodeFormat, String> = linkedMapOf(
        BarcodeFormat.EAN_13 to "ean_13",
        BarcodeFormat.EAN_8 to "ean_8",
        BarcodeFormat.UPC_A to "upc_a",
        BarcodeFormat.UPC_E to "upc_e",
        BarcodeFormat.CODE_128 to "code_128",
        BarcodeFormat.CODE_39 to "code_39",
        BarcodeFormat.QR_CODE to "qr_code",
    )

    data class Read(val text: String, val format: String)

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to FORMATS.keys.toList(),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * @param luminance one byte per pixel, `rowStride` bytes per row (the camera's Y plane)
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): Read? {
        read(luminance, rowStride, width, height)?.let { return it }
        val turned = turn(luminance, rowStride, width, height)
        return read(turned, height, height, width)
    }

    private fun read(luminance: ByteArray, rowStride: Int, width: Int, height: Int): Read? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)

        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(HybridBinarizer(source.invert())))) {
            try {
                val result = reader.decodeWithState(bitmap)
                val format = FORMATS[result.barcodeFormat] ?: continue
                return Read(result.text, format)
            } catch (e: Exception) {
                reader.reset()
            }
        }
        return null
    }

    /** The plane turned a quarter clockwise: `height` × `width`, packed rows. */
    fun turn(luminance: ByteArray, rowStride: Int, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                out[x * height + (height - 1 - y)] = luminance[row + x]
            }
        }
        return out
    }
}

/**
 * "Many in a row": the same code read again within [windowMs] is the same item still under the camera, not a second
 * one. A different code, or the same code after the window, counts. Pure, JVM-tested.
 */
class ScanRepeatGuard(private val windowMs: Long = 1500) {
    private var lastCode: String? = null
    private var lastAt: Long = Long.MIN_VALUE

    /** True when this read counts. */
    fun accept(code: String, nowMs: Long): Boolean {
        if (code == lastCode && nowMs - lastAt < windowMs) return false
        lastCode = code
        lastAt = nowMs
        return true
    }
}

/**
 * Scan.Camera (till plan §3.4, camera scanning), off unless the app turns `features.scan_camera` on: the app's own
 * barcode screen (CameraX + ZXing). Each code read goes to PHP at once as the event `ScanReceived` (`code`, `source`
 * `camera`, `format`, `id`); with `continuous` the screen stays open (a beep and a buzz per read, the same code ignored
 * for 1.5 s, a Done button), otherwise the first read closes it. When it closes, `ScanCameraClosed` (`status` done |
 * cancelled | denied | failed, `id`, `count`).
 */
object BarcodeFunctions {

    class Camera(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SyllooFeatures.on(context, SyllooFeatures.SCAN_CAMERA)) return BridgeResponse.success(mapOf("status" to "not_set_up"))

            val id = parameters["id"] as? String
            val continuous = parameters["continuous"] as? Boolean ?: true

            val started = SyllooCoordinator.run { coordinator, activity ->
                coordinator.withPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                    if (!granted) {
                        closed(id, "denied", 0)
                        return@withPermissions
                    }

                    val intent = Intent(activity, BarcodeScanActivity::class.java)
                        .putExtra("id", id)
                        .putExtra("continuous", continuous)

                    coordinator.startForResult(intent) { result ->
                        val count = result.data?.getIntExtra("count", 0) ?: 0
                        val status = when {
                            result.data?.getBooleanExtra("failed", false) == true -> "failed"
                            count > 0 -> "done"
                            else -> "cancelled"
                        }
                        closed(id, status, count)
                    }
                }
            }

            return BridgeResponse.success(mapOf("status" to if (started) "started" else "failed"))
        }

        private fun closed(id: String?, status: String, count: Int) {
            SyllooEvents.send("ScanCameraClosed", SyllooEvents.payload(id, status).put("count", count))
        }
    }
}

/**
 * The barcode screen: the live picture with a wide box to aim at, the last code read, and Done (Cancel before the
 * first read).
 */
class BarcodeScanActivity : ComponentActivity() {
    private val analysisThread = Executors.newSingleThreadExecutor()
    private val closing = AtomicBoolean(false)
    private val count = AtomicInteger(0)
    private val guard = ScanRepeatGuard()
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra("id")
        val continuous = intent.getBooleanExtra("continuous", true)
        tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (e: Exception) { null }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(AimView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val last = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val lastParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        root.addView(last, lastParams)

        val done = Button(this).apply {
            text = getString(android.R.string.cancel)
            setOnClickListener { close() }
        }
        val doneParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        root.addView(done, doneParams)

        // Keep the last code and Done clear of the status and navigation bars (the screen is drawn edge to edge).
        val space = (32 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            lastParams.topMargin = bars.top + space
            doneParams.bottomMargin = bars.bottom + space
            last.layoutParams = lastParams
            done.layoutParams = doneParams
            insets
        }

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                // Thin 1D bars need more than the default 640 × 480; 1280 × 720 keeps a frame quick to read.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisThread) { image ->
                    try {
                        if (!closing.get()) {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            val read = BarcodeDecoder.decode(bytes, plane.rowStride, image.width, image.height)
                            if (read != null && guard.accept(read.text, SystemClock.elapsedRealtime())) {
                                val text = read.text.take(256)
                                count.incrementAndGet()
                                SyllooEvents.send(
                                    "ScanReceived",
                                    JSONObject().put("code", text).put("source", "camera").put("format", read.format).apply { if (id != null) put("id", id) },
                                )
                                runOnUiThread {
                                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                                    root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    last.text = "$text  (${count.get()})"
                                    done.text = getString(android.R.string.ok)
                                    if (!continuous) close()
                                }
                            }
                        }
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            } catch (e: Exception) {
                if (closing.compareAndSet(false, true)) {
                    setResult(Activity.RESULT_CANCELED, Intent().putExtra("failed", true).putExtra("count", count.get()))
                    finish()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun close() {
        if (!closing.compareAndSet(false, true)) return
        setResult(if (count.get() > 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent().putExtra("count", count.get()))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisThread.shutdown()
        tone?.release()
        tone = null
    }

    private class AimView(context: Context) : View(context) {
        private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val boxWidth = width * 0.85f
            val boxHeight = minOf(height * 0.3f, boxWidth * 0.6f)
            val box = RectF((width - boxWidth) / 2, (height - boxHeight) / 2, (width + boxWidth) / 2, (height + boxHeight) / 2)
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRoundRect(box, 24f, 24f, Path.Direction.CW)
            }
            canvas.drawPath(path, dim)
            canvas.drawRoundRect(box, 24f, 24f, line)
        }
    }
}
