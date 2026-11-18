package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.Camera as CameraHandle
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scan.Camera (till plan §3.4, camera scanning), off unless the app turns `features.scan_camera` on: the app's own
 * barcode screen (CameraX + ZXing). Each code read goes to PHP at once as the event `ScanReceived` (`code`, `source`
 * `camera`, `format`, `id`, `continuous`). Continuous scan on: the screen stays open (a beep, a buzz and a green flash
 * per read; the same code counts again only after it was out of sight for 0.8 s; a Done button); off: the first read
 * closes it. The person turns it on or off on the screen and the phone keeps that choice ([ScanMode]); the app's
 * `continuous` is the first-time default, and with `switchable` false the app alone decides. The switch's text comes
 * from PHP (`continuous_label`, in the app's language). When it closes, `ScanCameraClosed` (`status` done | cancelled
 * | denied | failed, `id`, `count`, `continuous`).
 */
object BarcodeFunctions {

    /**
     * Scan.Answer `{id, text, kind: ok|warn|error}`: one line under the aim box of the open camera screen for 2 s (the
     * app's answer to the code it just got). `shown`, `not_open` (no camera screen, or one of another id), or
     * `not_set_up` (feature off).
     */
    class Answer(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SyllooFeatures.on(context, SyllooFeatures.SCAN_CAMERA)) return BridgeResponse.success(mapOf("status" to "not_set_up"))
            val text = ScanAnswer.text(parameters["text"] as? String)
                ?: return BridgeResponse.error("INVALID_PARAMETERS", "No text")
            val shown = BarcodeScanActivity.answer(parameters["id"] as? String, text, ScanAnswer.kind(parameters["kind"] as? String))
            return BridgeResponse.success(mapOf("status" to if (shown) "shown" else "not_open"))
        }
    }

    class Camera(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SyllooFeatures.on(context, SyllooFeatures.SCAN_CAMERA)) return BridgeResponse.success(mapOf("status" to "not_set_up"))

            val id = parameters["id"] as? String
            val continuous = parameters["continuous"] as? Boolean ?: true
            val switchable = parameters["switchable"] as? Boolean ?: true
            val label = parameters["continuous_label"] as? String
            val onLabel = parameters["on_label"] as? String
            val offLabel = parameters["off_label"] as? String
            val doneLabel = parameters["done_label"] as? String

            val started = SyllooCoordinator.run { coordinator, activity ->
                // Start the camera service now, while Android asks for the permission or opens the screen.
                ProcessCameraProvider.getInstance(activity)
                coordinator.withPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                    if (!granted) {
                        closed(id, "denied", 0)
                        return@withPermissions
                    }

                    val intent = Intent(activity, BarcodeScanActivity::class.java)
                        .putExtra("id", id)
                        .putExtra("continuous", continuous)
                        .putExtra("switchable", switchable)
                        .putExtra("continuous_label", label)
                        .putExtra("on_label", onLabel)
                        .putExtra("off_label", offLabel)
                        .putExtra("done_label", doneLabel)

                    coordinator.startForResult(intent) { result ->
                        val count = result.data?.getIntExtra("count", 0) ?: 0
                        val status = when {
                            result.data?.getBooleanExtra("failed", false) == true -> "failed"
                            count > 0 -> "done"
                            else -> "cancelled"
                        }
                        val mode = result.data?.takeIf { it.hasExtra("continuous") }?.getBooleanExtra("continuous", continuous)
                        closed(id, status, count, mode)
                    }
                }
            }

            return BridgeResponse.success(mapOf("status" to if (started) "started" else "failed"))
        }

        private fun closed(id: String?, status: String, count: Int, continuous: Boolean? = null) {
            SyllooEvents.send(
                "ScanCameraClosed",
                SyllooEvents.payload(id, status).put("count", count).apply { if (continuous != null) put("continuous", continuous) },
            )
        }
    }
}

/**
 * The barcode screen: the live picture with a wide box to aim at (a moving line while it looks, a green flash on each
 * read), the last code read, a torch button when the phone has a light, the "Continuous scan" switch next to it (both
 * low on the screen, under the thumb), and Done (in the app's language). A tap
 * on the picture focuses there. It opens at a modest zoom ([ZOOM]), so a small code reads from a distance the camera
 * can focus at.
 *
 * Only the aim box is read ([BarcodeReader]), on one thread, always the newest frame (older ones are dropped while a
 * frame is read). The times are written to the log under `SyllooScan` when the screen closes.
 */
class BarcodeScanActivity : ComponentActivity() {
    private val analysisThread = Executors.newSingleThreadExecutor()
    private val closing = AtomicBoolean(false)

    /** Set when no more codes may be counted: the screen is closing, or it read its one code with continuous off. */
    private val stopReading = AtomicBoolean(false)
    private lateinit var mode: ScanMode
    private val count = AtomicInteger(0)
    private val guard = ScanRepeatGuard()
    private val reader = BarcodeReader()
    private val stats = ScanStats()
    private var frame = ByteArray(0)
    private var tone: ToneGenerator? = null
    private var camera: CameraHandle? = null
    private var torchOn = false
    private var openedAt = 0L
    private var screenId: String? = null
    private var answerView: TextView? = null
    private val hideAnswer = Runnable { answerView?.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAt = SystemClock.elapsedRealtime()
        quickTransitions()

        val id = intent.getStringExtra("id")
        screenId = id
        mode = ScanMode(
            PreferenceStore(getSharedPreferences(ScanMode.PREFS, MODE_PRIVATE)),
            appDefault = intent.getBooleanExtra("continuous", true),
            switchable = intent.getBooleanExtra("switchable", true),
        )
        tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (e: Exception) { null }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val aim = AimView(this)
        root.addView(aim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val last = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val lastParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        root.addView(last, lastParams)

        // The app's answer to the last code (Scan.Answer): a coloured pill just under the aim box.
        val answer = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 2
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        answerView = answer
        val answerParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        root.addView(answer, answerParams)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val box = AimBox.box(root.width, root.height)
            val top = box.top + box.height + (12 * resources.displayMetrics.density).toInt()
            if (answerParams.topMargin != top) {
                answerParams.topMargin = top
                answerParams.width = box.width
                answer.layoutParams = answerParams
            }
        }

        val density = resources.displayMetrics.density
        val torch = TorchView(this).apply {
            visibility = View.GONE
            setOnClickListener {
                torchOn = !torchOn
                on = torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            }
        }
        // The controls are drawn here (the activity's plain theme draws no switch track): a dark pill each, white text.
        val done = TextView(this).apply {
            text = ScanLabels.text(intent.getStringExtra("done_label"), ScanLabels.DONE)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            val pad = (22 * density).toInt()
            setPadding(pad, 0, pad, 0)
            minHeight = (48 * density).toInt()
            minWidth = (120 * density).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Color.argb(160, 0, 0, 0))
                setStroke((1 * density).toInt(), Color.argb(90, 255, 255, 255))
            }
            isClickable = true
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
            setOnClickListener { close() }
        }
        val continuousSwitch = ScanToggleView(
            this,
            ScanLabels.text(intent.getStringExtra("continuous_label"), ScanLabels.CONTINUOUS),
            intent.getStringExtra("on_label"),
            intent.getStringExtra("off_label"),
        ).apply {
            checked = mode.continuous
            visibility = if (mode.switchable) View.VISIBLE else View.GONE
            onChange = { on -> mode.change(on) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val size = (52 * density).toInt()
            addView(torch, LinearLayout.LayoutParams(size, size).apply { marginEnd = (16 * density).toInt() })
            addView(continuousSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (16 * density).toInt() })
            addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val barParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        root.addView(bar, barParams)

        // Keep the last code and the buttons clear of the status and navigation bars (the screen is drawn edge to edge).
        val space = (32 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            lastParams.topMargin = bars.top + space
            barParams.bottomMargin = bars.bottom + space
            last.layoutParams = lastParams
            bar.layoutParams = barParams
            insets
        }

        setContentView(root)
        open = java.lang.ref.WeakReference(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        // A tap on the picture focuses there (and measures the light there).
        preview.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAt(preview, event.x, event.y)
                view.performClick()
            }
            true
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            // After the first layout, so the preview knows its size and the frames can be cut to what it shows.
            preview.post {
                try {
                    bind(providerFuture.get(), preview, aim, last, done, torch, id)
                } catch (e: Exception) {
                    Log.w(TAG, "The camera did not start: ${e.message}")
                    if (closing.compareAndSet(false, true)) {
                        stopReading.set(true)
                        setResult(Activity.RESULT_CANCELED, Intent().putExtra("failed", true).putExtra("count", count.get()).putExtra("continuous", mode.continuous))
                        finish()
                    }
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind(provider: ProcessCameraProvider, preview: PreviewView, aim: AimView, last: TextView, done: TextView, torch: TorchView, id: String?) {
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
                if (!stopReading.get()) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val size = buffer.remaining()
                    if (frame.size < size) frame = ByteArray(size)
                    buffer.get(frame, 0, size)
                    val crop = image.cropRect
                    val started = SystemClock.elapsedRealtime()
                    if (stats.frames == 0) stats.firstFrameMs = started - openedAt
                    val read = reader.read(frame, plane.rowStride, image.width, image.height, image.imageInfo.rotationDegrees, PixelBox(crop.left, crop.top, crop.width(), crop.height()))
                    stats.add(SystemClock.elapsedRealtime() - started)
                    val now = SystemClock.elapsedRealtime()
                    if (read != null && guard.accept(read.text, now)) {
                        // Continuous off: this is the one code; no later frame may count another before the screen goes.
                        val onlyOne = mode.closesAfterRead()
                        if (onlyOne) stopReading.set(true)
                        val text = read.text.take(256)
                        val n = count.incrementAndGet()
                        if (n == 1) stats.firstReadMs = now - openedAt
                        SyllooEvents.send(
                            "ScanReceived",
                            JSONObject().put("code", text).put("source", "camera").put("format", read.format).put("continuous", !onlyOne)
                                .apply { if (id != null) put("id", id) },
                        )
                        runOnUiThread {
                            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                            aim.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                            aim.flash()
                            last.text = "$text  ($n)"
                            if (onlyOne) close()
                        }
                    }
                }
            } finally {
                image.close()
            }
        }

        // One view port for both: the frames' crop is what the preview shows, so the aim box is the read box.
        val group = UseCaseGroup.Builder().addUseCase(previewUseCase).addUseCase(analysis)
        preview.viewPort?.let { group.setViewPort(it) }

        provider.unbindAll()
        val bound = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
        camera = bound

        bound.cameraInfo.zoomState.value?.let { zoom ->
            bound.cameraControl.setZoomRatio(ZOOM.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
        }
        if (bound.cameraInfo.hasFlashUnit()) torch.visibility = View.VISIBLE
        focusAt(preview, preview.width / 2f, preview.height / 2f)
    }

    private fun focusAt(preview: PreviewView, x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        try {
            control.startFocusAndMetering(action)
        } catch (e: Exception) {
            // A camera without focus areas keeps its own continuous focus.
        }
    }

    private fun close() {
        if (!closing.compareAndSet(false, true)) return
        stopReading.set(true)
        if (torchOn) camera?.cameraControl?.enableTorch(false)
        Log.i(TAG, stats.summary(count.get()))
        setResult(
            if (count.get() > 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Intent().putExtra("count", count.get()).putExtra("continuous", mode.continuous),
        )
        finish()
        if (Build.VERSION.SDK_INT < 34) fadeNow()
    }

    /** A short fade instead of the phone's slide, so the screen opens and closes at once. */
    private fun quickTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            fadeNow()
        }
    }

    @Suppress("DEPRECATION")
    private fun fadeNow() = overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)

    override fun onDestroy() {
        super.onDestroy()
        if (open?.get() === this) open = null
        answerView?.removeCallbacks(hideAnswer)
        analysisThread.shutdown()
        tone?.release()
        tone = null
    }

    companion object {
        const val TAG = "SyllooScan"

        /** The zoom the screen opens at: small codes read from about 10–15 cm, where the camera focuses. */
        const val ZOOM = 1.5f

        const val FLASH_MS = 250L
        const val SWEEP_MS = 1600L

        @Volatile
        private var open: java.lang.ref.WeakReference<BarcodeScanActivity>? = null

        /** Show the app's line on the open screen; false when no screen of that id is open. */
        fun answer(id: String?, text: String, kind: String): Boolean {
            val screen = open?.get() ?: return false
            if (screen.isFinishing || !ScanAnswer.forScreen(id, screen.screenId)) return false
            screen.runOnUiThread { screen.showAnswer(text, kind) }
            return true
        }
    }

    private fun showAnswer(text: String, kind: String) {
        val view = answerView ?: return
        val density = resources.displayMetrics.density
        view.text = text
        val pad = (14 * density).toInt()
        view.setPadding(pad, pad / 2, pad, pad / 2)
        view.background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(ScanAnswer.color(kind))
        }
        view.visibility = View.VISIBLE
        view.removeCallbacks(hideAnswer)
        view.postDelayed(hideAnswer, ScanAnswer.SHOW_MS)
    }

    /** The dimmed picture around [AimBox.box], its frame, a line sweeping up and down, and a green flash per read. */
    private class AimView(context: Context) : View(context) {
        private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f
            color = Color.argb(220, 244, 144, 49)
        }
        private val glow = Paint().apply { color = Color.argb(90, 46, 204, 113) }
        private var flashedAt = 0L

        fun flash() {
            flashedAt = SystemClock.uptimeMillis()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = AimBox.box(width, height)
            val box = RectF(b.left.toFloat(), b.top.toFloat(), (b.left + b.width).toFloat(), (b.top + b.height).toFloat())
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRoundRect(box, 24f, 24f, Path.Direction.CW)
            }
            canvas.drawPath(path, dim)

            val now = SystemClock.uptimeMillis()
            val flashing = now - flashedAt < FLASH_MS
            if (flashing) {
                canvas.drawRoundRect(box, 24f, 24f, glow)
                frame.color = Color.rgb(46, 204, 113)
            } else {
                frame.color = Color.WHITE
                // Up and down once per SWEEP_MS, inside the box.
                val phase = (now % SWEEP_MS).toFloat() / SWEEP_MS
                val along = if (phase < 0.5f) phase * 2 else (1 - phase) * 2
                val y = box.top + 12f + along * (box.height() - 24f)
                canvas.drawLine(box.left + 16f, y, box.right - 16f, y, sweep)
            }
            canvas.drawRoundRect(box, 24f, 24f, frame)
            postInvalidateOnAnimation()
        }
    }

    /** [ScanMode]'s choice in the app's own SharedPreferences (private to the app, kept until its data is cleared). */
    private class PreferenceStore(private val prefs: SharedPreferences) : ScanSettingStore {
        override fun get(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, true) else null

        override fun put(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    /**
     * The "Continuous scan" switch, drawn by itself so it shows on any theme: the label, then a track (teal when on,
     * grey when off) with a white thumb, on a dark pill. TalkBack reads it as a switch with its On / Off state.
     */
    private class ScanToggleView(context: Context, private val label: String, private val onText: String?, private val offText: String?) : View(context) {
        var checked = false
            set(value) {
                field = value
                stateDescription()
                invalidate()
            }
        var onChange: ((Boolean) -> Unit)? = null

        private val density = context.resources.displayMetrics.density
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16 * density * context.resources.configuration.fontScale }
        private val pad = 14 * density
        private val gap = 12 * density
        private val trackW = 44 * density
        private val trackH = 24 * density

        init {
            isClickable = true
            isFocusable = true
            contentDescription = label
            stateDescription()
            setOnClickListener {
                checked = !checked
                onChange?.invoke(checked)
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = checked
                }
            }
        }

        private fun stateDescription() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stateDescription = ScanLabels.state(checked, onText, offText)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (pad + words.measureText(label) + gap + trackW + pad).toInt()
            val h = (48 * density).toInt()
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, pill)

            val baseline = h / 2 - (words.descent() + words.ascent()) / 2
            canvas.drawText(label, pad, baseline, words)

            val left = width - pad - trackW
            val top = (h - trackH) / 2
            track.color = if (checked) Color.rgb(0, 150, 136) else Color.rgb(120, 120, 120)
            canvas.drawRoundRect(RectF(left, top, left + trackW, top + trackH), trackH / 2, trackH / 2, track)

            val r = trackH / 2 - 3 * density
            val cx = if (checked) left + trackW - trackH / 2 else left + trackH / 2
            canvas.drawCircle(cx, h / 2, r, thumb)
        }
    }

    /** A round torch button with a lightning mark, filled while the light is on. */
    private class TorchView(context: Context) : View(context) {
        var on = false
            set(value) {
                field = value
                invalidate()
            }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        init {
            isClickable = true
            contentDescription = "Torch"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = minOf(width, height) / 2f
            ring.color = if (on) Color.WHITE else Color.argb(110, 0, 0, 0)
            canvas.drawCircle(width / 2f, height / 2f, r - 2f, ring)
            mark.color = if (on) Color.BLACK else Color.WHITE
            val s = r / 12f
            val cx = width / 2f
            val cy = height / 2f
            val bolt = Path().apply {
                moveTo(cx + 1 * s, cy - 8 * s)
                lineTo(cx - 5 * s, cy + 1 * s)
                lineTo(cx - 0.5f * s, cy + 1 * s)
                lineTo(cx - 1.5f * s, cy + 8 * s)
                lineTo(cx + 5 * s, cy - 1.5f * s)
                lineTo(cx + 0.5f * s, cy - 1.5f * s)
                close()
            }
            canvas.drawPath(bolt, mark)
        }
    }
}
