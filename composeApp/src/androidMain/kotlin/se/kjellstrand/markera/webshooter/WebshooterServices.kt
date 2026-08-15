package se.kjellstrand.markera.webshooter

import android.content.Context
import io.ktor.client.engine.okhttp.OkHttp
import se.kjellstrand.markera.webshooter.api.WebshooterApi
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient
import se.kjellstrand.markera.webshooter.auth.DataStoreTokenStore
import se.kjellstrand.markera.webshooter.auth.SessionRepository

/**
 * Wires the webshooter stack (HTTP client → API → session → scoring repo).
 * One instance for the app; create it in the nav root and pass it down.
 */
class WebshooterServices(context: Context) {

    val sessionRepository: SessionRepository
    val scoringRepository: ScoringRepository

    init {
        val client = createWebshooterHttpClient(OkHttp.create())
        lateinit var session: SessionRepository
        val api = WebshooterApi(client, tokenProvider = { session.currentToken })
        session = SessionRepository(api, DataStoreTokenStore(context))
        sessionRepository = session
        scoringRepository = ScoringRepository(api, session)
    }
}
