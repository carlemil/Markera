package se.kjellstrand.markera.webshooter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import se.kjellstrand.markera.webshooter.api.dto.LenientBoolean
import se.kjellstrand.markera.webshooter.api.webshooterJson

class LenientBooleanSerializerTest {

    @Serializable
    data class Probe(val v: LenientBoolean = false)

    @Test
    fun acceptsBothLiteralAndIntBooleans() {
        assertEquals(false, webshooterJson.decodeFromString<Probe>("""{"v":false}""").v, "literal false")
        assertEquals(true, webshooterJson.decodeFromString<Probe>("""{"v":true}""").v, "literal true")
        assertEquals(false, webshooterJson.decodeFromString<Probe>("""{"v":0}""").v, "int 0")
        assertEquals(true, webshooterJson.decodeFromString<Probe>("""{"v":1}""").v, "int 1")
        assertEquals(false, webshooterJson.decodeFromString<Probe>("""{}""").v, "default")
    }
}
