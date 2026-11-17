package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File
import java.util.UUID

/**
 * How big a photo is kept (pure, JVM-tested): the long side at most `maxSide`, never made bigger.
 */
object PhotoSize {
    const val MIN_SIDE = 320
    const val MAX_SIDE = 2048

    fun maxSide(requested: Int?): Int = (requested ?: 1280).coerceIn(MIN_SIDE, MAX_SIDE)

    fun quality(requested: Int?): Int = (requested ?: 80).coerceIn(40, 95)

    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxSide || long <= 0) return Pair(width, height)
        val scale = maxSide.toDouble() / long
        return Pair(maxOf(1, Math.round(width * scale).toInt()), maxOf(1, Math.round(height * scale).toInt()))
    }
}

/**
 * Camera.Take (plan §4.6, §7.4, §7.5): the app's own camera screen, never the gallery. `lens` `front` (a selfie, with
 * the face outline) or `back` (a photo: a receipt, a visit). The photo is made small here in Kotlin (the phone's PHP has
 * no gd): the long side at most `max_side` px (default 1280), JPEG `quality` (default 80), kept in the app's no-backup
 * folder `sylloo-photos/`. The answer is the event `CameraAnswered`: `status` `taken` (with `path`, `width`, `height`,
 * `bytes`, `lens`) | `cancelled` | `denied` | `failed`.
 */
object PhotoFunctions {

    class Take(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            val lens = if (parameters["lens"] == "back") "back" else "front"
            val outline = (parameters["outline"] as? Boolean) ?: (lens == "front")
            val maxSide = PhotoSize.maxSide((parameters["max_side"] as? Number)?.toInt())
            val quality = PhotoSize.quality((parameters["quality"] as? Number)?.toInt())

            val started = SyllooCoordinator.run { coordinator, activity ->
                coordinator.withPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                    if (!granted) {
                        SyllooEvents.send("CameraAnswered", SyllooEvents.payload(id, "denied"))
                        return@withPermissions
                    }

                    val intent = Intent(activity, PhotoActivity::class.java)
                        .putExtra("lens", lens)
                        .putExtra("outline", outline)
                        .putExtra("max_side", maxSide)
                        .putExtra("quality", quality)

                    coordinator.startForResult(intent) { result ->
                        val data = result.data
                        val payload = SyllooEvents.payload(id, when {
                            result.resultCode == Activity.RESULT_OK && data?.getStringExtra("path") != null -> "taken"
                            data?.getBooleanExtra("failed", false) == true -> "failed"
                            else -> "cancelled"
                        })
                        if (payload.getString("status") == "taken" && data != null) {
                            payload.put("path", data.getStringExtra("path"))
                            payload.put("width", data.getIntExtra("width", 0))
                            payload.put("height", data.getIntExtra("height", 0))
                            payload.put("bytes", data.getLongExtra("bytes", 0L))
                            payload.put("lens", lens)
                        }
                        SyllooEvents.send("CameraAnswered", payload)
                    }
                }
            }

            return BridgeResponse.success(mapOf("started" to started))
        }
    }
}

/**
 * The camera screen of Camera.Take: the live picture, the face outline for a selfie, Take and Cancel. Drawn in code
 * (no layout files), CameraX only (no Google services).
 */
class PhotoActivity : ComponentActivity() {
    private var capture: ImageCapture? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val front = intent.getStringExtra("lens") != "back"
        val outline = intent.getBooleanExtra("outline", front)
        val maxSide = intent.getIntExtra("max_side", 1280)
        val quality = intent.getIntExtra("quality", 80)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (outline) {
            root.addView(FaceOutlineView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 96)
        }
        val cancel = Button(this).apply {
            text = getString(android.R.string.cancel)
            setOnClickListener { finishWith(Activity.RESULT_CANCELED, Intent()) }
        }
        val take = Button(this).apply {
            text = "●"
            textSize = 28f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setTextColor(Color.DKGRAY)
            contentDescription = "Take photo"
            setOnClickListener { takePhoto(maxSide, quality) }
        }
        bar.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 64 })
        bar.addView(take, LinearLayout.LayoutParams(200, 200))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewUseCase, imageCapture)
                capture = imageCapture
            } catch (e: Exception) {
                finishWith(Activity.RESULT_CANCELED, Intent().putExtra("failed", true))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto(maxSide: Int, quality: Int) {
        val imageCapture = capture ?: return
        if (busy) return
        busy = true

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Thread {
                    val result = try {
                        save(image, maxSide, quality)
                    } catch (e: Exception) {
                        null
                    } finally {
                        image.close()
                    }
                    runOnUiThread {
                        if (result == null) finishWith(Activity.RESULT_CANCELED, Intent().putExtra("failed", true))
                        else finishWith(Activity.RESULT_OK, result)
                    }
                }.start()
            }

            override fun onError(exception: ImageCaptureException) {
                finishWith(Activity.RESULT_CANCELED, Intent().putExtra("failed", true))
            }
        })
    }

    private fun save(image: ImageProxy, maxSide: Int, quality: Int): Intent {
        val source = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val (width, height) = if (rotation % 180 == 0) Pair(source.width, source.height) else Pair(source.height, source.width)
        val (targetWidth, targetHeight) = PhotoSize.fit(width, height, maxSide)

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            val scale = targetWidth.toFloat() / width
            postScale(scale, scale)
        }
        val made = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        val folder = File(noBackupFilesDir, "sylloo-photos").apply { mkdirs() }
        val file = File(folder, "photo-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jpg")
        file.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, quality, it) }

        val result = Intent()
            .putExtra("path", file.absolutePath)
            .putExtra("width", made.width)
            .putExtra("height", made.height)
            .putExtra("bytes", file.length())

        if (made !== source) made.recycle()
        source.recycle()

        return result
    }

    private fun finishWith(code: Int, data: Intent) {
        setResult(code, data)
        finish()
    }

    /**
     * The face outline over the live picture: the screen dimmed around an upright oval.
     */
    private class FaceOutlineView(context: Context) : View(context) {
        private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val ovalWidth = w * 0.66f
            val ovalHeight = ovalWidth * 1.3f
            val oval = RectF((w - ovalWidth) / 2, h * 0.42f - ovalHeight / 2, (w + ovalWidth) / 2, h * 0.42f + ovalHeight / 2)

            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, w, h, Path.Direction.CW)
                addOval(oval, Path.Direction.CW)
            }
            canvas.drawPath(path, dim)
            canvas.drawOval(oval, line)
        }
    }
}
