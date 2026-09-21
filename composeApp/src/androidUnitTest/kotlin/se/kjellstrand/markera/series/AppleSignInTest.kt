package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The state/secret split the deep link rests on: whatever an interceptor catches must be useless
 * without the pre-image, and the backend must be able to recompute it.
 */
class AppleSignInTest {

    @Test
    fun stateIsTheSha256OfTheSecret() {
        // The known digest of "abc".
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }

    @Test
    fun secretsAreLongHexAndNotRepeated() {
        val secret = randomSecret()
        assertTrue(Regex("[0-9a-f]{64}").matches(secret), secret)
        // The backend validates `state` with the same shape.
        assertTrue(Regex("[0-9a-f]{64}").matches(sha256Hex(secret)))
        assertNotEquals(secret, randomSecret())
    }

    @Test
    fun theStartUrlCarriesTheStateAndToleratesATrailingSlash() {
        val state = sha256Hex("abc")
        val expected = "https://markera.example/auth/apple/start?state=$state"
        assertEquals(expected, appleStartUrl("https://markera.example", state))
        assertEquals(expected, appleStartUrl("https://markera.example/", state))
    }
}
