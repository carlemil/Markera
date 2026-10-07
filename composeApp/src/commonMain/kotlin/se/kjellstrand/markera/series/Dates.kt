package se.kjellstrand.markera.series

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The date formatting the UI needs, on kotlinx-datetime — the screens are common
 * code. 0.6.x on purpose: Compose material3's date pickers link against 0.6, and
 * 0.7 drops the `kotlinx.datetime.Instant` they use (iOS would crash on open).
 */

private fun Int.pad(width: Int = 2) = toString().padStart(width, '0')

/** `yyyy-MM-dd HH:mm` in [zone]; the raw string back if it isn't an ISO instant. */
fun localStamp(timestamp: String, zone: TimeZone = TimeZone.currentSystemDefault()): String = try {
    val t = Instant.parse(timestamp).toLocalDateTime(zone)
    "${t.year.pad(4)}-${t.monthNumber.pad()}-${t.dayOfMonth.pad()} ${t.hour.pad()}:${t.minute.pad()}"
} catch (_: Exception) {
    timestamp
}

/**
 * `HH:mm` in [zone]; the raw string back if it isn't an ISO instant. For the
 * history list, where the day is already the group header above the card.
 */
fun localTime(timestamp: String, zone: TimeZone = TimeZone.currentSystemDefault()): String = try {
    val t = Instant.parse(timestamp).toLocalDateTime(zone)
    "${t.hour.pad()}:${t.minute.pad()}"
} catch (_: Exception) {
    timestamp
}

/** `yyyy-MM-dd` of a UTC start-of-day millis, which is what the range picker returns. */
fun utcDay(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()

/** `yyyyMMdd-HHmm`, for the export file name. */
fun exportStamp(
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val t = now.toLocalDateTime(zone)
    return "${t.year.pad(4)}${t.monthNumber.pad()}${t.dayOfMonth.pad()}-${t.hour.pad()}${t.minute.pad()}"
}

/** `yyyy-MM-ddTHH:mm:ss.SSSZ` in UTC, JavaScript's `toISOString` (what webshooter's web client sends). */
fun isoUtcMillis(now: Instant = Clock.System.now()): String {
    val t = now.toLocalDateTime(TimeZone.UTC)
    return "${t.year.pad(4)}-${t.monthNumber.pad()}-${t.dayOfMonth.pad()}T" +
        "${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}.${(t.nanosecond / 1_000_000).pad(3)}Z"
}

/**
 * A photo's EXIF `DateTimeOriginal` (`yyyy:MM:dd HH:mm:ss`) as an ISO instant, using
 * its `OffsetTimeOriginal` (`+02:00`) when there is one and [zone] otherwise. Null
 * for a missing or unreadable date (cameras write `0000:00:00 00:00:00` too).
 */
fun exifInstant(
    dateTime: String?,
    offset: String?,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String? = try {
    val (date, time) = dateTime!!.trim().split(' ', limit = 2)
    val local = LocalDateTime.parse(date.replace(':', '-') + "T" + time.trim())
    (if (offset.isNullOrBlank()) local.toInstant(zone) else local.toInstant(UtcOffset.parse(offset.trim()))).toString()
} catch (_: Exception) {
    null
}
