package com.rishadblack.plugins.sylloo_app_native

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera scanner reads the till's barcode kinds from a camera-like brightness plane (also one lying on its side, as
 * the camera hands it over when the phone is upright), and counts the same code only once within 1.5 s.
 */
class BarcodeDecoderTest {

    /** The code drawn black on white in the middle of a grey-free `width` × `height` picture with a quiet margin. */
    private fun plane(text: String, format: BarcodeFormat, width: Int = 640, height: Int = 480, codeWidth: Int = 480, codeHeight: Int = 160): ByteArray {
        val matrix: BitMatrix = MultiFormatWriter().encode(text, format, codeWidth, codeHeight)
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        return ByteArray(width * height) { i ->
            val x = i % width - left
            val y = i / width - top
            val dark = x in 0 until matrix.width && y in 0 until matrix.height && matrix.get(x, y)
            (if (dark) 0 else 255).toByte()
        }
    }

    @Test
    fun readsEan13() {
        assertEquals(BarcodeDecoder.Read("8901234567890", "ean_13"), BarcodeDecoder.decode(plane("8901234567890", BarcodeFormat.EAN_13), 640, 640, 480))
    }

    @Test
    fun readsEan8() {
        assertEquals(BarcodeDecoder.Read("96385074", "ean_8"), BarcodeDecoder.decode(plane("96385074", BarcodeFormat.EAN_8), 640, 640, 480))
    }

    @Test
    fun readsUpcA() {
        assertEquals("upc_a", BarcodeDecoder.decode(plane("036000291452", BarcodeFormat.UPC_A), 640, 640, 480)?.format)
    }

    @Test
    fun readsUpcE() {
        assertEquals(BarcodeDecoder.Read("01234565", "upc_e"), BarcodeDecoder.decode(plane("01234565", BarcodeFormat.UPC_E), 640, 640, 480))
    }

    @Test
    fun readsCode128WithLettersAndDashes() {
        assertEquals(BarcodeDecoder.Read("SKU-A12-77", "code_128"), BarcodeDecoder.decode(plane("SKU-A12-77", BarcodeFormat.CODE_128), 640, 640, 480))
    }

    @Test
    fun readsCode39() {
        assertEquals(BarcodeDecoder.Read("TILL42", "code_39"), BarcodeDecoder.decode(plane("TILL42", BarcodeFormat.CODE_39), 640, 640, 480))
    }

    @Test
    fun readsQr() {
        assertEquals(BarcodeDecoder.Read("P-1001", "qr_code"), BarcodeDecoder.decode(plane("P-1001", BarcodeFormat.QR_CODE, codeWidth = 300, codeHeight = 300), 640, 640, 480))
    }

    @Test
    fun readsABarcodeLyingOnItsSide() {
        val upright = plane("8901234567890", BarcodeFormat.EAN_13)
        // The camera's frame of a phone held upright: the picture turned a quarter, 480 wide and 640 high.
        val sideways = BarcodeDecoder.turn(BarcodeDecoder.turn(BarcodeDecoder.turn(upright, 640, 640, 480), 480, 480, 640), 640, 640, 480)
        assertEquals(BarcodeDecoder.Read("8901234567890", "ean_13"), BarcodeDecoder.decode(sideways, 480, 480, 640))
    }

    @Test
    fun keepsTheRowStrideOfTheCamera() {
        val packed = plane("8901234567890", BarcodeFormat.EAN_13)
        val stride = 704
        val padded = ByteArray(stride * 480) { 255.toByte() }
        for (y in 0 until 480) System.arraycopy(packed, y * 640, padded, y * stride, 640)
        assertEquals("8901234567890", BarcodeDecoder.decode(padded, stride, 640, 480)?.text)
    }

    @Test
    fun findsNothingInAnEmptyPicture() {
        assertNull(BarcodeDecoder.decode(ByteArray(640 * 480) { 127 }, 640, 640, 480))
    }

    @Test
    fun turnsAQuarterClockwise() {
        // 3 wide, 2 high: a b c / d e f  ->  2 wide, 3 high: d a / e b / f c
        val turned = BarcodeDecoder.turn(byteArrayOf(1, 2, 3, 4, 5, 6), 3, 3, 2)
        assertEquals(listOf<Byte>(4, 1, 5, 2, 6, 3), turned.toList())
    }

    @Test
    fun countsTheSameCodeOnceWithinOneAndAHalfSeconds() {
        val guard = ScanRepeatGuard()
        assertTrue(guard.accept("8901234567890", 10_000))
        assertFalse(guard.accept("8901234567890", 10_400))
        assertFalse(guard.accept("8901234567890", 11_499))
        assertTrue(guard.accept("8901234567890", 11_500))
    }

    @Test
    fun countsADifferentCodeAtOnceAndTheFirstAgainAfterIt() {
        val guard = ScanRepeatGuard()
        assertTrue(guard.accept("A1", 0))
        assertTrue(guard.accept("B2", 100))
        assertTrue(guard.accept("A1", 200))
    }
}
