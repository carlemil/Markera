package se.kjellstrand.markera.series

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The two CSVs of the Historik export (PLAN task 58). Semicolon-separated with
 * CRLF ends so Excel on a Swedish machine opens them directly; numbers are
 * Kotlin's own `toString`, i.e. always a decimal point, never a locale comma.
 * Nulls are empty cells.
 */

private const val EOL = "\r\n"

/** RFC-4180 quoting, but only when the cell would otherwise break the row. */
private fun cell(value: Any?): String {
    val s = value?.toString() ?: return ""
    return if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else {
        s
    }
}

private fun row(vararg values: Any?) = values.joinToString(";") { cell(it) } + EOL

/** One row per series; `image` names the entry in the zip, empty when there is none. */
fun seriesCsv(series: List<SeriesDto>): String = buildString {
    append(
        row(
            "id", "timestamp", "caliber", "total", "imageWidth", "imageHeight",
            "centreX", "centreY", "ringCx", "ringCy",
            "ringSemiMajor", "ringSemiMinor", "ringRotationRad", "image",
        ),
    )
    series.forEach { s ->
        val g = s.geometry
        append(
            row(
                s.id, s.timestamp, s.caliber, s.total(), s.imageWidth, s.imageHeight,
                g?.centreX, g?.centreY, g?.ringCx, g?.ringCy,
                g?.ringSemiMajor, g?.ringSemiMinor, g?.ringRotationRad,
                if (s.hasImage) "images/${s.id}.jpg" else "",
            ),
        )
    }
}

/** One row per hole, keyed back to its series by `seriesId` + its index in it. */
fun holesCsv(series: List<SeriesDto>): String = buildString {
    append(
        row(
            "seriesId", "index", "x", "y", "ring", "innerTen", "distanceMm",
            "detectedRing", "detectedInnerTen", "detectedX", "detectedY",
        ),
    )
    series.forEach { s ->
        s.holes.forEachIndexed { i, h ->
            append(
                row(
                    s.id, i, h.x, h.y, h.ring, h.innerTen, h.distanceMm,
                    h.detectedRing, h.detectedInnerTen, h.detectedX, h.detectedY,
                ),
            )
        }
    }
}

/** Excel only reads the CSVs as UTF-8 if they start with a BOM. */
private fun csvBytes(csv: String): ByteArray = "\uFEFF$csv".encodeToByteArray()

/**
 * Everything the app has, as one zip in `cacheDir/export`: `series.csv`,
 * `holes.csv` and `images/<id>.jpg` for every series whose frame the cache can
 * hand over (an image that neither cache nor server gives is skipped silently).
 * Only the newest export is kept — the older ones go before it is written.
 *
 * `Dispatchers.Default`, not IO: kotlinx-io blocks, but common code has no IO.
 */
suspend fun exportSeriesZip(repository: SeriesRepository, cacheDir: Path): Path =
    withContext(Dispatchers.Default) {
        val dir = Path(cacheDir, "export")
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
        val path = Path(dir, "markera-export-${exportStamp()}.zip")
        val series = repository.series.value
        SystemFileSystem.sink(path).buffered().use { sink ->
            val zip = ZipWriter(sink)
            zip.entry("series.csv", csvBytes(seriesCsv(series)))
            zip.entry("holes.csv", csvBytes(holesCsv(series)))
            for (s in series) {
                if (!s.hasImage) continue
                val bytes = repository.image(s.id) ?: continue
                zip.entry("images/${s.id}.jpg", bytes)
            }
            zip.finish()
        }
        path
    }
