package se.kjellstrand.markera.webshooter.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.kjellstrand.markera.webshooter.api.dto.ApiErrorDto
import se.kjellstrand.markera.webshooter.api.dto.ClaimResponse
import se.kjellstrand.markera.webshooter.api.dto.CompetitionsResponse
import se.kjellstrand.markera.webshooter.api.dto.RegistrationResponse
import se.kjellstrand.markera.webshooter.api.dto.ResolveResponse
import se.kjellstrand.markera.webshooter.api.dto.ScoringTargetsResponse
import se.kjellstrand.markera.webshooter.api.dto.StatusResponse
import se.kjellstrand.markera.webshooter.api.dto.SummaryResponse
import se.kjellstrand.markera.webshooter.api.dto.TokenResponse
import se.kjellstrand.markera.webshooter.api.dto.UserResponse

const val WEBSHOOTER_BASE_URL = "https://test.webshooter.se/api/v4.1.9/"

// The password-grant client shipped by the web SPA (public, embedded in its page).
private const val OAUTH_CLIENT_ID = 1
private const val OAUTH_CLIENT_SECRET = "52FphTYzOrmuqH30ltL7LrBzhSEURIJiMFNp6Qt0"

val webshooterJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

fun createWebshooterHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(webshooterJson) }
}

/** Non-2xx response. [error] is the parsed Laravel error body when present. */
class WebshooterApiException(
    val status: Int,
    val error: ApiErrorDto?,
    message: String,
) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401
    val isDuplicateResult: Boolean get() = error?.error == "duplicate_result"
    val isNoActivePatrol: Boolean get() = error?.error == "no_active_patrol"
}

// No default values: kotlinx-serialization omits defaulted fields
// (encodeDefaults=false), which would drop the whole OAuth envelope.
@Serializable
private data class LoginRequest(
    @SerialName("grant_type") val grantType: String,
    @SerialName("client_id") val clientId: Int,
    @SerialName("client_secret") val clientSecret: String,
    val username: String,
    val email: String,
    val password: String,
)

/**
 * Thin typed wrapper over the webshooter endpoints the marking flow uses.
 * Adds the Bearer token from [tokenProvider] to every call; auth retry
 * lives in SessionRepository, not here.
 */
class WebshooterApi(
    private val client: HttpClient,
    private val baseUrl: String = WEBSHOOTER_BASE_URL,
    private val tokenProvider: () -> String? = { null },
) {

    private suspend inline fun <reified T> HttpResponse.parseOrThrow(): T {
        if (status.value in 200..299) return body()
        val text = bodyAsText()
        val error = try {
            webshooterJson.decodeFromString<ApiErrorDto>(text)
        } catch (_: Exception) {
            null
        }
        throw WebshooterApiException(
            status = status.value,
            error = error,
            message = error?.message ?: "HTTP ${status.value} ${text.take(200)}",
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun formBody(params: Map<String, Any?>): TextContent = TextContent(
        laravelFormEncode(params).formUrlEncode(),
        ContentType.Application.FormUrlEncoded,
    )

    suspend fun login(email: String, password: String): TokenResponse =
        client.post(baseUrl + "oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    grantType = "password",
                    clientId = OAUTH_CLIENT_ID,
                    clientSecret = OAUTH_CLIENT_SECRET,
                    username = email,
                    email = email,
                    password = password,
                )
            )
        }.parseOrThrow()

    /** Exchanges the (possibly expired) Bearer token for a fresh one. */
    suspend fun refresh(): TokenResponse =
        client.post(baseUrl + "refresh") { auth() }.parseOrThrow()

    suspend fun currentUser(): UserResponse =
        client.get(baseUrl + "authenticate/user") { auth() }.parseOrThrow()

    suspend fun competitions(
        search: String = "",
        page: Int = 1,
        perPage: Int = 50,
    ): CompetitionsResponse =
        client.get(baseUrl + "competitions") {
            auth()
            parameter("page", page)
            parameter("per_page", perPage)
            if (search.isNotBlank()) parameter("search", search)
        }.parseOrThrow()

    suspend fun scoringTargets(competitionId: Int): ScoringTargetsResponse =
        client.get(baseUrl + "competitions/$competitionId/scoring/targets") { auth() }
            .parseOrThrow()

    suspend fun resolve(guid: String): ResolveResponse =
        client.get(baseUrl + "scoring/resolve/$guid") { auth() }.parseOrThrow()

    suspend fun registration(
        competitionId: Int,
        patrol: Int,
        laneStart: Int,
        laneEnd: Int,
    ): RegistrationResponse =
        client.post(baseUrl + "competitions/$competitionId/admin/results/registration") {
            auth()
            setBody(
                formBody(
                    mapOf(
                        "patrol" to patrol,
                        "patrol_type" to "",
                        "station_start" to 1,
                        "station_end" to 999,
                        "lane_start" to laneStart,
                        "lane_end" to laneEnd,
                    )
                )
            )
        }.parseOrThrow()

    suspend fun status(
        competitionId: Int,
        station: Int,
        patrol: Int,
        laneStart: Int,
        laneEnd: Int,
    ): StatusResponse =
        client.get(baseUrl + "competitions/$competitionId/scoring/status") {
            auth()
            parameter("station", station)
            parameter("patrol", patrol)
            parameter("lane_start", laneStart)
            parameter("lane_end", laneEnd)
        }.parseOrThrow()

    suspend fun claim(competitionId: Int, signupId: Long, station: Int): ClaimResponse =
        client.post(baseUrl + "competitions/$competitionId/scoring/claim") {
            auth()
            setBody(formBody(mapOf("signup_id" to signupId, "station" to station)))
        }.parseOrThrow()

    suspend fun releaseClaim(competitionId: Int, signupId: Long, station: Int) {
        client.delete(baseUrl + "competitions/$competitionId/scoring/claim") {
            auth()
            parameter("signup_id", signupId)
            parameter("station", station)
        }
        // Best-effort like the web client: response intentionally ignored.
    }

    /**
     * Saves one lane's series. [audit] and [results] follow the web client's
     * mobile-v2 payload exactly (Laravel bracket-array form encoding).
     */
    suspend fun saveMobileV2(
        competitionId: Int,
        audit: Map<String, Any?>,
        results: List<Map<String, Any?>>,
    ) {
        client.put(baseUrl + "competitions/$competitionId/admin/results/mobile-v2") {
            auth()
            setBody(formBody(mapOf("audit" to audit, "results" to results)))
        }.parseOrThrow<Unit>()
    }

    suspend fun summary(competitionId: Int, patrol: Int): SummaryResponse =
        client.get(baseUrl + "competitions/$competitionId/scoring/summary") {
            auth()
            parameter("patrol", patrol)
            parameter("patrol_type", "")
        }.parseOrThrow()

    /** Fire-and-forget publish request when advancing to the next series. */
    suspend fun requestPublish(competitionId: Int) {
        client.post(baseUrl + "competitions/$competitionId/scoring/publish") { auth() }
    }
}
