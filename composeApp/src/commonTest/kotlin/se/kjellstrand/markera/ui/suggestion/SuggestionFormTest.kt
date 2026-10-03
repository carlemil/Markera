package se.kjellstrand.markera.ui.suggestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestionFormTest {

    @Test
    fun titleAndDescriptionAreBothRequired() {
        assertNull(suggestionOrNull("", "D", ""))
        assertNull(suggestionOrNull("T", "  ", ""))
        assertNull(suggestionOrNull(" ", "\n", ""))
        assertNotNull(suggestionOrNull("T", "D", ""))
    }

    @Test
    fun theEmailIsOptionalButMustLookLikeOneOnceTyped() {
        assertNull(suggestionOrNull("T", "D", "not-an-address"))
        assertNull(suggestionOrNull("T", "D", "a@b"))
        assertEquals("a@b.se", suggestionOrNull("T", "D", " a@b.se ")?.email)
        assertNull(suggestionOrNull("T", "D", "   ")!!.email)
    }

    @Test
    fun whatIsSentIsTrimmedAndSaysWhichApp() {
        val req = suggestionOrNull("  Dark mode ", "\nPlease\n", "")!!
        assertEquals("Dark mode", req.title)
        assertEquals("Please", req.description)
        assertEquals(appPlatform, req.platform)
        assertEquals(appVersion, req.appVersion)
    }

    @Test
    fun emailShape() {
        assertTrue(isPlausibleEmail("erbsman@gmail.com"))
        assertFalse(isPlausibleEmail("erbs man@gmail.com"))
        assertFalse(isPlausibleEmail("@gmail.com"))
    }
}
