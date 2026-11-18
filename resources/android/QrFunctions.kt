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
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a QR code from a camera frame's brightness plane (pure, JVM-tested). ZXing core (Apache 2.0): offline, no
 * Google services.
 */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * @param luminance one byte per pixel, `rowStride` bytes per row (the camera's Y plane)
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)

        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(HybridBinarizer(source.invert())))) {
            try {
                return reader.decodeWithState(bitmap).text
            } catch (e: NotFoundException) {
                reader.reset()
            } catch (e: Exception) {
                reader.reset()
            }
        }
        return null
    }
}

/**
 * Qr.Scan (plan §4.6, §7.2, §7.4): the app's own scanner screen (CameraX + ZXing core), without internet and without
 * Google services. The answer is the event `QrAnswered`: `status` `scanned` (with `text`) | `cancelled` | `denied` |
 * `failed`. The text is only data: the PHP side decides what it means (an activation link, a door's rotating code).
 */
object QrFunctions {

    class Scan(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String

            val started = SyllooCoordinator.run { coordinator, activity ->
                coordinator.withPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                    if (!granted) {
                        SyllooEvents.send("QrAnswered", SyllooEvents.payload(id, "denied"))
                        return@withPermissions
                    }

                    coordinator.startForResult(Intent(activity, QrScanActivity::class.java)) { result ->
                        val text = result.data?.getStringExtra("text")
                        val status = when {
                            result.resultCode == Activity.RESULT_OK && text != null -> "scanned"
                            result.data?.getBooleanExtra("failed", false) == true -> "failed"
                            else -> "cancelled"
                        }
                        val payload = SyllooEvents.payload(id, status)
                        if (status == "scanned") payload.put("text", text!!.take(2048))
                        SyllooEvents.send("QrAnswered", payload)
                    }
                }
            }

            return BridgeResponse.success(mapOf("started" to started))
        }
    }
}

/**
 * The scanner screen: the live picture with a square to aim at, and Cancel. The first code read ends it.
 */
class QrScanActivity : ComponentActivity() {
    private val analysisThread = Executors.newSingleThreadExecutor()
    private val found = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(AimView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val cancel = Button(this).apply {
            text = getString(android.R.string.cancel)
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        val cancelParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        root.addView(cancel, cancelParams)

        // Keep Cancel clear of the navigation bar (Android 15 and newer draw the screen edge to edge).
        val space = (32 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            cancelParams.bottomMargin = bars.bottom + space
            cancel.layoutParams = cancelParams
            insets
        }

        setContentView(root)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisThread) { image ->
                    try {
                        if (!found.get()) {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            val text = QrDecoder.decode(bytes, plane.rowStride, image.width, image.height)
                            if (text != null && found.compareAndSet(false, true)) {
                                runOnUiThread {
                                    setResult(Activity.RESULT_OK, Intent().putExtra("text", text))
                                    finish()
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
                setResult(Activity.RESULT_CANCELED, Intent().putExtra("failed", true))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisThread.shutdown()
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
            val side = minOf(width, height) * 0.7f
            val box = RectF((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
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
