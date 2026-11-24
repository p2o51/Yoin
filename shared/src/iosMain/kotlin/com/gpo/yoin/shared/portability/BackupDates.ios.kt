package com.gpo.yoin.shared.portability

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithAbbreviation
import platform.Foundation.timeZoneWithName

/** An IANA id first, then an abbreviation NSTimeZone knows ("JST", "CEST"); anything else is refused. */
internal actual fun platformZoneOffsetMillis(zoneId: String, epochMs: Long): Long {
    val zone = NSTimeZone.timeZoneWithName(zoneId)
        ?: NSTimeZone.timeZoneWithAbbreviation(zoneId)
        ?: throw IllegalArgumentException("Unknown time zone $zoneId")
    return zone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L
}
