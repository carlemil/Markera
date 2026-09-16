package se.kjellstrand.markera.ui.history

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.stats.DatePreset
import se.kjellstrand.markera.series.stats.window

/**
 * The Historik screen's own filter state — client-side over the already-loaded
 * [SeriesDto] list. An empty [calibers] set means "every caliber", an empty
 * [tags] set "every tag" (untagged series included).
 */
data class HistoryFilter(
    val calibers: Set<Caliber> = emptySet(),
    val preset: DatePreset = DatePreset.ALL,
    val customRange: Pair<Long, Long>? = null,
    val tags: Set<String> = emptySet(),
)

/** Keeps [SeriesDto.timestamp] order (newest first); caliber, tag and date combine with AND. */
@OptIn(ExperimentalTime::class)
fun List<SeriesDto>.filteredBy(filter: HistoryFilter, now: Instant): List<SeriesDto> {
    val (from, to) = filter.preset.window(filter.customRange, now)
    return this.filter { series ->
        if (filter.calibers.isNotEmpty() && Caliber.fromLabel(series.caliber) !in filter.calibers) {
            return@filter false
        }
        if (filter.tags.isNotEmpty() && series.tag !in filter.tags) return@filter false
        if (from == null && to == null) return@filter true
        val at = runCatching { Instant.parse(series.timestamp) }.getOrNull() ?: return@filter false
        (from == null || at >= from) && (to == null || at <= to)
    }
}

/** One day's worth of series in the Historik list, in the order the list had them. */
data class DayGroup(val day: String, val series: List<SeriesDto>)

/**
 * Buckets by local date (`yyyy-MM-dd`), newest day first. The key is [localStamp]'s
 * date half, so an unparsable timestamp groups under its own raw text instead of
 * vanishing.
 */
fun List<SeriesDto>.groupedByDay(zone: TimeZone = TimeZone.currentSystemDefault()): List<DayGroup> =
    groupBy { localStamp(it.timestamp, zone).take(10) }
        .map { (day, series) -> DayGroup(day, series) }
        .sortedByDescending { it.day }

/** Every caliber on any series, [Caliber.NONE] included, ordinal order. */
fun List<SeriesDto>.calibersPresent(): List<Caliber> =
    map { Caliber.fromLabel(it.caliber) }.distinct().sortedBy { it.ordinal }

/** Every tag any series carries, alphabetical. An untagged series contributes none. */
fun List<SeriesDto>.tagsPresent(): List<String> = mapNotNull { it.tag }.distinct().sorted()

/**
 * A tag is free text, so it can contain the very characters this encoding separates
 * on. Percent-escape them (and `%` itself) rather than lose such a filter; `%` last
 * on the way out, so an escaped literal `%25` doesn't unescape twice.
 */
private fun escapeTag(tag: String): String = tag
    .replace("%", "%25").replace(";", "%3B").replace(",", "%2C").replace("=", "%3D")

private fun unescapeTag(text: String): String = text
    .replace("%3B", ";").replace("%2C", ",").replace("%3D", "=").replace("%25", "%")

/** A simple `key=value;...` encoding, just for [BackendTokenStore][se.kjellstrand.markera.series.BackendTokenStore] persistence. */
fun HistoryFilter.encode(): String {
    val parts = mutableListOf(
        "calibers=${calibers.joinToString(",") { it.label }}",
        "tags=${tags.joinToString(",") { escapeTag(it) }}",
        "preset=${preset.name}",
    )
    customRange?.let { parts += "range=${it.first}-${it.second}" }
    return parts.joinToString(";")
}

/**
 * Never throws: null or unparsable text, an unknown caliber/preset, all degrade to
 * defaults. Fields are looked up by key, so a string written before `tags` existed
 * keeps its calibers and dates and simply means "no tag filter".
 */
fun decodeHistoryFilter(text: String?): HistoryFilter {
    if (text == null) return HistoryFilter()
    return try {
        val fields = text.split(";").associate { field ->
            val (k, v) = field.split("=", limit = 2)
            k to v
        }
        val calibers = fields["calibers"]
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { label -> Caliber.entries.firstOrNull { it.label == label } }
            ?.toSet()
            ?: emptySet()
        val tags = fields["tags"]
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.map { unescapeTag(it) }
            ?.toSet()
            ?: emptySet()
        val range = fields["range"]
            ?.split("-")
            ?.takeIf { it.size == 2 }
            ?.let { (a, b) -> a.toLongOrNull()?.let { start -> b.toLongOrNull()?.let { end -> start to end } } }
        val preset = fields["preset"]?.let { name -> DatePreset.entries.firstOrNull { it.name == name } }
            ?: DatePreset.ALL
        HistoryFilter(
            calibers = calibers,
            preset = if (preset == DatePreset.CUSTOM && range == null) DatePreset.ALL else preset,
            customRange = range,
            tags = tags,
        )
    } catch (_: Exception) {
        HistoryFilter()
    }
}
