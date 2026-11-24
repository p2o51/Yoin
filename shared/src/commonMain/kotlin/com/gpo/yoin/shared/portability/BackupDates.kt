package com.gpo.yoin.shared.portability

/**
 * The UTC offset of the time zone [zoneId] (an IANA id such as "Asia/Shanghai") at [epochMs], in ms.
 * Android reads java.time's zone rules, iOS NSTimeZone's: the same tz database. Throws
 * [IllegalArgumentException] for an id the platform doesn't know.
 */
internal expect fun platformZoneOffsetMillis(zoneId: String, epochMs: Long): Long

/**
 * Dates as the backup writes them (file name, readable Markdown): "yyyy-MM-dd" and "yyyy-MM-dd HH:mm" in a
 * time zone, the same text java.time's DateTimeFormatter gives for years 0..9999.
 */
object BackupDates {
    /** "yyyy-MM-dd" of [epochMs] in [zoneId]. */
    fun date(epochMs: Long, zoneId: String): String = civil(epochMs + offsetMillis(zoneId, epochMs)).let { (date, _) -> date }

    /** "yyyy-MM-dd HH:mm" of [epochMs] in [zoneId]. */
    fun dateTime(epochMs: Long, zoneId: String): String =
        civil(epochMs + offsetMillis(zoneId, epochMs)).let { (date, time) -> "$date $time" }

    /** "Z", "UTC", "GMT" and fixed offsets ("+08:00", "-0530", "UTC+8") are resolved here; ids go to the platform. */
    fun offsetMillis(zoneId: String, epochMs: Long): Long {
        val id = zoneId.trim()
        if (id == "Z" || id == "UTC" || id == "GMT" || id == "UT") return 0L
        fixedOffset(id)?.let { return it }
        return platformZoneOffsetMillis(id, epochMs)
    }

    private val FIXED = Regex("""^(?:UTC|GMT|UT)?([+-])(\d{1,2})(?::?(\d{2}))?$""")

    private fun fixedOffset(id: String): Long? {
        val match = FIXED.matchEntire(id) ?: return null
        val (sign, hours, minutes) = match.destructured
        val total = hours.toLong() * 3_600_000L + (minutes.toLongOrNull() ?: 0L) * 60_000L
        if (total > 18 * 3_600_000L) return null
        return if (sign == "-") -total else total
    }

    /** ("yyyy-MM-dd", "HH:mm") of a local epoch-ms value (Howard Hinnant's civil_from_days). */
    private fun civil(localMs: Long): Pair<String, String> {
        val days = floorDiv(localMs, 86_400_000L)
        val msOfDay = localMs - days * 86_400_000L
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        val minutes = msOfDay / 60_000L
        val date = y.toString().padStart(4, '0') + "-" + two(m) + "-" + two(d)
        return date to (two(minutes / 60) + ":" + two(minutes % 60))
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q
    }
}
