package com.gpo.yoin.shared.portability

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/** An IANA id first, then java.time's short ids ("JST", "CTT"), as iOS falls back to abbreviations. */
internal actual fun platformZoneOffsetMillis(zoneId: String, epochMs: Long): Long = try {
    ZoneId.of(zoneId, ZoneId.SHORT_IDS).rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds * 1000L
} catch (e: DateTimeException) {
    throw IllegalArgumentException("Unknown time zone $zoneId", e)
}
