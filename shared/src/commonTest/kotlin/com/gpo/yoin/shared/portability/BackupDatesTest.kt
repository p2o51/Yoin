package com.gpo.yoin.shared.portability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupDatesTest {
    @Test
    fun should_formatLikeJavaTime_when_zoneIsUtcOrAFixedOffset() {
        assertEquals("1970-01-01 00:00", BackupDates.dateTime(0, "UTC"))
        assertEquals("1970-01-01 00:00", BackupDates.dateTime(0, "Z"))
        assertEquals("2023-11-14 22:13", BackupDates.dateTime(1_700_000_000_000, "UTC"))
        assertEquals("2023-11-15 06:13", BackupDates.dateTime(1_700_000_000_000, "+08:00"))
        assertEquals("2023-11-14 16:43", BackupDates.dateTime(1_700_000_000_000, "-0530"))
        assertEquals("1969-12-31 23:59", BackupDates.dateTime(-1, "UTC"))
        assertEquals("2026-11-23", BackupDates.date(1_795_400_000_000, "UTC"))
        assertEquals("yoin-backup-2026-11-23.zip", BackupFormat.fileName(1_795_400_000_000, "Z"))
    }

    @Test
    fun should_followTheZonesRules_when_zoneIsAnIanaId() {
        // Shanghai has no DST; New York is EDT (-4) in July and EST (-5) in November.
        assertEquals("2023-11-15 06:13", BackupDates.dateTime(1_700_000_000_000, "Asia/Shanghai"))
        assertEquals("2023-07-10 10:40", BackupDates.dateTime(1_689_000_000_000, "America/New_York"))
        assertEquals("2023-11-14 17:13", BackupDates.dateTime(1_700_000_000_000, "America/New_York"))
    }

    @Test
    fun should_fallBackToAnAbbreviation_when_theIdIsNotAZoneName() {
        // NSTimeZone.timeZoneWithAbbreviation on iOS, java.time's SHORT_IDS on Android: both Asia/Tokyo.
        assertEquals("2023-11-15 07:13", BackupDates.dateTime(1_700_000_000_000, "JST"))
    }

    @Test
    fun should_refuse_when_zoneIsUnknown() {
        assertFailsWith<IllegalArgumentException> { BackupDates.dateTime(0, "Mars/Olympus_Mons") }
    }
}
