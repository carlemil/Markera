package se.kjellstrand.markera.ui.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.stats.DatePreset

@OptIn(ExperimentalTime::class)
class HistoryFilterTest {

    private fun series(
        id: Long,
        caliber: String = "9mm",
        timestamp: String = "2026-09-01T10:00:00Z",
        tag: String? = null,
    ) = SeriesDto(id = id, timestamp = timestamp, caliber = caliber, holes = emptyList(), tag = tag)

    private val now = Instant.parse("2026-09-15T10:00:00Z")

    @Test
    fun `two selected calibers keep both and preserve order`() {
        val nine = series(1, caliber = "9mm")
        val lr = series(2, caliber = "22lr")
        val other = series(3, caliber = "45")
        val filter = HistoryFilter(calibers = setOf(Caliber.MM9, Caliber.LR22))
        val kept = listOf(nine, lr, other).filteredBy(filter, now)
        assertEquals(listOf(1L, 2L), kept.map { it.id })
    }

    @Test
    fun `an empty caliber set passes everything including a dash series`() {
        val dash = series(1, caliber = "-")
        val nine = series(2, caliber = "9mm")
        val kept = listOf(dash, nine).filteredBy(HistoryFilter(), now)
        assertEquals(listOf(1L, 2L), kept.map { it.id })
    }

    @Test
    fun `caliber and date combine with and`() {
        val recentNine = series(1, caliber = "9mm", timestamp = "2026-09-14T10:00:00Z")
        val oldNine = series(2, caliber = "9mm", timestamp = "2026-08-01T10:00:00Z")
        val recentOther = series(3, caliber = "45", timestamp = "2026-09-14T10:00:00Z")
        val filter = HistoryFilter(calibers = setOf(Caliber.MM9), preset = DatePreset.WEEK)
        val kept = listOf(recentNine, oldNine, recentOther).filteredBy(filter, now)
        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `a custom range includes the whole end day`() {
        // The picker's UTC start-of-day millis for 2026-09-05 .. 2026-09-06.
        val start = Instant.parse("2026-09-05T00:00:00Z").toEpochMilliseconds()
        val end = Instant.parse("2026-09-06T00:00:00Z").toEpochMilliseconds()
        val lateOnEndDay = series(1, timestamp = "2026-09-06T23:59:59Z")
        val nextDay = series(2, timestamp = "2026-09-07T00:00:01Z")
        val filter = HistoryFilter(preset = DatePreset.CUSTOM, customRange = start to end)
        val kept = listOf(lateOnEndDay, nextDay).filteredBy(filter, now)
        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `calibersPresent includes none and sorts by ordinal`() {
        val list = listOf(series(1, caliber = "9mm"), series(2, caliber = "-"), series(3, caliber = "22lr"))
        assertEquals(listOf(Caliber.NONE, Caliber.LR22, Caliber.MM9), list.calibersPresent())
    }

    @Test
    fun `series on the same local day land in one group`() {
        val late = series(1, timestamp = "2026-09-15T22:00:00Z")
        val early = series(2, timestamp = "2026-09-15T06:00:00Z")
        val groups = listOf(late, early).groupedByDay(TimeZone.UTC)
        assertEquals(listOf("2026-09-15"), groups.map { it.day })
        assertEquals(listOf(1L, 2L), groups.single().series.map { it.id })
    }

    @Test
    fun `days come newest first`() {
        val old = series(1, timestamp = "2026-09-01T10:00:00Z")
        val new = series(2, timestamp = "2026-09-15T10:00:00Z")
        val groups = listOf(old, new).groupedByDay(TimeZone.UTC)
        assertEquals(listOf("2026-09-15", "2026-09-01"), groups.map { it.day })
    }

    @Test
    fun `an empty list gives no groups`() {
        assertEquals(emptyList(), emptyList<SeriesDto>().groupedByDay(TimeZone.UTC))
    }

    @Test
    fun `encode and decode round trip a filter with calibers and a custom range`() {
        val filter = HistoryFilter(
            calibers = setOf(Caliber.LR22, Caliber.MM9),
            preset = DatePreset.CUSTOM,
            customRange = 123L to 456L,
        )
        assertEquals(filter, decodeHistoryFilter(filter.encode()))
    }

    @Test
    fun `a selected tag keeps only that tag and drops untagged series`() {
        val training = series(1, tag = "träning")
        val competition = series(2, tag = "tävling")
        val untagged = series(3)
        val kept = listOf(training, competition, untagged)
            .filteredBy(HistoryFilter(tags = setOf("träning")), now)
        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `an empty tag set passes everything including untagged series`() {
        val tagged = series(1, tag = "träning")
        val untagged = series(2)
        val kept = listOf(tagged, untagged).filteredBy(HistoryFilter(), now)
        assertEquals(listOf(1L, 2L), kept.map { it.id })
    }

    @Test
    fun `tag and caliber combine with and`() {
        val both = series(1, caliber = "9mm", tag = "träning")
        val wrongCaliber = series(2, caliber = "45", tag = "träning")
        val wrongTag = series(3, caliber = "9mm", tag = "tävling")
        val filter = HistoryFilter(calibers = setOf(Caliber.MM9), tags = setOf("träning"))
        val kept = listOf(both, wrongCaliber, wrongTag).filteredBy(filter, now)
        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `tagsPresent is the distinct tags, sorted, untagged series aside`() {
        val list = listOf(series(1, tag = "tävling"), series(2), series(3, tag = "träning"), series(4, tag = "tävling"))
        assertEquals(listOf("träning", "tävling"), list.tagsPresent())
        assertEquals(emptyList(), listOf(series(1)).tagsPresent())
    }

    @Test
    fun `encode and decode round trip tags, separators included`() {
        // Free text: a tag may hold the ; , = % this encoding separates on.
        val filter = HistoryFilter(tags = setOf("träning", "a;b", "c,d", "e=f", "100%"))
        assertEquals(filter, decodeHistoryFilter(filter.encode()))
    }

    @Test
    fun `a filter string from before tags existed still decodes`() {
        // Exactly what the previous build wrote: no `tags` field at all.
        val old = "calibers=9mm,22lr;preset=CUSTOM;range=123-456"
        assertEquals(
            HistoryFilter(
                calibers = setOf(Caliber.MM9, Caliber.LR22),
                preset = DatePreset.CUSTOM,
                customRange = 123L to 456L,
                tags = emptySet(),
            ),
            decodeHistoryFilter(old),
        )
    }

    @Test
    fun `garbage and null decode to the default filter`() {
        assertEquals(HistoryFilter(), decodeHistoryFilter(null))
        assertEquals(HistoryFilter(), decodeHistoryFilter("not a valid filter at all"))
    }
}
