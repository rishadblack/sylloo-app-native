package com.rishadblack.plugins.sylloo_app_native

/**
 * The receipt printer's rules (pure, JVM-tested; till plan §7.1): the paper widths in printer dots, the CSS width the
 * receipt page is laid out at (as on the PC: 96 CSS px per inch, the printer 203 dots per inch), the copies.
 */
object PrintRules {
    const val DOTS_58 = 384
    const val DOTS_80 = 576
    const val PRINTER_DPI = 203
    const val MAX_HTML_BYTES = 2 * 1024 * 1024
    const val MAX_PNG_BYTES = 4 * 1024 * 1024
    const val MAX_ROWS = 20_000
    const val MAX_COPIES = 5

    /** 58 mm paper = 384 dots, 80 mm = 576 dots; any other width: null. */
    fun dots(paperMm: Number?): Int? = when (paperMm?.toInt()) {
        58 -> DOTS_58
        80 -> DOTS_80
        else -> null
    }

    /** The printable width in CSS px: 384 dots → 182 px, 576 dots → 272 px. */
    fun cssWidth(dots: Int): Int = Math.round(dots * 96.0 / PRINTER_DPI).toInt()

    /** The height in dots of a picture `width` × `height` scaled to `dots` wide, at least 1, at most MAX_ROWS. */
    fun scaledRows(width: Int, height: Int, dots: Int): Int =
        if (width <= 0 || height <= 0) 1 else Math.round(height.toDouble() * dots / width).toInt().coerceIn(1, MAX_ROWS)

    fun copies(value: Number?): Int = (value?.toInt() ?: 1).coerceIn(1, MAX_COPIES)
}

/**
 * Where a printer is (pure, JVM-tested; the PHP twin is `Data\PrinterTarget`):
 * `bt:AA:BB:CC:DD:EE:FF` (a paired Bluetooth printer), `usb:0416:5011` (vendor:product, hex), `net:192.168.1.50:9100`
 * (LAN or Wi-Fi; port 9100 when left out).
 */
sealed class PrinterTarget {
    data class Bluetooth(val address: String) : PrinterTarget()
    data class Usb(val vendorId: Int, val productId: Int) : PrinterTarget()
    data class Network(val host: String, val port: Int) : PrinterTarget()

    fun text(): String = when (this) {
        is Bluetooth -> "bt:$address"
        is Usb -> "usb:%04x:%04x".format(vendorId, productId)
        is Network -> "net:$host:$port"
    }

    companion object {
        const val DEFAULT_PORT = 9100
        private val MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
        private val USB = Regex("^([0-9a-f]{4}):([0-9a-f]{4})$")
        private val HOST = Regex("^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
        private val PORT = Regex("^[0-9]{1,5}$")

        fun parse(value: String?): PrinterTarget? {
            val text = value?.trim() ?: return null
            if (!text.contains(':')) return null
            val rest = text.substringAfter(':')

            return when (text.substringBefore(':').lowercase()) {
                "bt" -> rest.uppercase().takeIf { MAC.matches(it) }?.let { Bluetooth(it) }
                "usb" -> USB.matchEntire(rest.lowercase())?.let { Usb(it.groupValues[1].toInt(16), it.groupValues[2].toInt(16)) }
                "net" -> network(rest.lowercase())
                else -> null
            }
        }

        private fun network(rest: String): Network? {
            val host = if (rest.contains(':')) rest.substringBeforeLast(':') else rest
            val port = if (rest.contains(':')) rest.substringAfterLast(':') else DEFAULT_PORT.toString()
            if (!HOST.matches(host) || !PORT.matches(port)) return null
            val number = port.toInt()
            return if (number in 1..65535) Network(host, number) else null
        }
    }
}

/**
 * ESC/POS bytes (pure, JVM-tested): the receipt as one 1-bit raster picture (`GS v 0`), so Bangla, the logo, the
 * barcode and the layout print exactly as designed with no font tables; the cut; the cash drawer kick (`ESC p`).
 */
object EscPos {
    val INIT = byteArrayOf(0x1B, 0x40)

    /** `GS V 66 0`: feed to the cutter and cut (partial cut on most printers; ignored by printers without one). */
    val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    /** Rows per `GS v 0` command: small printers have a small buffer. */
    const val BAND_ROWS = 256

    /** `ESC d n`: feed n lines. */
    fun feed(lines: Int): ByteArray = byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())

    /**
     * `ESC p m t1 t2`: the drawer kick on pin 2 (m = 0) or pin 5 (m = 1), on for t1 × 2 ms, off for t2 × 2 ms. The
     * default (pin 2, 50 ms, 500 ms) is what the PC till sends.
     */
    fun drawer(pin: Int = 2, onMs: Int = 50, offMs: Int = 500): ByteArray = byteArrayOf(
        0x1B, 0x70,
        (if (pin == 5) 1 else 0).toByte(),
        (onMs / 2).coerceIn(1, 255).toByte(),
        (offMs / 2).coerceIn(1, 255).toByte(),
    )

    /**
     * Brightness 0 (black) … 255 (white) of ARGB pixels, see-through drawn on white paper.
     */
    fun luminance(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val a = (p ushr 24) and 0xFF
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        255 - (a * (255 - lum)) / 255
    }

    /**
     * Floyd–Steinberg dithering to black (true) and white: greys and photos keep their tone; pure black and white
     * stay exactly as they are.
     */
    fun dither(luminance: IntArray, width: Int, height: Int, threshold: Int = 128): BooleanArray {
        require(width > 0 && height > 0 && luminance.size >= width * height) { "picture size" }
        val work = IntArray(width * height) { luminance[it] * 16 }
        val black = BooleanArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val old = work[i]
                val isBlack = old < threshold * 16
                black[i] = isBlack
                val error = old - (if (isBlack) 0 else 255 * 16)
                if (x + 1 < width) work[i + 1] += error * 7 / 16
                if (y + 1 < height) {
                    if (x > 0) work[i + width - 1] += error * 3 / 16
                    work[i + width] += error * 5 / 16
                    if (x + 1 < width) work[i + width + 1] += error / 16
                }
            }
        }
        return black
    }

    /**
     * The picture as `GS v 0` raster commands (`1D 76 30 00 xL xH yL yH` + rows), in bands of at most [bandRows] rows.
     * Each row is `ceil(width / 8)` bytes, the leftmost dot in the highest bit, 1 = black; the padding bits are white.
     */
    fun raster(black: BooleanArray, width: Int, height: Int, bandRows: Int = BAND_ROWS): ByteArray {
        require(width > 0 && height > 0 && black.size >= width * height) { "picture size" }
        val rowBytes = (width + 7) / 8
        val out = java.io.ByteArrayOutputStream(height * rowBytes + (height / bandRows + 1) * 8)
        var top = 0

        while (top < height) {
            val rows = minOf(bandRows, height - top)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (rowBytes and 0xFF).toByte(), (rowBytes shr 8).toByte(), (rows and 0xFF).toByte(), (rows shr 8).toByte()))
            for (y in top until top + rows) {
                for (byteIndex in 0 until rowBytes) {
                    var value = 0
                    for (bit in 0 until 8) {
                        val x = byteIndex * 8 + bit
                        if (x < width && black[y * width + x]) value = value or (0x80 ushr bit)
                    }
                    out.write(value)
                }
            }
            top += rows
        }
        return out.toByteArray()
    }

    /**
     * One printer job: init, the picture `copies` times (each fed and cut when [cut]), then the drawer kick when asked.
     */
    fun receipt(black: BooleanArray, width: Int, height: Int, copies: Int = 1, cut: Boolean = true, openDrawer: Boolean = false): ByteArray {
        val picture = raster(black, width, height)
        val out = java.io.ByteArrayOutputStream()
        out.write(INIT)
        repeat(copies.coerceIn(1, PrintRules.MAX_COPIES)) {
            out.write(picture)
            out.write(feed(4))
            if (cut) out.write(CUT)
        }
        if (openDrawer) out.write(drawer())
        return out.toByteArray()
    }
}
