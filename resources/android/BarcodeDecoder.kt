package com.rishadblack.plugins.sylloo_app_native

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a product barcode or a QR code from a camera frame's brightness plane (pure, JVM-tested). ZXing core
 * (Apache 2.0, offline, no Google services), the same library as [QrDecoder].
 *
 * [decode] is the slow, thorough reading of a whole frame (both ways round, light on dark too). The camera screen
 * uses [BarcodeReader], which reads only the aim box, the way the person sees it, many times a second.
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

    internal val THOROUGH_HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to FORMATS.keys.toList(),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** No TRY_HARDER: a 1D reader looks at 15 rows around the middle instead of every row. */
    internal val QUICK_HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to FORMATS.keys.toList(),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * The whole frame, thorough: as it is, then turned a quarter, each also light on dark.
     *
     * @param luminance one byte per pixel, `rowStride` bytes per row (the camera's Y plane)
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): Read? {
        val reader = MultiFormatReader().apply { setHints(THOROUGH_HINTS) }
        read(reader, luminance, rowStride, width, height, invert = true)?.let { return it }
        val turned = turn(luminance, rowStride, width, height)
        return read(reader, turned, height, height, width, invert = true)
    }

    /** One try of [reader] on a packed or strided plane; with [invert] also light bars on a dark ground. */
    internal fun read(reader: MultiFormatReader, luminance: ByteArray, rowStride: Int, width: Int, height: Int, invert: Boolean = false, inverseOnly: Boolean = false): Read? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        val sources = when {
            inverseOnly -> listOf(source.invert())
            invert -> listOf(source, source.invert())
            else -> listOf(source)
        }

        for (one in sources) {
            try {
                val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(one)))
                val format = FORMATS[result.barcodeFormat] ?: continue
                return Read(result.text, format)
            } catch (e: Exception) {
                // NotFound / Checksum / Format: nothing here.
            } finally {
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

/** A rectangle in whole pixels. */
data class PixelBox(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Where the aim box sits on a screen of `width` × `height` (the camera screen draws it, the reader reads it), and the
 * reading window around it: a little larger, so a code that is not quite in the box still reads.
 */
object AimBox {
    const val WIDTH_SHARE = 0.85f
    const val MAX_HEIGHT_SHARE = 0.3f
    const val HEIGHT_TO_WIDTH = 0.6f
    const val WINDOW_GROW_WIDTH = 1.12f
    const val WINDOW_GROW_HEIGHT = 1.5f

    fun box(width: Int, height: Int): PixelBox {
        val w = (width * WIDTH_SHARE).toInt()
        val h = minOf(height * MAX_HEIGHT_SHARE, w * HEIGHT_TO_WIDTH).toInt()
        return PixelBox((width - w) / 2, (height - h) / 2, w, h)
    }

    fun window(width: Int, height: Int): PixelBox {
        val box = box(width, height)
        val w = minOf(width, (box.width * WINDOW_GROW_WIDTH).toInt())
        val h = minOf(height, (box.height * WINDOW_GROW_HEIGHT).toInt())
        return PixelBox((width - w) / 2, (height - h) / 2, w, h)
    }
}

/**
 * The camera screen's reader, one per analysis thread (it keeps its buffers). Each frame:
 *
 * 1. the reading window ([AimBox.window]) of the visible part of the frame, cut out and turned upright as the person
 *    sees it, read quickly (no TRY_HARDER): this is where the code is when the person aims;
 * 2. one more quick try that changes from frame to frame: the window turned a quarter (a code standing up), the
 *    window light on dark, and the whole visible frame (a code outside the box).
 *
 * So no frame costs more than two quick tries, and every way of holding a code is tried within three frames.
 */
class BarcodeReader {
    private val quick = MultiFormatReader().apply { setHints(BarcodeDecoder.QUICK_HINTS) }
    private var window = ByteArray(0)
    private var crossway = ByteArray(0)
    private var whole = ByteArray(0)
    private var frames = 0L

    /** Which extra try the last [read] made (0 window turned, 1 window inverted, 2 whole visible frame); for tests. */
    var lastExtra = -1
        private set

    /**
     * @param luminance the camera's Y plane, `rowStride` bytes per row, `width` × `height` as the sensor gives it
     * @param rotation degrees the frame must turn clockwise to stand as on the screen (ImageInfo.rotationDegrees)
     * @param visible the part of the frame the screen shows (ImageProxy.cropRect with a view port), in frame pixels;
     *   null: the whole frame
     */
    fun read(luminance: ByteArray, rowStride: Int, width: Int, height: Int, rotation: Int, visible: PixelBox? = null): BarcodeDecoder.Read? {
        val shown = visible ?: PixelBox(0, 0, width, height)
        val quarter = rotation == 90 || rotation == 270
        val uprightWidth = if (quarter) shown.height else shown.width
        val uprightHeight = if (quarter) shown.width else shown.height
        val aim = AimBox.window(uprightWidth, uprightHeight)

        window = upright(luminance, rowStride, shown, rotation, aim, window)
        BarcodeDecoder.read(quick, window, aim.width, aim.width, aim.height)?.let { lastExtra = -1; return it }

        val extra = (frames++ % 3).toInt()
        lastExtra = extra
        return when (extra) {
            0 -> {
                crossway = turnInto(window, aim.width, aim.height, crossway)
                BarcodeDecoder.read(quick, crossway, aim.height, aim.height, aim.width)
            }
            1 -> BarcodeDecoder.read(quick, window, aim.width, aim.width, aim.height, inverseOnly = true)
            else -> {
                val all = PixelBox(0, 0, uprightWidth, uprightHeight)
                whole = upright(luminance, rowStride, shown, rotation, all, whole)
                BarcodeDecoder.read(quick, whole, uprightWidth, uprightWidth, uprightHeight)
            }
        }
    }

    companion object {
        /**
         * Cuts [box] (in upright, on-screen coordinates of the visible part [shown]) out of the sensor's frame and
         * turns it upright, packed rows, into [into] (grown when too small).
         */
        fun upright(luminance: ByteArray, rowStride: Int, shown: PixelBox, rotation: Int, box: PixelBox, into: ByteArray): ByteArray {
            val out = if (into.size >= box.width * box.height) into else ByteArray(box.width * box.height)
            var i = 0
            for (uy in box.top until box.top + box.height) {
                for (ux in box.left until box.left + box.width) {
                    // (ux, uy) upright → (sx, sy) in the visible part as the sensor gives it.
                    val sx: Int
                    val sy: Int
                    when (rotation) {
                        90 -> { sx = uy; sy = shown.height - 1 - ux }
                        180 -> { sx = shown.width - 1 - ux; sy = shown.height - 1 - uy }
                        270 -> { sx = shown.width - 1 - uy; sy = ux }
                        else -> { sx = ux; sy = uy }
                    }
                    out[i++] = luminance[(shown.top + sy) * rowStride + shown.left + sx]
                }
            }
            return out
        }

        /** A packed `width` × `height` plane turned a quarter clockwise into [into]. */
        fun turnInto(plane: ByteArray, width: Int, height: Int, into: ByteArray): ByteArray {
            val out = if (into.size >= width * height) into else ByteArray(width * height)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = plane[row + x]
                }
            }
            return out
        }
    }
}

/**
 * "Many in a row": one item under the camera counts once, however long it is held there. The same code counts again
 * only after it has been out of sight (not read) for [gapMs]: take the item away and show it again, as a shop does to
 * sell two. A different code counts at once. Pure, JVM-tested.
 */
class ScanRepeatGuard(private val gapMs: Long = 800) {
    private val lastSeen = HashMap<String, Long>()

    /** True when this read counts. Every read, counted or not, keeps its code "in sight". */
    fun accept(code: String, nowMs: Long): Boolean {
        val seen = lastSeen[code]
        lastSeen[code] = nowMs
        if (lastSeen.size > 32) lastSeen.entries.removeAll { nowMs - it.value >= gapMs }
        return seen == null || nowMs - seen >= gapMs
    }
}

/**
 * How quick the camera screen was, for the log when it closes: the time from opening to the first frame and to the
 * first read, and the read time per frame (middle, 90 %, slowest). Pure, JVM-tested.
 */
class ScanStats(private val keep: Int = 512) {
    private val times = LongArray(keep)
    var frames = 0
        private set
    var firstFrameMs = -1L
    var firstReadMs = -1L

    fun add(ms: Long) {
        times[frames % keep] = ms
        frames++
    }

    /** The [share] (0–1) percentile of the kept frame times; -1 with no frames. */
    fun percentile(share: Double): Long {
        val n = minOf(frames, keep)
        if (n == 0) return -1
        val sorted = times.copyOf(n).also { it.sort() }
        return sorted[minOf(n - 1, (share * (n - 1) + 0.5).toInt())]
    }

    fun summary(reads: Int): String =
        "frames=$frames reads=$reads first_frame_ms=$firstFrameMs first_read_ms=$firstReadMs " +
            "frame_ms_p50=${percentile(0.5)} p90=${percentile(0.9)} max=${percentile(1.0)}"
}

/** Where the camera screen keeps a choice: the app's SharedPreferences on the phone, a map in the tests. */
interface ScanSettingStore {
    fun get(key: String): Boolean?

    fun put(key: String, value: Boolean)
}

/**
 * Continuous scan on or off (owner 2026-11-18). On: the screen stays open for many codes in a row. Off: it closes after
 * the first code it counts and sends only that one. The choice the person makes on the screen is kept on the phone and
 * wins next time; the app's `continuous` is only the first-time default. With [switchable] false the app decides
 * alone: no switch is shown and nothing is read or kept. Pure, JVM-tested.
 */
class ScanMode(private val store: ScanSettingStore, appDefault: Boolean, val switchable: Boolean = true) {
    @Volatile
    var continuous: Boolean = if (switchable) store.get(KEY) ?: appDefault else appDefault
        private set

    /** The person turned the switch. */
    fun change(on: Boolean) {
        if (!switchable) return
        continuous = on
        store.put(KEY, on)
    }

    /** After a counted read: close the screen now (one code only)? */
    fun closesAfterRead(): Boolean = !continuous

    companion object {
        const val PREFS = "sylloo_app_native_scan"
        const val KEY = "camera_continuous"
    }
}

/**
 * The words of the camera screen's own controls (pure, JVM-tested): the switch's label, its On / Off state as TalkBack
 * reads it, and the close button ("Done"). Each comes from PHP in the app's language; a blank or missing one falls back
 * to English, and a very long one is cut so the dark pill stays one line.
 */
object ScanLabels {
    const val CONTINUOUS = "Continuous scan"
    const val DONE = "Done"
    const val ON = "On"
    const val OFF = "Off"
    const val MAX = 40

    fun text(given: String?, fallback: String): String = given?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX) ?: fallback

    /** What TalkBack says after the label: the state in the app's language. */
    fun state(checked: Boolean, on: String?, off: String?): String = if (checked) text(on, ON) else text(off, OFF)
}

/**
 * One line the app sends back to the open camera screen after a code (`Scan.Answer`, remote-scanner.md §5): "Added:
 * Rice 5 kg × 2" in green, "No product with code …" in red. Pure, JVM-tested: the kind decides the colour, the text is
 * one trimmed line of at most [MAX] letters, and it shows for [SHOW_MS]. An answer for another screen (`id`) is dropped.
 */
object ScanAnswer {
    const val OK = "ok"
    const val WARN = "warn"
    const val ERROR = "error"
    const val MAX = 80
    const val SHOW_MS = 2000L

    /** ARGB colours of the pill: green, amber, red. */
    const val GREEN = 0xE02E7D32.toInt()
    const val AMBER = 0xE0B26A00.toInt()
    const val RED = 0xE0C62828.toInt()

    fun kind(value: String?): String = when (value) { OK, WARN, ERROR -> value; else -> OK }

    fun color(kind: String?): Int = when (kind(kind)) { ERROR -> RED; WARN -> AMBER; else -> GREEN }

    /** One line: newlines and runs of spaces become one space; null when nothing is left. */
    fun text(value: String?): String? {
        val line = value?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (line.isEmpty()) return null
        return if (line.length <= MAX) line else line.take(MAX - 1).trimEnd() + "…"
    }

    /** Whether an answer belongs to the open screen: no id on either side, or the same id. */
    fun forScreen(answerId: String?, screenId: String?): Boolean = answerId.isNullOrEmpty() || screenId.isNullOrEmpty() || answerId == screenId
}
