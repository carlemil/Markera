package se.kjellstrand.markera.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlinx.datetime.LocalDate
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.ui.markera.ImportQueue
import se.kjellstrand.markera.ui.markera.PickedImage
import se.kjellstrand.markera.ui.stats.InsightsDay

class BackStackTest {
    private val series = SeriesDto(
        id = 42,
        timestamp = "2026-11-20T10:00:00Z",
        caliber = "22lr",
        holes = listOf(HoleDto(500.0, 510.0, 9, false, null), HoleDto(480.0, 490.0, 10, true, null)),
        hasImage = true,
        imageWidth = 3000,
        imageHeight = 3000,
        tag = "träning",
    )
    private val day = InsightsDay(
        date = LocalDate(2026, 11, 20),
        caliber = Caliber("my 9mm", 9.02f),
        from = LocalDate(2026, 10, 1),
    )
    private val anyScope = SaverScope { true }

    @Test
    fun everySavableScreenRoundTrips() {
        val stack = listOf(
            Screen.Home,
            Screen.History,
            Screen.SeriesDetail(series, listOf(42L, 41L, 40L)),
            Screen.StatisticsDay(day),
            Screen.Statistics,
            Screen.FreeMarking,
            Screen.Settings,
            Screen.Suggestion,
        )
        val back = decodeBackStack(encodeBackStack(stack))
        assertEquals(stack, back)
        // A custom caliber keeps its diameter, not only its label.
        assertEquals(9.02f, (back[3] as Screen.StatisticsDay).day.caliber.diameterMm)
    }

    @Test
    fun anImportIsCutWithEverythingAboveIt() {
        val stack = listOf(
            Screen.Home,
            Screen.History,
            Screen.Import(ImportQueue(emptyList<PickedImage>(), auto = false)),
            Screen.Settings,
        )
        assertEquals(listOf(Screen.Home, Screen.History), decodeBackStack(encodeBackStack(stack)))
    }

    @Test
    fun unreadableTextIsHome() {
        assertEquals(listOf(Screen.Home), decodeBackStack(""))
        assertEquals(listOf(Screen.Home), decodeBackStack("not json"))
        assertEquals(listOf(Screen.Home), decodeBackStack("[]"))
        assertEquals(listOf(Screen.Home), decodeBackStack("""[{"name":"history"}]"""))
        assertEquals(listOf(Screen.Home), decodeBackStack("""[{"name":"seriesDetail"}]"""))
    }

    @Test
    fun anUnknownScreenEndsTheStack() {
        assertEquals(
            listOf(Screen.Home, Screen.History),
            decodeBackStack("""[{"name":"home"},{"name":"history"},{"name":"gone"},{"name":"settings"}]"""),
        )
    }

    @Test
    fun saverRebuildsTheStackAfterProcessDeath() {
        val stack = listOf(Screen.Home, Screen.History, Screen.SeriesDetail(series, listOf(42L)))
        val saver = backStackSaver { null }
        val saved = with(saver) { anyScope.save(mutableStateOf(stack)) }!!
        assertEquals(stack, saver.restore(saved)!!.value)
    }

    @Test
    fun saverKeepsTheLiveStackAfterAConfigurationChange() {
        // The live one still holds the import that the text had to drop.
        val live = mutableStateOf(
            listOf(Screen.Home, Screen.Import(ImportQueue(emptyList<PickedImage>(), auto = true))),
        )
        val saver = backStackSaver { live }
        val saved = with(saver) { anyScope.save(live) }!!
        assertSame(live, saver.restore(saved))
    }

    @Test
    fun entryKeysTellDepthAndContentApart() {
        assertNotEquals(backStackKey(1, Screen.Settings), backStackKey(2, Screen.Settings))
        assertNotEquals(
            backStackKey(2, Screen.SeriesDetail(series)),
            backStackKey(2, Screen.SeriesDetail(series.copy(id = 41))),
        )
        assertEquals(backStackKey(1, Screen.History), backStackKey(1, Screen.History))
    }
}
