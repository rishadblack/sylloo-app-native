package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.ui.MainActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which of the package's switched jobs this build has (config `sylloo-app-native.features.*`, written by the build
 * hook into `res/values/sylloo_features.xml`). A build without the value has the job off.
 */
object SyllooFeatures {
    const val PRINT = "sylloo_feature_print"
    const val SCAN_KEYS = "sylloo_feature_scan_keys"
    const val SCAN_CAMERA = "sylloo_feature_scan_camera"

    fun on(context: Context, name: String): Boolean {
        val id = context.resources.getIdentifier(name, "bool", context.packageName)
        return id != 0 && try { context.resources.getBoolean(id) } catch (e: Exception) { false }
    }
}

/**
 * Print.Receipt / Print.Printers / Print.Drawer (till plan §7.1, §7.3), off unless the app turns `features.print` on.
 *
 * The receipt page (self-contained HTML: no network, no scripts) is drawn off the screen in a web view at the paper's
 * width, made a 1-bit picture with dithering and sent as ESC/POS raster (`GS v 0`), then fed and cut; or a ready PNG
 * is sent the same way. Bangla, the logo and the barcode print as drawn. The way to the printer is Bluetooth (a paired
 * printer, classic SPP), USB (Android asks the person once per printer) or the network (port 9100).
 *
 * The final answer is the event `PrintAnswered`: `status` `printed` | `opened` (drawer) | `no_printer` |
 * `not_permitted` | `failed` (with `reason`) | `not_set_up`; `job` `receipt` | `drawer` | `permission`. Nothing is
 * thrown across the bridge.
 */
object PrintFunctions {
    private const val TAG = "SyllooPrint"

    class Receipt(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            if (!SyllooFeatures.on(context, SyllooFeatures.PRINT)) return answer("not_set_up", "feature_off")

            val dots = PrintRules.dots(parameters["paper_mm"] as? Number) ?: return answer("failed", "paper")
            val target = PrinterTarget.parse(parameters["target"] as? String) ?: return answer("no_printer", "target")
            val html = parameters["html"] as? String
            val png = parameters["png"] as? String
            val copies = PrintRules.copies(parameters["copies"] as? Number)
            val cut = parameters["cut"] as? Boolean ?: true
            val openDrawer = parameters["open_drawer"] as? Boolean ?: false

            if (html.isNullOrBlank() && png.isNullOrBlank()) return answer("failed", "empty")
            if ((html?.length ?: 0) > PrintRules.MAX_HTML_BYTES || (png?.length ?: 0) > PrintRules.MAX_PNG_BYTES * 4 / 3 + 4) {
                return answer("failed", "too_big")
            }

            Jobs.run(context, id, "receipt", "printed", target) { done ->
                val picture: (Bitmap?) -> Unit = { bitmap ->
                    if (bitmap == null) {
                        done(null)
                    } else {
                        Thread {
                            done(try { encode(bitmap, dots, copies, cut, openDrawer) } catch (e: Throwable) { null })
                        }.start()
                    }
                }

                if (!png.isNullOrBlank()) {
                    picture(try { Base64.decode(png, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (e: Exception) { null })
                } else {
                    Painter.paint(context, html!!, dots, picture)
                }
            }

            return answer("started", null)
        }
    }

    class Drawer(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            if (!SyllooFeatures.on(context, SyllooFeatures.PRINT)) return answer("not_set_up", "feature_off")
            val target = PrinterTarget.parse(parameters["target"] as? String) ?: return answer("no_printer", "target")

            Jobs.run(context, id, "drawer", "opened", target) { done -> done(EscPos.INIT + EscPos.drawer()) }

            return answer("started", null)
        }
    }

    /**
     * The paired Bluetooth devices and the attached USB printers. Without the Bluetooth permission: `not_permitted`
     * with the USB ones; with `ask`, Android's question is shown and the answer comes as `PrintAnswered` (job
     * `permission`, status `permitted` | `not_permitted`); then list again.
     */
    class Printers(private val context: Context) : BridgeFunction {
        @SuppressLint("MissingPermission")
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SyllooFeatures.on(context, SyllooFeatures.PRINT)) return mapOf("status" to "not_set_up", "printers" to emptyList<Any>())

            val printers = mutableListOf<Map<String, Any>>()
            var status = "listed"

            if (!Jobs.bluetoothPermitted(context)) {
                status = "not_permitted"
                if (parameters["ask"] as? Boolean == true) {
                    val asked = SyllooCoordinator.run { coordinator, _ ->
                        coordinator.withPermissions(Jobs.BLUETOOTH) { granted ->
                            SyllooEvents.send("PrintAnswered", SyllooEvents.payload(parameters["id"] as? String, if (granted) "permitted" else "not_permitted").put("job", "permission"))
                        }
                    }
                    if (asked) status = "asking"
                }
            } else {
                try {
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    adapter?.bondedDevices?.forEach { device ->
                        printers += mapOf("kind" to "bluetooth", "name" to (device.name ?: device.address), "target" to "bt:${device.address.uppercase()}")
                    }
                } catch (e: SecurityException) {
                    status = "not_permitted"
                }
            }

            try {
                val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                usb?.deviceList?.values?.filter { Usb.printerInterface(it) != null }?.forEach { device ->
                    val name = try { device.productName } catch (e: Exception) { null } ?: "USB printer"
                    printers += mapOf("kind" to "usb", "name" to name, "target" to PrinterTarget.Usb(device.vendorId, device.productId).text())
                }
            } catch (e: Exception) {
                Log.w(TAG, "USB list failed: ${e.message}")
            }

            return mapOf("status" to status, "printers" to printers)
        }
    }

    private fun answer(status: String, reason: String?): Map<String, Any> =
        if (reason == null) mapOf("status" to status) else mapOf("status" to status, "reason" to reason)

    /** The drawn page to printer bytes: scaled to the paper's dots, grey, dithered, raster, copies, cut, drawer. */
    private fun encode(bitmap: Bitmap, dots: Int, copies: Int, cut: Boolean, openDrawer: Boolean): ByteArray {
        val rows = PrintRules.scaledRows(bitmap.width, bitmap.height, dots)
        val scaled = if (bitmap.width == dots && bitmap.height == rows) bitmap else Bitmap.createScaledBitmap(bitmap, dots, rows, true)
        val pixels = IntArray(dots * rows)
        scaled.getPixels(pixels, 0, dots, 0, 0, dots, rows)
        val black = EscPos.dither(EscPos.luminance(pixels), dots, rows)
        return EscPos.receipt(black, dots, rows, copies, cut, openDrawer)
    }

    /** One job: the permission, the bytes, the way to the printer, the event. */
    private object Jobs {
        val BLUETOOTH: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

        fun bluetoothPermitted(context: Context): Boolean = BLUETOOTH.all { SyllooCoordinator.hasPermission(context, it) }

        fun run(context: Context, id: String?, job: String, success: String, target: PrinterTarget, bytes: ((ByteArray?) -> Unit) -> Unit) {
            val send = { status: String, reason: String? ->
                val payload = SyllooEvents.payload(id, status).put("job", job)
                if (reason != null) payload.put("reason", reason)
                SyllooEvents.send("PrintAnswered", payload)
            }

            val go = {
                bytes { data ->
                    if (data == null) {
                        send("failed", "render")
                    } else {
                        Thread {
                            try {
                                Link.send(context, target, data)
                                send(success, null)
                            } catch (e: Refused) {
                                send(e.status, e.reason)
                            } catch (e: Throwable) {
                                Log.w(TAG, "Print failed: ${e.message}")
                                send("failed", "io")
                            }
                        }.start()
                    }
                }
            }

            if (target !is PrinterTarget.Bluetooth || bluetoothPermitted(context)) {
                Handler(Looper.getMainLooper()).post { go() }
                return
            }

            val asked = SyllooCoordinator.run { coordinator, _ ->
                coordinator.withPermissions(BLUETOOTH) { granted -> if (granted) go() else send("not_permitted", "bluetooth") }
            }
            if (!asked) send("not_permitted", "bluetooth")
        }
    }

    class Refused(val status: String, val reason: String) : Exception(reason)

    /** The three ways to a printer. Blocking: called on a worker thread. */
    private object Link {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun send(context: Context, target: PrinterTarget, data: ByteArray) = when (target) {
            is PrinterTarget.Bluetooth -> bluetooth(context, target.address, data)
            is PrinterTarget.Network -> network(target.host, target.port, data)
            is PrinterTarget.Usb -> Usb.send(context, target, data)
        }

        @SuppressLint("MissingPermission")
        private fun bluetooth(context: Context, address: String, data: ByteArray) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw Refused("no_printer", "no_bluetooth")
            if (!Jobs.bluetoothPermitted(context)) throw Refused("not_permitted", "bluetooth")
            if (!adapter.isEnabled) throw Refused("no_printer", "bluetooth_off")
            val device = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) { throw Refused("no_printer", "target") }
            if (device.bondState != BluetoothDevice.BOND_BONDED) throw Refused("no_printer", "not_paired")

            val socket = try {
                device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
            } catch (e: IOException) {
                try {
                    device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
                } catch (e2: IOException) {
                    throw Refused("no_printer", "not_reachable")
                }
            }

            socket.use {
                val out = it.outputStream
                var at = 0
                while (at < data.size) {
                    val size = minOf(1024, data.size - at)
                    out.write(data, at, size)
                    at += size
                }
                out.flush()
                // Cheap printers lose what is still in their buffer when the link closes at once.
                Thread.sleep(minOf(4_000L, 300L + data.size / 20L))
            }
        }

        private fun network(host: String, port: Int, data: ByteArray) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5_000)
            } catch (e: IOException) {
                socket.close()
                throw Refused("no_printer", "not_reachable")
            }
            socket.use {
                it.soTimeout = 10_000
                it.getOutputStream().apply {
                    write(data)
                    flush()
                }
                it.shutdownOutput()
            }
        }
    }

    /** USB printers: a device with a printer-class interface (7) and a bulk OUT endpoint. */
    private object Usb {
        private const val ACTION = "com.rishadblack.plugins.sylloo_app_native.USB_PERMISSION"

        fun printerInterface(device: UsbDevice): android.hardware.usb.UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { face ->
                face.interfaceClass == UsbConstants.USB_CLASS_PRINTER && (0 until face.endpointCount).any {
                    face.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK && face.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT
                }
            }

        fun send(context: Context, target: PrinterTarget.Usb, data: ByteArray) {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: throw Refused("no_printer", "no_usb")
            val device = manager.deviceList.values.firstOrNull { it.vendorId == target.vendorId && it.productId == target.productId }
                ?: throw Refused("no_printer", "not_connected")
            val face = printerInterface(device) ?: throw Refused("no_printer", "not_a_printer")
            if (!manager.hasPermission(device) && !ask(context, manager, device)) throw Refused("not_permitted", "usb")

            val endpoint = (0 until face.endpointCount).map { face.getEndpoint(it) }
                .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            val connection = manager.openDevice(device) ?: throw Refused("no_printer", "not_reachable")
            try {
                if (!connection.claimInterface(face, true)) throw Refused("failed", "usb_busy")
                var at = 0
                while (at < data.size) {
                    val size = minOf(16 * 1024, data.size - at)
                    val sent = connection.bulkTransfer(endpoint, data, at, size, 5_000)
                    if (sent <= 0) throw Refused("failed", "usb_write")
                    at += sent
                }
                connection.releaseInterface(face)
            } finally {
                connection.close()
            }
        }

        /** Android's own "allow this app to use the USB device?" question; waits up to a minute for the answer. */
        private fun ask(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
            val answered = CountDownLatch(1)
            val granted = AtomicBoolean(false)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == ACTION) {
                        granted.set(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        answered.countDown()
                    }
                }
            }
            val app = context.applicationContext
            ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val intent = PendingIntent.getBroadcast(app, 0, Intent(ACTION).setPackage(app.packageName), flags)
                manager.requestPermission(device, intent)
                answered.await(60, TimeUnit.SECONDS)
            } finally {
                try { app.unregisterReceiver(receiver) } catch (e: Exception) { }
            }
            return granted.get() && manager.hasPermission(device)
        }
    }

    /**
     * Draws the receipt page off the screen: a web view never shown, JavaScript off, no network, no files, laid out at
     * the paper's CSS width (as the PC prints it), then drawn whole into a white picture. Main thread only.
     */
    private object Painter {
        private const val SETTLE_MS = 150L
        private const val TIMEOUT_MS = 15_000L

        fun paint(context: Context, html: String, dots: Int, done: (Bitmap?) -> Unit) {
            val main = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            val finish = { bitmap: Bitmap? -> if (finished.compareAndSet(false, true)) done(bitmap) }

            main.post {
                val web = try {
                    // Draw the whole page, not only the part a screen would show (read when a web view is made).
                    WebView.enableSlowWholeDocumentDraw()
                    WebView(MainActivity.instance ?: context)
                } catch (e: Throwable) {
                    Log.w(TAG, "No web view: ${e.message}")
                    finish(null)
                    return@post
                }

                val density = web.resources.displayMetrics.density
                val widthPx = Math.round(PrintRules.cssWidth(dots) * density)
                val maxHeightPx = (PrintRules.MAX_ROWS.toLong() * widthPx / dots).toInt()

                web.settings.apply {
                    javaScriptEnabled = false
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    textZoom = 100
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    setGeolocationEnabled(false)
                }
                web.setBackgroundColor(Color.WHITE)
                web.isVerticalScrollBarEnabled = false
                web.isHorizontalScrollBarEnabled = false

                val capture = Runnable {
                    try {
                        val heightPx = Math.round(web.contentHeight * density).coerceIn(1, maxHeightPx)
                        layout(web, widthPx, heightPx)
                        main.postDelayed({
                            try {
                                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(Color.WHITE)
                                web.draw(canvas)
                                finish(bitmap)
                            } catch (e: Throwable) {
                                Log.w(TAG, "Drawing failed: ${e.message}")
                                finish(null)
                            } finally {
                                web.destroy()
                            }
                        }, SETTLE_MS)
                    } catch (e: Throwable) {
                        web.destroy()
                        finish(null)
                    }
                }

                web.webViewClient = object : WebViewClient() {
                    private var loaded = false

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (loaded) return
                        loaded = true
                        main.postDelayed(capture, SETTLE_MS)
                    }
                }

                layout(web, widthPx, 1)
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)

                main.postDelayed({
                    if (!finished.get()) {
                        Log.w(TAG, "The receipt page did not finish drawing")
                        finish(null)
                        web.destroy()
                    }
                }, TIMEOUT_MS)
            }
        }

        private fun layout(web: WebView, width: Int, height: Int) {
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            web.layout(0, 0, width, height)
        }
    }
}
