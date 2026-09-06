package markera.server

import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Instant

data class Config(
    val port: Int,
    val dbPath: String,
    val googleClientId: String?,
    val appleBundleId: String?,
    val devAuth: Boolean,
) {
    companion object {
        fun fromEnv() = Config(
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            dbPath = System.getenv("DB_PATH")?.ifBlank { null } ?: "./data/markera.db",
            googleClientId = System.getenv("GOOGLE_CLIENT_ID")?.ifBlank { null },
            appleBundleId = System.getenv("APPLE_BUNDLE_ID")?.ifBlank { null },
            devAuth = System.getenv("DEV_AUTH") == "true",
        )
    }
}

val CALIBERS = setOf("-", "22lr", "32", "38", "357", "45", "44", "9mm", "10mm")

@Serializable
data class Hole(val x: Double, val y: Double, val ring: Int, val innerTen: Boolean, val distanceMm: Double)

@Serializable
data class Series(val id: Long, val timestamp: String, val caliber: String, val holes: List<Hole>)

@Serializable
data class SeriesRequest(val timestamp: String, val caliber: String, val holes: List<Hole>)

@Serializable
data class IdTokenRequest(val idToken: String)

@Serializable
data class DevAuthRequest(val subject: String)

@Serializable
data class AuthResponse(val token: String, val userId: Long)

@Serializable
data class IdResponse(val id: Long)

@Serializable
data class ErrorResponse(val error: String)

fun main() {
    val config = Config.fromEnv()
    val db = Db(config.dbPath)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { markeraModule(config, db) }.start(wait = true)
}

fun Application.markeraModule(config: Config, db: Db) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "malformed request"))
        }
    }

    val google = config.googleClientId?.let { googleVerifier(it) }
    val apple = config.appleBundleId?.let { appleVerifier(it) }

    routing {
        get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }

        post("/auth/google") { providerAuth(db, "google", google) }
        post("/auth/apple") { providerAuth(db, "apple", apple) }
        if (config.devAuth) {
            post("/auth/dev") { issueSession(db, "dev", call.receive<DevAuthRequest>().subject) }
        }

        post("/series") {
            val userId = authenticate(db) ?: return@post
            val req = call.receive<SeriesRequest>()
            val error = when {
                req.caliber !in CALIBERS -> "unknown caliber '${req.caliber}'"
                req.holes.isEmpty() -> "holes must not be empty"
                runCatching { Instant.parse(req.timestamp) }.isFailure -> "timestamp must be an ISO-8601 instant"
                else -> null
            }
            if (error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@post
            }
            val id = db.insertSeries(userId, req.timestamp, req.caliber, req.holes)
            call.respond(HttpStatusCode.Created, IdResponse(id))
        }

        get("/series") {
            val userId = authenticate(db) ?: return@get
            call.respond(db.listSeries(userId))
        }
    }
}

/** Resolves the Bearer session token, responding 401 (and returning null) when it is missing or unknown. */
private suspend fun RoutingContext.authenticate(db: Db): Long? {
    val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
    val userId = token?.takeIf { it.isNotEmpty() }?.let { db.userForToken(it) }
    if (userId == null) call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid session token"))
    return userId
}

private suspend fun RoutingContext.providerAuth(db: Db, provider: String, verifier: TokenVerifier?) {
    if (verifier == null) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("$provider sign-in is not configured"))
        return
    }
    val subject = try {
        verifier.subject(call.receive<IdTokenRequest>().idToken)
    } catch (e: JWTVerificationException) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message ?: "invalid id token"))
        return
    }
    issueSession(db, provider, subject)
}

private suspend fun RoutingContext.issueSession(db: Db, provider: String, subject: String) {
    val userId = db.upsertUser(provider, subject)
    call.respond(AuthResponse(db.createSession(userId), userId))
}
