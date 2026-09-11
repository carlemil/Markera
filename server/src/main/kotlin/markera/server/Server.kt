package markera.server

import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

data class Config(
    val port: Int,
    val dbPath: String,
    val googleClientId: String?,
    val appleBundleId: String?,
    val devAuth: Boolean,
    /** JPEG snapshots live here as `<seriesId>.jpg`; defaults next to the database so Docker's volume holds both. */
    val imagesDir: String = defaultImagesDir(dbPath),
    /** Password for the read-only `/admin` pages (HTTP Basic, user `admin`); blank/null leaves them unregistered. */
    val adminPassword: String? = null,
    /** Shown on the public `/delete-account` and `/privacy` pages as the contact address; null hides it. */
    val contactEmail: String? = null,
) {
    companion object {
        fun defaultImagesDir(dbPath: String) = File(File(dbPath).absoluteFile.parentFile, "images").path

        fun fromEnv(): Config {
            val dbPath = System.getenv("DB_PATH")?.ifBlank { null } ?: "./data/markera.db"
            return Config(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                dbPath = dbPath,
                googleClientId = System.getenv("GOOGLE_CLIENT_ID")?.ifBlank { null },
                appleBundleId = System.getenv("APPLE_BUNDLE_ID")?.ifBlank { null },
                devAuth = System.getenv("DEV_AUTH") == "true",
                imagesDir = System.getenv("IMAGES_DIR")?.ifBlank { null } ?: defaultImagesDir(dbPath),
                adminPassword = System.getenv("ADMIN_PASSWORD")?.ifBlank { null },
                contactEmail = System.getenv("CONTACT_EMAIL")?.ifBlank { null },
            )
        }
    }
}

// A ~3000² q90 target photo is ~2 MB; this is headroom for a bigger sensor.
const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

val CALIBERS = setOf("-", "22lr", "32", "38", "357", "45", "44", "9mm", "10mm")

/**
 * One shot. [ring]/[innerTen] is what the user confirmed; [detectedRing]/[detectedInnerTen] what the
 * detector said (null when there was no detection). Kinds are derived, never stored: detected =
 * `detectedRing != null`, manual = placed by hand on the photo (`x != null`, no detection), typed =
 * a score keyed into an empty picker slot (`x == null`).
 */
@Serializable
data class Hole(
    // Defaults on every nullable: the app omits nulls instead of sending them.
    val x: Double? = null,
    val y: Double? = null,
    val ring: Int,
    val innerTen: Boolean,
    val distanceMm: Double? = null,
    val detectedRing: Int? = null,
    val detectedInnerTen: Boolean? = null,
    /** Where the detector put the hole; [x]/[y] differing from it means the user dragged the marker. */
    val detectedX: Double? = null,
    val detectedY: Double? = null,
    /** Removed by the user: stored for training, hidden from every read (the app never sees it again). */
    val deleted: Boolean = false,
)

/**
 * The target geometry the scan was scored against, in the same pixel frame as the hole positions.
 * `centre` is the digit-row intersection (the true centre); the ring is the fitted 6/7 black-edge
 * ellipse, whose semi-major axis is 100 mm on the target. Null for series saved before the app sent it.
 */
@Serializable
data class Geometry(
    val centreX: Double,
    val centreY: Double,
    val ringCx: Double,
    val ringCy: Double,
    val ringSemiMajor: Double,
    val ringSemiMinor: Double,
    val ringRotationRad: Double,
)

@Serializable
data class Series(
    val id: Long,
    val timestamp: String,
    val caliber: String,
    val holes: List<Hole>,
    val hasImage: Boolean = false,
    /** Size of the frame the holes were measured in, sent with the image; null for series uploaded before that. */
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val geometry: Geometry? = null,
    /** When the series last changed, in the format `GET /series?since=` takes back. */
    val updatedAt: String = "",
    /** A tombstone from the delta: the series is gone, and only [id] and [updatedAt] are filled in. */
    val deleted: Boolean = false,
)

@Serializable
data class SeriesRequest(
    val timestamp: String,
    val caliber: String,
    val holes: List<Hole>,
    val geometry: Geometry? = null,
)

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

    val images = File(config.imagesDir).also { it.mkdirs() }
    val google = config.googleClientId?.let { googleVerifier(it) }
    val apple = config.appleBundleId?.let { appleVerifier(it) }

    routing {
        get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }

        // Public, unauthenticated: the account-deletion instructions Google Play requires a URL for.
        get("/delete-account") { call.respondText(deleteAccountPage(config.contactEmail), ContentType.Text.Html) }
        get("/privacy") { call.respondText(privacyPage(config.contactEmail), ContentType.Text.Html) }

        post("/auth/google") { providerAuth(db, "google", google) }
        post("/auth/apple") { providerAuth(db, "apple", apple) }
        if (config.devAuth) {
            post("/auth/dev") {
                val subject = call.receive<DevAuthRequest>().subject
                issueSession(db, "dev", Identity(subject, subject))
            }
        }

        post("/series") {
            val userId = authenticate(db) ?: return@post
            val req = call.receive<SeriesRequest>()
            if (invalid(req)) return@post
            val id = db.insertSeries(userId, req.timestamp, req.caliber, req.holes, req.geometry)
            call.respond(HttpStatusCode.Created, IdResponse(id))
        }

        // Editing a saved series: same body as POST, replaces timestamp, caliber and every hole.
        put("/series/{id}") {
            val seriesId = ownedSeries(db) ?: return@put
            val req = call.receive<SeriesRequest>()
            if (invalid(req)) return@put
            db.replaceSeries(seriesId, req)
            call.respond(HttpStatusCode.NoContent)
        }

        // `since` = the sync delta: everything changed at or after that `updatedAt`, tombstones included and
        // unpaged, so `limit`/`before` are ignored with it. Without it the live series, newest id first, paged
        // by `limit` (default 50, clamped 1..200) and `before` = the last id of the previous page.
        get("/series") {
            val userId = authenticate(db) ?: return@get
            val since = call.request.queryParameters["since"]
            val series = if (since != null) db.seriesSince(userId, since) else {
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                db.listSeries(userId, limit, call.request.queryParameters["before"]?.toLongOrNull())
            }
            call.respond(series.map { if (it.deleted) it else it.copy(hasImage = imageFile(images, it.id).isFile) })
        }

        delete("/series/{id}") {
            val seriesId = ownedSeries(db) ?: return@delete
            db.deleteSeries(seriesId)
            imageFile(images, seriesId).delete()
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/account") {
            val userId = authenticate(db) ?: return@delete
            db.deleteAccount(userId).forEach { imageFile(images, it).delete() }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/series/{id}/image") {
            val seriesId = ownedSeries(db) ?: return@post
            if (call.request.contentType().withoutParameters() != ContentType.Image.JPEG) {
                call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("expected image/jpeg"))
                return@post
            }
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_IMAGE_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("image must be at most $MAX_IMAGE_BYTES bytes"))
                return@post
            }
            val bytes = call.receiveChannel().readRemaining(MAX_IMAGE_BYTES + 1L).readByteArray()
            if (bytes.size > MAX_IMAGE_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("image must be at most $MAX_IMAGE_BYTES bytes"))
                return@post
            }
            val target = imageFile(images, seriesId)
            val tmp = File(images, "$seriesId.jpg.tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            // ?width=&height= is the size of the frame the hole coordinates were measured in, so the admin
            // page can place markers on the (downscaled but same-aspect) JPEG. Nonsense values are ignored.
            val width = call.request.queryParameters["width"]?.toIntOrNull()?.takeIf { it > 0 }
            val height = call.request.queryParameters["height"]?.toIntOrNull()?.takeIf { it > 0 }
            db.imageStored(seriesId, width, height)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/series/{id}/image") {
            val seriesId = ownedSeries(db) ?: return@get
            val file = imageFile(images, seriesId)
            if (!file.isFile) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no image"))
                return@get
            }
            call.respondFile(file)
        }

        config.adminPassword?.takeIf { it.isNotBlank() }?.let { adminRoutes(db, images, it) }
    }
}

internal fun imageFile(imagesDir: File, seriesId: Long) = File(imagesDir, "$seriesId.jpg")

/** Responds 400 (and returns true) for a series body that cannot be stored. */
internal suspend fun RoutingContext.invalid(req: SeriesRequest): Boolean {
    val error = when {
        req.caliber !in CALIBERS -> "unknown caliber '${req.caliber}'"
        req.holes.isEmpty() -> "holes must not be empty"
        runCatching { Instant.parse(req.timestamp) }.isFailure -> "timestamp must be an ISO-8601 instant"
        req.geometry?.let { it.ringSemiMajor <= 0 || it.ringSemiMinor <= 0 } == true ->
            "ring semi-axes must be positive"
        else -> return false
    }
    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
    return true
}

/** Resolves `{id}` for the authenticated caller, responding 401/404 (and returning null) when it is not theirs. */
private suspend fun RoutingContext.ownedSeries(db: Db): Long? {
    val userId = authenticate(db) ?: return null
    val seriesId = call.parameters["id"]?.toLongOrNull()
    if (seriesId == null || db.seriesOwner(seriesId) != userId) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown series"))
        return null
    }
    return seriesId
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
    val identity = try {
        verifier.identity(call.receive<IdTokenRequest>().idToken)
    } catch (e: JWTVerificationException) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message ?: "invalid id token"))
        return
    }
    issueSession(db, provider, identity)
}

private suspend fun RoutingContext.issueSession(db: Db, provider: String, identity: Identity) {
    val userId = db.upsertUser(provider, identity.subject, identity.name)
    call.respond(AuthResponse(db.createSession(userId), userId))
}
