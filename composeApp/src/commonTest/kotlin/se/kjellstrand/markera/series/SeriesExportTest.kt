package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesExportTest {

    private val geometry = GeometryDto(
        centreX = 100.5,
        centreY = 200.0,
        ringCx = 101.0,
        ringCy = 201.0,
        ringSemiMajor = 300.0,
        ringSemiMinor = 290.0,
        ringRotationRad = 0.25,
    )

    private val series = SeriesDto(
        id = 7,
        timestamp = "2026-09-06T10:00:00Z",
        caliber = "9mm",
        holes = listOf(
            // Detected, inner ten.
            HoleDto(12.0, 34.0, 10, true, 5.5, 10, true, 12.0, 34.0),
            // Typed: no position, no detection — the null cells.
            HoleDto(null, null, 8, false, null),
        ),
        hasImage = true,
        imageWidth = 3000,
        imageHeight = 3000,
        geometry = geometry,
    )

    @Test
    fun seriesCsvHasHeaderAndOneRowPerSeries() {
        assertEquals(
            "id;timestamp;caliber;total;imageWidth;imageHeight;centreX;centreY;" +
                "ringCx;ringCy;ringSemiMajor;ringSemiMinor;ringRotationRad;image\r\n" +
                "7;2026-09-06T10:00:00Z;9mm;18;3000;3000;100.5;200.0;" +
                "101.0;201.0;300.0;290.0;0.25;images/7.jpg\r\n",
            seriesCsv(listOf(series)),
        )
    }

    @Test
    fun holesCsvKeepsNullsEmptyAndMarksInnerTen() {
        assertEquals(
            "seriesId;index;x;y;ring;innerTen;distanceMm;" +
                "detectedRing;detectedInnerTen;detectedX;detectedY\r\n" +
                "7;0;12.0;34.0;10;true;5.5;10;true;12.0;34.0\r\n" +
                "7;1;;;8;false;;;;;\r\n",
            holesCsv(listOf(series)),
        )
    }

    @Test
    fun geometrylessSeriesWithoutImageLeavesThoseCellsEmpty() {
        val bare = SeriesDto(id = 1, timestamp = "t", caliber = "-", holes = emptyList())
        // 14 columns: the 10 after `total` are all empty here.
        assertEquals("1;t;-;0" + ";".repeat(10), seriesCsv(listOf(bare)).lines()[1])
    }

    @Test
    fun aSeparatorOrQuoteInACellIsQuoted() {
        val odd = SeriesDto(id = 2, timestamp = "a;b", caliber = "x\"y", holes = emptyList())
        assertEquals(
            """2;"a;b";"x""y";0""" + ";".repeat(10),
            seriesCsv(listOf(odd)).lines()[1],
        )
    }
}
