package se.kjellstrand.markera.ui

import kotlinx.coroutines.CancellationException
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesApiException
import se.kjellstrand.markera.series.SignInCancelledException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserErrorTest {
    @Test
    fun mapsFailuresToLocalisedMessages() {
        assertEquals(Res.string.error_session_expired, SeriesApiException(401, "x").userMessage())
        assertEquals(Res.string.error_server, SeriesApiException(500, "x").userMessage())
        assertEquals(Res.string.error_network, kotlinx.io.IOException("x").userMessage())
        assertEquals(Res.string.error_generic, IllegalStateException("x").userMessage())
        assertNull(SignInCancelledException().userMessage())
        assertNull(CancellationException("x").userMessage())
    }
}
