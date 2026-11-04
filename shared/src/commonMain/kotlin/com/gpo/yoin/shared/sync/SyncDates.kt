package com.gpo.yoin.shared.sync

/** "yyyy-MM-dd" of [epochMs] in UTC, or null outside years 0..9999 (Android's ISO_LOCAL_DATE range we need). */
internal fun isoUtcDate(epochMs: Long): String? {
    val days = floorDiv(epochMs, 86_400_000L)
    // Howard Hinnant's civil_from_days.
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
    if (y < 0 || y > 9999) return null
    return y.toString().padStart(4, '0') + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')
}

/** Epoch ms of an RFC 3339 timestamp ("2026-10-04T08:15:30.123Z", "+09:00" offsets), or null. */
internal fun parseRfc3339Millis(text: String): Long? {
    val match = RFC3339.matchEntire(text.trim()) ?: return null
    val g = match.groupValues
    val year = g[1].toLong()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    val fraction = g[7].takeIf { it.isNotEmpty() }?.drop(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
    val offsetMs = when (val zone = g[8]) {
        "Z", "z" -> 0L
        else -> {
            val sign = if (zone[0] == '-') -1 else 1
            sign * (zone.substring(1, 3).toLong() * 60 + zone.substring(4, 6).toLong()) * 60_000L
        }
    }
    val days = daysFromCivil(year, month, day)
    return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L + fraction - offsetMs
}

private val RFC3339 =
    Regex("""(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = floorDiv(y, 400)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q
}
