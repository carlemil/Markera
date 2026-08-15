package se.kjellstrand.markera.webshooter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.kjellstrand.markera.webshooter.api.dto.CompetitionsResponse
import se.kjellstrand.markera.webshooter.api.dto.RegistrationResponse
import se.kjellstrand.markera.webshooter.api.dto.ResolveResponse
import se.kjellstrand.markera.webshooter.api.dto.ScoringTargetsResponse
import se.kjellstrand.markera.webshooter.api.dto.StatusResponse
import se.kjellstrand.markera.webshooter.api.dto.UserResponse
import se.kjellstrand.markera.webshooter.api.webshooterJson

/**
 * Parses fixtures captured from the live test.webshooter.se API (2026-08-15,
 * competition 244 "Testa mobilregistrering"), trimmed to a representative
 * subset but structurally unchanged.
 */
class WebshooterDtoParsingTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/webshooter/$name")) {
            "missing fixture /webshooter/$name"
        }.bufferedReader().readText()

    @Test
    fun parsesScoringTargets() {
        val targets = webshooterJson.decodeFromString<ScoringTargetsResponse>(fixture("targets.json"))
        assertEquals(244, targets.competition?.id)
        assertTrue(targets.competition!!.mobileScoringEnabled)
        assertEquals("precision", targets.competition?.resultsType)
        assertEquals(6, targets.markingGroups.size)
        val group = targets.markingGroups.first { it.name == "B-hallen 31-35" }
        assertEquals(31, group.laneStart)
        assertEquals(35, group.laneEnd)
        assertEquals("b0339b4e-1637-4609-ae25-3a0865128964", group.guid)
        assertFalse(group.complete)
        val withLast = targets.markingGroups.first { it.name == "B-hallen 21-25" }
        assertEquals("Henrik Holmgren", withLast.lastMarked?.shooter)
        assertEquals(1, targets.activePatrol?.sortorder)
        // The 46-51 hall has six lanes — nothing may assume five.
        assertEquals(6, targets.markingGroups.first { it.name == "B-hallen 46-51" }.expectedCount)
    }

    @Test
    fun parsesResolve() {
        val resolve = webshooterJson.decodeFromString<ResolveResponse>(fixture("resolve.json"))
        assertEquals("marking_group", resolve.type)
        assertEquals(244, resolve.competitionId)
        assertEquals(listOf(31, 32, 33, 34, 35), resolve.lanes)
        assertEquals(1, resolve.patrolSortorder)
    }

    @Test
    fun parsesRegistrationAndBuildsContext() {
        val registration =
            webshooterJson.decodeFromString<RegistrationResponse>(fixture("registration.json"))
        assertEquals(7, registration.stations.size)
        assertTrue(registration.stations.all { it.shots == 5 })
        assertTrue(registration.patrols.first { it.sortorder == 1 }.mobileScoringActive)
        assertFalse(registration.patrols.first { it.sortorder == 2 }.mobileScoringActive)

        // Lane 21 has a scored result with numeric station_figure_hits.
        val lane21 = registration.signups.getValue("21")!!
        val res = lane21.results.single()
        assertEquals(47, res.points)
        assertEquals(1, res.stationsId)
        assertNotNull(res.scoredAt)
        assertEquals(listOf(10, 10, 9, 9, 9), ShotMapping.shotsToPickers(res.stationFigureHits))

        val ctx = MarkingLogic.buildContext(244, 1, 31, 35, registration)
        assertEquals(listOf(31, 32, 33, 34, 35), ctx.lanes.map { it.lane })
        assertEquals("Roger Örnberg", ctx.lanes.first().signup.user?.fullname)
        assertEquals(0 to 0, MarkingLogic.firstOpenPosition(ctx))
    }

    @Test
    fun parsesStatus() {
        val status = webshooterJson.decodeFromString<StatusResponse>(fixture("status.json"))
        assertEquals(5, status.lanes.size)
        assertEquals(19248L, status.lanes.first { it.lane == 31 }.signupId)
        assertNull(status.lanes.first().result)
        assertNull(status.lanes.first().claim)
    }

    @Test
    fun parsesUserWithIntBoolean() {
        val user = webshooterJson.decodeFromString<UserResponse>(fixture("user.json"))
        assertFalse(user.user.isAdmin) // is_admin arrives as 0/1
        assertEquals("Carl-Emil Kjellstrand", user.user.fullname)
    }

    @Test
    fun parsesCompetitionsPage() {
        val page = webshooterJson.decodeFromString<CompetitionsResponse>(fixture("competitions.json"))
            .competitions
        assertEquals(1, page.currentPage)
        assertEquals(3, page.data.size)
        val comp = page.data.first { it.id == 244 }
        assertEquals("Testa mobilregistrering", comp.name)
        assertEquals("2026-08-29", comp.date)
        assertEquals("precision", comp.resultsType)
        assertFalse(comp.isCompleted)
        // The competition picker hides avslutade competitions.
        val finished = page.data.first { it.id == 239 }
        assertTrue(finished.isCompleted)
        assertEquals(listOf(229, 244), page.data.filterNot { it.isCompleted }.map { it.id })
    }
}
