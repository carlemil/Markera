package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
    fun theStartUrlCarriesTheStateAsksForTheHttpsReturnAndToleratesATrailingSlash() {
        val state = sha256Hex("abc")
        val expected = "https://markera.example/auth/apple/start?state=$state&return=https"
        assertEquals(expected, appleStartUrl("https://markera.example", state))
        assertEquals(expected, appleStartUrl("https://markera.example/", state))
    }

    // --- Return links (issue #48): the https App Link and the old markera:// one read alike. ---

    private val backend = "https://markera.example"
    private val state = sha256Hex("abc")
    private val nonce = "b".repeat(64)

    private fun parse(link: String?, expected: String? = state) = parseAppleReturn(link, backend, expected)

    @Test
    fun theHttpsAppLinkCarriesTheNonce() {
        assertEquals(AppleReturnLink.Nonce(nonce), parse("https://markera.example/auth/apple/done?state=$state&nonce=$nonce"))
        // Host case does not matter; a backend URL with a path or slash still names the same host.
        assertEquals(AppleReturnLink.Nonce(nonce), parse("https://Markera.Example/auth/apple/done?state=$state&nonce=$nonce"))
        assertEquals(
            AppleReturnLink.Nonce(nonce),
            parseAppleReturn("https://markera.example/auth/apple/done?state=$state&nonce=$nonce", "https://markera.example/", state),
        )
    }

    @Test
    fun theCustomSchemeLinkCarriesTheNonce() {
        assertEquals(AppleReturnLink.Nonce(nonce), parse("markera://auth/apple?state=$state&nonce=$nonce"))
    }

    @Test
    fun aCancelOrAMissingNonceIsACancelOnEitherLink() {
        assertEquals(AppleReturnLink.Cancelled, parse("https://markera.example/auth/apple/done?state=$state&error=cancelled"))
        assertEquals(AppleReturnLink.Cancelled, parse("markera://auth/apple?state=$state&error=cancelled"))
        assertEquals(AppleReturnLink.Cancelled, parse("markera://auth/apple?state=$state"))
    }

    @Test
    fun anotherSignInsStateIsNotOurs() {
        assertNull(parse("https://markera.example/auth/apple/done?state=${sha256Hex("x")}&nonce=$nonce"))
        assertNull(parse("markera://auth/apple?nonce=$nonce"))
        // Nothing armed: nothing matches.
        assertNull(parse("markera://auth/apple?state=$state&nonce=$nonce", expected = null))
    }

    @Test
    fun linksThatAreNotAReturnAreIgnored() {
        assertNull(parse(null))
        assertNull(parse("not a uri at all ::"))
        // The right path on another host, plain http, or another path on the right host.
        assertNull(parse("https://evil.example/auth/apple/done?state=$state&nonce=$nonce"))
        assertNull(parse("http://markera.example/auth/apple/done?state=$state&nonce=$nonce"))
        assertNull(parse("https://markera.example/auth/apple/callback?state=$state&nonce=$nonce"))
        assertNull(parse("markera://other/apple?state=$state&nonce=$nonce"))
    }
}
