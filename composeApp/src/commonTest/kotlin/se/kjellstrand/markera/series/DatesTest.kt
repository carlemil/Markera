package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class DatesTest {

    @Test
    fun `localStamp renders the instant in the given zone`() {
        assertEquals(
            "2026-09-01 12:00",
            localStamp("2026-09-01T10:00:00Z", TimeZone.of("Europe/Stockholm")),
        )
    }

    @Test
    fun `localStamp hands back anything it cannot parse`() {
        assertEquals("garbage", localStamp("garbage"))
    }

    @Test
    fun `localTime is the time half in the given zone`() {
        assertEquals("12:00", localTime("2026-09-01T10:00:00Z", TimeZone.of("Europe/Stockholm")))
    }

    @Test
    fun `localTime hands back anything it cannot parse`() {
        assertEquals("garbage", localTime("garbage"))
    }

    @Test
    fun `utcDay is the UTC date of the millis`() {
        assertEquals("1970-01-01", utcDay(0L))
    }

    @Test
    fun `exportStamp is a file-name-safe stamp`() {
        assertEquals(
            "20260901-1005",
            exportStamp(Instant.parse("2026-09-01T10:05:00Z"), TimeZone.UTC),
        )
    }

    @Test
    fun `isoUtcMillis is toISOString with three fraction digits`() {
        assertEquals("2026-08-15T12:00:00.000Z", isoUtcMillis(Instant.parse("2026-08-15T14:00:00+02:00")))
        assertEquals("2026-08-15T12:00:00.120Z", isoUtcMillis(Instant.parse("2026-08-15T12:00:00.1209Z")))
    }

    @Test
    fun exifInstantUsesTheOffsetOrElseTheZone() {
        val stockholm = TimeZone.of("Europe/Stockholm")
        assertEquals("2026-05-01T08:30:00Z", exifInstant("2026:05:01 10:30:00", "+02:00", TimeZone.UTC))
        assertEquals("2026-05-01T08:30:00Z", exifInstant("2026:05:01 10:30:00", null, stockholm))
        assertEquals(null, exifInstant("0000:00:00 00:00:00", null, stockholm))
        assertEquals(null, exifInstant(null, null, stockholm))
    }
}
