package com.gpo.yoin.shared.portability

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

internal actual fun platformZoneOffsetMillis(zoneId: String, epochMs: Long): Long = try {
    ZoneId.of(zoneId).rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds * 1000L
} catch (e: DateTimeException) {
    throw IllegalArgumentException("Unknown time zone $zoneId", e)
}
