package com.rishadblack.plugins.sylloo_app_native

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera screen's quick reader: it reads only the aim box, upright as the person sees it, from the sensor's frame
 * (which lies on its side when the phone is upright), and still finds a code held across, light on dark, or outside
 * the box within three frames.
 */
class BarcodeReaderTest {
    private val sensorWidth = 1280
    private val sensorHeight = 720

    /**
     * What the person sees (upright, `uw` × `uh`): grey, with the code drawn at (`cx`, `cy`) (centre), level or
     * standing (turned a quarter), dark on light or light on dark.
     */
    private fun upright(text: String, format: BarcodeFormat, uw: Int, uh: Int, codeWidth: Int = 420, codeHeight: Int = 140, standing: Boolean = false, inverted: Boolean = false, cx: Int = uw / 2, cy: Int = uh / 2): ByteArray {
        val m = MultiFormatWriter().encode(text, format, codeWidth, codeHeight, mapOf(EncodeHintType.MARGIN to 0))
        val mw = if (standing) m.height else m.width
        val mh = if (standing) m.width else m.height
        val left = cx - mw / 2
        val top = cy - mh / 2
        val ground = if (inverted) 30 else 220
        val ink = if (inverted) 230 else 30
        val out = ByteArray(uw * uh) { 150.toByte() }
        for (y in top - 24 until top + mh + 24) for (x in left - 32 until left + mw + 32) {
            if (x in 0 until uw && y in 0 until uh) out[y * uw + x] = ground.toByte()
        }
        for (y in 0 until mh) for (x in 0 until mw) {
            val dark = if (standing) m.get(y, m.height - 1 - x) else m.get(x, y)
            if (dark) out[(top + y) * uw + left + x] = ink.toByte()
        }
        return out
    }

    /** The upright picture as a sensor turned by [rotation] hands it over, with [stride] bytes per row. */
    private fun sensor(up: ByteArray, uw: Int, uh: Int, rotation: Int, stride: Int? = null): Triple<ByteArray, Int, Pair<Int, Int>> {
        val quarter = rotation == 90 || rotation == 270
        val sw = if (quarter) uh else uw
        val sh = if (quarter) uw else uh
        val rowStride = stride ?: sw
        val out = ByteArray(rowStride * sh) { 255.toByte() }
        for (uy in 0 until uh) for (ux in 0 until uw) {
            val (sx, sy) = when (rotation) {
                90 -> uy to (sh - 1 - ux)
                180 -> (sw - 1 - ux) to (sh - 1 - uy)
                270 -> (sw - 1 - uy) to ux
                else -> ux to uy
            }
            out[sy * rowStride + sx] = up[uy * uw + ux]
        }
        return Triple(out, rowStride, sw to sh)
    }

    private fun readWithin(frames: Int, plane: ByteArray, stride: Int, width: Int, height: Int, rotation: Int, visible: PixelBox? = null): Pair<BarcodeDecoder.Read?, Int> {
        val reader = BarcodeReader()
        for (n in 1..frames) reader.read(plane, stride, width, height, rotation, visible)?.let { return it to n }
        return null to -1
    }

    @Test
    fun readsALevelCodeInTheBoxAtTheFirstFrameWhateverWayTheSensorLies() {
        for (rotation in listOf(90, 270, 0, 180)) {
            val (uw, uh) = if (rotation == 90 || rotation == 270) sensorHeight to sensorWidth else sensorWidth to sensorHeight
            val (plane, stride, size) = sensor(upright("8901234567890", BarcodeFormat.EAN_13, uw, uh), uw, uh, rotation)
            val (read, frames) = readWithin(1, plane, stride, size.first, size.second, rotation)
            assertEquals("rotation $rotation", BarcodeDecoder.Read("8901234567890", "ean_13"), read)
            assertEquals(1, frames)
        }
    }

    @Test
    fun readsEveryTillFormatInTheBox() {
        val cases = listOf(
            Triple("96385074", BarcodeFormat.EAN_8, "ean_8"),
            Triple("036000291452", BarcodeFormat.UPC_A, "upc_a"),
            Triple("01234565", BarcodeFormat.UPC_E, "upc_e"),
            Triple("SKU-A12-77", BarcodeFormat.CODE_128, "code_128"),
            Triple("TILL42", BarcodeFormat.CODE_39, "code_39"),
        )
        for ((text, format, name) in cases) {
            val (plane, stride, size) = sensor(upright(text, format, 720, 1280, codeWidth = 360, codeHeight = 120), 720, 1280, 90)
            assertEquals(BarcodeDecoder.Read(text, name), readWithin(1, plane, stride, size.first, size.second, 90).first)
        }
        val (qr, stride, size) = sensor(upright("P-1001", BarcodeFormat.QR_CODE, 720, 1280, codeWidth = 200, codeHeight = 200), 720, 1280, 90)
        assertEquals(BarcodeDecoder.Read("P-1001", "qr_code"), readWithin(1, qr, stride, size.first, size.second, 90).first)
    }

    @Test
    fun readsACodeHeldAcrossWithinThreeFrames() {
        val (plane, stride, size) = sensor(upright("8901234567890", BarcodeFormat.EAN_13, 720, 1280, codeWidth = 300, codeHeight = 110, standing = true), 720, 1280, 90)
        val (read, frames) = readWithin(3, plane, stride, size.first, size.second, 90)
        assertEquals("8901234567890", read?.text)
        assertTrue(frames in 1..3)
    }

    @Test
    fun readsLightBarsOnADarkGroundWithinThreeFrames() {
        val (plane, stride, size) = sensor(upright("8901234567890", BarcodeFormat.EAN_13, 720, 1280, inverted = true), 720, 1280, 90)
        assertEquals("8901234567890", readWithin(3, plane, stride, size.first, size.second, 90).first?.text)
    }

    @Test
    fun readsACodeBelowTheBoxWithinThreeFrames() {
        // Just below the aim box (the box ends near 915 of 1280), and a QR code down in a corner.
        val (plane, stride, size) = sensor(upright("SKU-A12-77", BarcodeFormat.CODE_128, 720, 1280, codeWidth = 360, codeHeight = 100, cy = 880), 720, 1280, 90)
        val (qr, qrStride, _) = sensor(upright("P-1001", BarcodeFormat.QR_CODE, 720, 1280, codeWidth = 160, codeHeight = 160, cx = 560, cy = 1120), 720, 1280, 90)
        assertEquals("P-1001", readWithin(3, qr, qrStride, size.first, size.second, 90).first?.text)
        val reader = BarcodeReader()
        assertEquals("SKU-A12-77", readWithin(3, plane, stride, size.first, size.second, 90).first?.text)
        // The box alone does not see it: only the whole-frame try does.
        assertNull(reader.read(plane, stride, size.first, size.second, 90))
    }

    @Test
    fun readsOnlyTheVisiblePartAndKeepsTheRowStride() {
        // The screen shows the middle 1100 of the sensor's 1280 rows (a view port crop), rows padded to 1344 bytes.
        val (plane, stride, size) = sensor(upright("8901234567890", BarcodeFormat.EAN_13, 720, 1280), 720, 1280, 90, stride = 1344)
        val visible = PixelBox(90, 0, 1100, 720)
        assertEquals("8901234567890", readWithin(1, plane, stride, size.first, size.second, 90, visible).first?.text)
    }

    @Test
    fun findsNothingInAnEmptyFrame() {
        val plane = ByteArray(sensorWidth * sensorHeight) { 127 }
        assertEquals(null to -1, readWithin(6, plane, sensorWidth, sensorWidth, sensorHeight, 90))
    }

    @Test
    fun turnsTheVisiblePartUprightAsTheScreenShowsIt() {
        // Sensor 3 wide, 2 high: a b c / d e f. Turned 90 clockwise: d a / e b / f c.
        val plane = byteArrayOf(1, 2, 3, 4, 5, 6)
        val all = PixelBox(0, 0, 3, 2)
        assertEquals(listOf<Byte>(4, 1, 5, 2, 6, 3), BarcodeReader.upright(plane, 3, all, 90, PixelBox(0, 0, 2, 3), ByteArray(0)).toList())
        assertEquals(listOf<Byte>(3, 6, 2, 5, 1, 4), BarcodeReader.upright(plane, 3, all, 270, PixelBox(0, 0, 2, 3), ByteArray(0)).toList())
        assertEquals(listOf<Byte>(6, 5, 4, 3, 2, 1), BarcodeReader.upright(plane, 3, all, 180, PixelBox(0, 0, 3, 2), ByteArray(0)).toList())
        // A window of the upright picture: its middle row only.
        assertEquals(listOf<Byte>(5, 2), BarcodeReader.upright(plane, 3, all, 90, PixelBox(0, 1, 2, 1), ByteArray(0)).toList())
    }

    @Test
    fun drawsTheAimBoxWideAndReadsALittleMoreAroundIt() {
        val box = AimBox.box(1080, 2340)
        assertEquals(PixelBox(81, 895, 918, 550), box)
        val window = AimBox.window(1080, 2340)
        assertTrue(window.width > box.width && window.width <= 1080)
        assertTrue(window.height > box.height)
        assertEquals(1080 - window.width, window.left * 2)
        // Landscape (a tablet): never taller than 30 % of the screen.
        assertEquals(240, AimBox.box(1920, 800).height)
    }

    @Test
    fun keepsTheTimesForTheLog() {
        val stats = ScanStats(keep = 4)
        assertEquals(-1, stats.percentile(0.5))
        for (ms in listOf(9L, 3L, 5L, 7L, 1L)) stats.add(ms) // the first (9) falls out: 1 3 5 7
        assertEquals(5, stats.frames)
        assertEquals(5, stats.percentile(0.5))
        assertEquals(7, stats.percentile(1.0))
        stats.firstFrameMs = 310
        assertTrue(stats.summary(2).startsWith("frames=5 reads=2 first_frame_ms=310"))
    }
}
