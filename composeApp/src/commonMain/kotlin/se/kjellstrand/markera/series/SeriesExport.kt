package se.kjellstrand.markera.series

/**
 * The two CSVs of the Historik export (PLAN task 58). Semicolon-separated with
 * CRLF ends so Excel on a Swedish machine opens them directly; numbers are
 * Kotlin's own `toString`, i.e. always a decimal point, never a locale comma.
 * Nulls are empty cells. The zip that carries them is Android-side.
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
