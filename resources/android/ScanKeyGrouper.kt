package com.rishadblack.plugins.sylloo_app_native

/**
 * Groups the key strokes of a scanner that types (USB or Bluetooth, till plan §3.4, §7.2) into one code (pure,
 * JVM-tested). A scanner types much faster than a person: its characters come at most [gapMs] apart, then Enter (or
 * nothing for [endMs] when [endOnGap]). Slower typing never makes a code, and nothing is grouped while a text field has
 * the cursor (the scanner then types into that field, as on the PC).
 *
 * A scanner's Enter can come late (a slow Bluetooth link; adb on a test phone), after the quiet gap already ended the
 * code: it still belongs to that code, so it is kept from the app (else the screen gets a stray Enter, and on a Compose
 * screen the next code is lost). Within [LATE_ENTER_MS] of a code ended by the gap, with nothing typed since, an Enter
 * is the scanner's.
 *
 * Times are in ms on one clock (Android: `KeyEvent.eventTime`, `SystemClock.uptimeMillis()`).
 */
class ScanKeyGrouper(
    val gapMs: Long = DEFAULT_GAP_MS,
    val endMs: Long = DEFAULT_END_MS,
    val minLength: Int = DEFAULT_MIN_LENGTH,
    val endOnGap: Boolean = true,
    val maxLength: Int = MAX_LENGTH,
) {
    sealed class Key {
        data class Typed(val char: Char) : Key()
        object Enter : Key()

        /** Shift, Alt, Ctrl…: scanners press Shift for capitals; it neither adds nor breaks a code. */
        object Modifier : Key()

        /** Arrows, Tab, Escape, function keys…: a person, not a scanner. */
        object Other : Key()
    }

    /** What one key stroke did: the code it ended (or null), and whether the app must not see this key. */
    data class Step(val code: String?, val consume: Boolean)

    private val buffer = StringBuilder()
    private var last = -1L

    /** When the quiet gap last ended a code (-1: not since the last key). */
    private var gapEndedAt = -1L

    val pending: Int get() = buffer.length

    fun key(key: Key, at: Long, typingInField: Boolean = false): Step {
        if (typingInField) {
            reset()
            return Step(null, false)
        }

        return when (key) {
            is Key.Modifier -> Step(null, false)
            is Key.Other -> {
                reset()
                Step(null, false)
            }
            is Key.Enter -> {
                if (buffer.isEmpty()) {
                    // The late Enter of a code the gap already ended.
                    val late = gapEndedAt >= 0 && at - gapEndedAt <= LATE_ENTER_MS
                    gapEndedAt = -1L
                    return Step(null, late)
                }
                val code = when {
                    buffer.length < minLength -> null
                    at - last <= endMs -> buffer.toString()
                    // The quiet timer had not run yet: the gap ended the code before this Enter came.
                    else -> endedByGap()
                }
                reset()
                Step(code, code != null)
            }
            is Key.Typed -> {
                var ended: String? = null
                if (buffer.isNotEmpty() && at - last > gapMs) {
                    ended = endedByGap()
                    reset()
                }
                gapEndedAt = -1L
                if (buffer.length >= maxLength) reset()
                buffer.append(key.char)
                last = at
                Step(ended, false)
            }
        }
    }

    /**
     * The quiet timer: after [endMs] with no key, a fast group ends as a code (when [endOnGap]); anything else is
     * dropped.
     */
    fun flush(at: Long): String? {
        if (buffer.isEmpty() || at - last < endMs) return null
        val code = endedByGap()
        reset()
        if (code != null) gapEndedAt = at
        return code
    }

    fun reset() {
        buffer.setLength(0)
        last = -1L
    }

    private fun endedByGap(): String? = if (endOnGap && buffer.length >= minLength) buffer.toString() else null

    companion object {
        const val DEFAULT_GAP_MS = 60L
        const val DEFAULT_END_MS = 150L
        const val DEFAULT_MIN_LENGTH = 4
        const val MAX_LENGTH = 256

        /** How long after a code ended by the quiet gap an Enter is still that code's. */
        const val LATE_ENTER_MS = 1_000L

        /** The settings the PHP side sends, kept within sane bounds. */
        fun from(gapMs: Number?, endMs: Number?, minLength: Number?, endOnGap: Boolean?): ScanKeyGrouper {
            val gap = (gapMs?.toLong() ?: DEFAULT_GAP_MS).coerceIn(10L, 500L)
            val end = (endMs?.toLong() ?: DEFAULT_END_MS).coerceIn(gap, 2_000L)
            val min = (minLength?.toInt() ?: DEFAULT_MIN_LENGTH).coerceIn(1, 64)
            return ScanKeyGrouper(gap, end, min, endOnGap ?: true)
        }
    }
}
