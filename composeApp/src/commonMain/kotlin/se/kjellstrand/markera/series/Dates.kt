package se.kjellstrand.markera.series

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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
