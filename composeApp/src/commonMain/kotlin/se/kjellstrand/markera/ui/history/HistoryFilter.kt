package se.kjellstrand.markera.ui.history

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.stats.DatePreset
import se.kjellstrand.markera.series.stats.window

/**
 * The Historik screen's own filter state — client-side over the already-loaded
 * [SeriesDto] list. An empty [calibers] set means "every caliber".
 */
data class HistoryFilter(
    val calibers: Set<Caliber> = emptySet(),
    val preset: DatePreset = DatePreset.ALL,
    val customRange: Pair<Long, Long>? = null,
)

/** Keeps [SeriesDto.timestamp] order (newest first); caliber and date combine with AND. */
@OptIn(ExperimentalTime::class)
fun List<SeriesDto>.filteredBy(filter: HistoryFilter, now: Instant): List<SeriesDto> {
    val (from, to) = filter.preset.window(filter.customRange, now)
    return this.filter { series ->
        if (filter.calibers.isNotEmpty() && Caliber.fromLabel(series.caliber) !in filter.calibers) {
            return@filter false
        }
        if (from == null && to == null) return@filter true
        val at = runCatching { Instant.parse(series.timestamp) }.getOrNull() ?: return@filter false
        (from == null || at >= from) && (to == null || at <= to)
    }
}

/** Every caliber on any series, [Caliber.NONE] included, ordinal order. */
fun List<SeriesDto>.calibersPresent(): List<Caliber> =
    map { Caliber.fromLabel(it.caliber) }.distinct().sortedBy { it.ordinal }

/** A simple `key=value;...` encoding, just for [BackendTokenStore][se.kjellstrand.markera.series.BackendTokenStore] persistence. */
fun HistoryFilter.encode(): String {
    val parts = mutableListOf("calibers=${calibers.joinToString(",") { it.label }}", "preset=${preset.name}")
    customRange?.let { parts += "range=${it.first}-${it.second}" }
    return parts.joinToString(";")
}

/** Never throws: null or unparsable text, an unknown caliber/preset, all degrade to defaults. */
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
        )
    } catch (_: Exception) {
        HistoryFilter()
    }
}
