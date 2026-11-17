package com.rishadblack.plugins.sylloo_app_native

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ZXing reads a QR code from a camera-like luminance plane, also a light-on-dark one. */
class QrDecoderTest {

    private fun plane(text: String, inverted: Boolean = false, size: Int = 300): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(size * size) { i ->
            val dark = matrix.get(i % size, i / size) != inverted
            (if (dark) 0 else 255).toByte()
        }
    }

    @Test
    fun readsAnActivationLink() {
        val text = "sylloo-app://activate?code=1111222233334444"
        assertEquals(text, QrDecoder.decode(plane(text), 300, 300, 300))
    }

    @Test
    fun readsAnInvertedCode() {
        assertEquals("1111222233334444", QrDecoder.decode(plane("1111222233334444", inverted = true), 300, 300, 300))
    }

    @Test
    fun findsNothingInAnEmptyPicture() {
        assertNull(QrDecoder.decode(ByteArray(300 * 300) { 127 }, 300, 300, 300))
    }
}
