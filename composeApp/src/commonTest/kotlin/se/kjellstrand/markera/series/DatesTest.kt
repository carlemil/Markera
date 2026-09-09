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
}
