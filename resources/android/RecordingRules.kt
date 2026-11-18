package com.rishadblack.plugins.sylloo_app_native

/** One file of the chosen recordings folder, as listed (name, last change, size). */
data class RecordingFile(val name: String, val modifiedMs: Long, val size: Long)

/** What the search for one call's recording found: `found` with the file, or why none was taken. */
data class RecordingMatch(val status: String, val file: RecordingFile? = null)

/**
 * The rules of a call's recording (pure, JVM-tested; crm-call-recording-plan.md §5 R2, CR3): which file of the folder
 * the person chose is the recording of one saved work call. The phone's own call recorder writes a file per call; its
 * name often holds the time the call started (`20260302_085000`, `260302_085000`, `2026-03-02 08-50-00`) and sometimes
 * the number. The rules, in order:
 *
 * - a call of 0 seconds has no recording (`no_call`); one longer than the most minutes is `too_long`;
 * - only sound files (the extensions below), not empty, not bigger than the most bytes;
 * - a name that holds a long number (7 digits or more, the time taken out) that does not end in the call's compare key
 *   is another person's call: never taken;
 * - a name with a time is taken only when that time is within [NAME_WINDOW_MS] of the call's start (the name is local
 *   time: the phone's offset is given); a name with another time is not this call;
 * - a name without a time is taken when the file's last change is within the end of the call (start + length) minus
 *   [END_BEFORE_MS] to plus [END_AFTER_MS] (the recorder closes its file when the call ends);
 * - exactly one file must fit: two by name, or none by name and two by time, is `ambiguous` and nothing is taken (a
 *   wrong recording on a lead's timeline is worse than none). None: `none`.
 */
object RecordingRules {
    const val NAME_WINDOW_MS = 90_000L
    const val END_BEFORE_MS = 60_000L
    const val END_AFTER_MS = 180_000L
    const val DEFAULT_MAX_MINUTES = 60
    const val DEFAULT_MAX_BYTES = 40L * 1024 * 1024

    /** Sound files a recorder writes; m4a / mp4 / aac / 3gp are re-wrapped as .mp4, the others are copied as they are. */
    val EXTENSIONS = setOf("m4a", "mp4", "aac", "3gp", "amr", "mp3", "wav", "ogg", "opus")

    /** The extensions the ERP keeps as they are (no re-wrap). */
    val KEPT_AS_IS = setOf("mp3", "wav", "ogg")

    /** The extensions re-wrapped to .mp4 (AAC sound only; AMR cannot be, see [target]). */
    val REWRAPPED = setOf("m4a", "mp4", "aac", "3gp")

    private val LONG_TIME = Regex("""(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})[-_. T]?(\d{2})[-_.:]?(\d{2})[-_.:]?(\d{2})""")
    private val SHORT_TIME = Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})[-_ ](\d{2})(\d{2})(\d{2})(?!\d)""")
    private val LONG_NUMBER = Regex("""\d{7,}""")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** What the file becomes for the ERP: `mp4` (re-wrapped), the same (copied), or null (not a sound the ERP keeps). */
    fun target(extension: String): String? = when (extension) {
        in REWRAPPED -> "mp4"
        in KEPT_AS_IS -> extension
        else -> null
    }

    /**
     * The start time in a file name, as UTC milliseconds (the name is the phone's local time: [offsetMs] is the phone's
     * offset from UTC at that time), or null when the name has none or an impossible one.
     */
    fun nameTime(name: String, offsetMs: Long): Long? {
        val long = LONG_TIME.find(name)
        val parts = long?.groupValues?.drop(1)?.map { it.toInt() }
            ?: SHORT_TIME.find(name)?.groupValues?.drop(1)?.map { it.toInt() }?.let { listOf(2000 + it[0]) + it.drop(1) }
            ?: return null

        val (year, month, day, hour, minute, second) = Six(parts)
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null

        return utcMs(year, month, day, hour, minute, second) - offsetMs
    }

    /** Whether the name holds a long number (the time taken out) that is not the call's: another person's call. */
    fun otherNumber(name: String, key: String?): Boolean {
        val withoutTime = SHORT_TIME.replace(LONG_TIME.replace(name, " "), " ")

        return LONG_NUMBER.findAll(withoutTime).any { found -> key == null || key.isEmpty() || !found.value.endsWith(key) }
    }

    fun match(
        files: List<RecordingFile>,
        startedAtMs: Long,
        durationSeconds: Long,
        key: String?,
        offsetMs: Long,
        maxMinutes: Int = DEFAULT_MAX_MINUTES,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): RecordingMatch {
        if (durationSeconds <= 0) return RecordingMatch("no_call")
        if (durationSeconds > maxMinutes.coerceAtLeast(1) * 60L) return RecordingMatch("too_long")

        val endMs = startedAtMs + durationSeconds * 1000
        val byName = ArrayList<RecordingFile>()
        val byTime = ArrayList<RecordingFile>()

        for (file in files) {
            if (extension(file.name) !in EXTENSIONS || file.size <= 0 || file.size > maxBytes) continue
            if (otherNumber(file.name, key)) continue

            val named = nameTime(file.name, offsetMs)

            if (named != null) {
                if (kotlin.math.abs(named - startedAtMs) <= NAME_WINDOW_MS) byName.add(file)
                continue
            }

            if (file.modifiedMs in (endMs - END_BEFORE_MS)..(endMs + END_AFTER_MS)) byTime.add(file)
        }

        return when {
            byName.size == 1 -> RecordingMatch("found", byName[0])
            byName.size > 1 -> RecordingMatch("ambiguous")
            byTime.size == 1 -> RecordingMatch("found", byTime[0])
            byTime.size > 1 -> RecordingMatch("ambiguous")
            else -> RecordingMatch("none")
        }
    }

    /** A safe file name for the copy of a call's recording (the app's call id: letters, digits, `-`, `_`, `:`). */
    fun copyName(callId: String?, extension: String): String? {
        val id = (callId ?: "").trim()
        if (id.isEmpty() || id.length > 80 || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' }) return null

        return id.replace(':', '_') + "." + extension
    }

    /** Days since 1970-01-01 (proleptic Gregorian), so the rules need no Android or java.time. */
    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L

        return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L
    }

    private data class Six(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int, val f: Int) {
        constructor(list: List<Int>) : this(list[0], list[1], list[2], list[3], list[4], list[5])
    }
}
