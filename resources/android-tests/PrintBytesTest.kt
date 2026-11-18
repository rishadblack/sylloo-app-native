package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receipt printer's bytes and rules (till plan §7.1), checked on the computer: `gradlew testDebugUnitTest`. */
class PrintBytesTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun picture(vararg rows: String): BooleanArray = rows.joinToString("").map { it == '#' }.toBooleanArray()

    @Test
    fun paperWidthsInDotsAndTheCssWidthOfThePage() {
        assertEquals(384, PrintRules.dots(58))
        assertEquals(576, PrintRules.dots(80))
        assertNull(PrintRules.dots(57))
        assertNull(PrintRules.dots(null))
        assertEquals(182, PrintRules.cssWidth(384))
        assertEquals(272, PrintRules.cssWidth(576))
        assertEquals(768, PrintRules.scaledRows(500, 1000, 384))
        assertEquals(PrintRules.MAX_ROWS, PrintRules.scaledRows(10, 10_000_000, 576))
        assertEquals(1, PrintRules.copies(null))
        assertEquals(5, PrintRules.copies(9))
        assertEquals(1, PrintRules.copies(0))
    }

    @Test
    fun aTinyKnownPictureBecomesTheseRasterBytes() {
        // 8 × 2: a full black row, then every other dot.
        val black = picture("########", "#.#.#.#.")

        assertArrayEquals(bytes(0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x02, 0x00, 0xFF, 0xAA), EscPos.raster(black, 8, 2))
    }

    @Test
    fun aWidthThatIsNoMultipleOfEightIsPaddedWithWhite() {
        // 10 × 1: dots 0, 8 and 9 black → 2 bytes per row: 1000 0000, 1100 0000.
        val black = picture("#.......##")

        assertArrayEquals(bytes(0x1D, 0x76, 0x30, 0x00, 0x02, 0x00, 0x01, 0x00, 0x80, 0xC0), EscPos.raster(black, 10, 1))
    }

    @Test
    fun fiftyEightAndEightyMillimetreRowsHaveTheirOwnWidths() {
        val narrow = EscPos.raster(BooleanArray(384), 384, 1)
        val wide = EscPos.raster(BooleanArray(576), 576, 1)

        assertEquals(48, narrow[4].toInt())
        assertEquals(0, narrow[5].toInt())
        assertEquals(8 + 48, narrow.size)
        assertEquals(72, wide[4].toInt())
        assertEquals(8 + 72, wide.size)
    }

    @Test
    fun tallPicturesAreSentInBands() {
        val out = EscPos.raster(BooleanArray(8 * 300), 8, 300, bandRows = 256)

        assertEquals(8 + 256 + 8 + 44, out.size)
        assertEquals(0, out[6].toInt()) // 256 rows: yL 0, yH 1
        assertEquals(1, out[7].toInt())
        assertEquals(0x1D, out[8 + 256].toInt())
        assertEquals(44, out[8 + 256 + 6].toInt())
    }

    @Test
    fun pureBlackAndWhiteStayAsTheyAreAndGreyIsDithered() {
        val lum = intArrayOf(0, 255, 0, 255, 255, 0, 255, 0)
        assertArrayEquals(booleanArrayOf(true, false, true, false, false, true, false, true), EscPos.dither(lum, 4, 2))

        val grey = EscPos.dither(IntArray(64 * 64) { 128 }, 64, 64)
        val share = grey.count { it } / grey.size.toDouble()
        assertTrue("about half black, was $share", share in 0.4..0.6)
    }

    @Test
    fun seeThroughPixelsAreWhitePaper() {
        val lum = EscPos.luminance(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x00000000, 0xFF808080.toInt()))

        assertEquals(0, lum[0])
        assertEquals(255, lum[1])
        assertEquals(255, lum[2])
        assertEquals(128, lum[3])
    }

    @Test
    fun theDrawerKickAndTheReceiptJob() {
        assertArrayEquals(bytes(0x1B, 0x70, 0x00, 0x19, 0xFA), EscPos.drawer())
        assertArrayEquals(bytes(0x1B, 0x70, 0x01, 0x32, 0x64), EscPos.drawer(pin = 5, onMs = 100, offMs = 200))

        val black = picture("########")
        val raster = EscPos.raster(black, 8, 1)
        val job = EscPos.receipt(black, 8, 1, copies = 2, cut = true, openDrawer = true)
        val expected = EscPos.INIT + raster + EscPos.feed(4) + EscPos.CUT + raster + EscPos.feed(4) + EscPos.CUT + EscPos.drawer()
        assertArrayEquals(expected, job)

        val noCut = EscPos.receipt(black, 8, 1, cut = false)
        assertArrayEquals(EscPos.INIT + raster + EscPos.feed(4), noCut)
    }

    @Test
    fun printerTargets() {
        assertEquals(PrinterTarget.Bluetooth("AA:BB:CC:DD:EE:0F"), PrinterTarget.parse("bt:aa:bb:cc:dd:ee:0f"))
        assertEquals(PrinterTarget.Usb(0x0416, 0x5011), PrinterTarget.parse("usb:0416:5011"))
        assertEquals(PrinterTarget.Network("192.168.1.50", 9100), PrinterTarget.parse("net:192.168.1.50"))
        assertEquals(PrinterTarget.Network("printer.local", 9101), PrinterTarget.parse(" NET:Printer.local:9101 "))
        assertEquals("usb:0416:5011", PrinterTarget.Usb(0x0416, 0x5011).text())
        assertEquals("net:192.168.1.50:9100", PrinterTarget.parse("net:192.168.1.50")!!.text())

        for (bad in listOf(null, "", "bt:", "bt:AA:BB", "usb:416:5011", "net:", "net:a b", "net:host:0", "net:host:70000", "net:http://x", "lpt:1", "AA:BB:CC:DD:EE:FF")) {
            assertNull(bad, PrinterTarget.parse(bad))
        }
    }
}
