package se.kjellstrand.markera.series

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import se.kjellstrand.markera.webshooter.api.dto.ApiErrorDto
import se.kjellstrand.markera.webshooter.api.webshooterJson

/** Non-2xx from the Markera backend; [message] is the server's `{"error": ...}` when present. */
class SeriesApiException(val status: Int, message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401
}

// No default values: kotlinx-serialization omits defaulted fields.
@Serializable
private data class IdTokenRequest(val idToken: String)

@Serializable
private data class DevAuthRequest(val subject: String)

@Serializable
private data class IdResponse(val id: Long)

/**
 * Typed client for the Markera series backend (`server/`). Adds the Bearer
 * session token from [tokenProvider] to everything but the auth endpoints.
 */
class SeriesApi(
    private val client: HttpClient,
    baseUrl: String,
    private val tokenProvider: () -> String? = { null },
) {
    private val base = baseUrl.trimEnd('/')

    private suspend inline fun <reified T> HttpResponse.parseOrThrow(): T {
        throwIfError()
        return body()
    }

    private suspend fun HttpResponse.throwIfError() {
        if (status.value in 200..299) return
        val text = bodyAsText()
        val error = try {
            webshooterJson.decodeFromString<ApiErrorDto>(text).error
        } catch (_: Exception) {
            null
        }
        throw SeriesApiException(status.value, error ?: "HTTP ${status.value} ${text.take(200)}")
    }

    private fun HttpRequestBuilder.auth() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend inline fun <reified B> postJson(
        path: String,
        body: B,
        authorized: Boolean,
    ): HttpResponse = client.post("$base$path") {
        if (authorized) auth()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun authGoogle(idToken: String): BackendAuthResponse =
        postJson("/auth/google", IdTokenRequest(idToken), authorized = false).parseOrThrow()

    suspend fun authApple(idToken: String): BackendAuthResponse =
        postJson("/auth/apple", IdTokenRequest(idToken), authorized = false).parseOrThrow()

    suspend fun authDev(subject: String): BackendAuthResponse =
        postJson("/auth/dev", DevAuthRequest(subject), authorized = false).parseOrThrow()

    /** @return the id the server assigned the stored series. */
    suspend fun postSeries(req: SeriesRequest): Long =
        postJson("/series", req, authorized = true).parseOrThrow<IdResponse>().id

    /** Newest first, at most [limit]; [before] pages backwards (only ids below it). */
    suspend fun listSeries(limit: Int = SERIES_PAGE_SIZE, before: Long? = null): List<SeriesDto> =
        client.get("$base/series") {
            auth()
            parameter("limit", limit)
            before?.let { parameter("before", it) }
        }.parseOrThrow()

    /**
     * The sync delta: every series changed at or after [since] (inclusive, so
     * the caller dedupes by id), deleted ones as tombstones (`deleted = true`).
     * [since] is the server's own `updatedAt` stamp, passed back verbatim.
     */
    suspend fun listSeriesSince(since: String): List<SeriesDto> =
        client.get("$base/series") {
            auth()
            parameter("since", since)
        }.parseOrThrow()

    /** Replaces the series' timestamp, caliber and holes (the detail screen's save). */
    suspend fun updateSeries(id: Long, req: SeriesRequest) {
        client.put("$base/series/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(req)
        }.throwIfError()
    }

    suspend fun deleteSeries(id: Long) {
        client.delete("$base/series/$id") { auth() }.throwIfError()
    }

    /** Wipes the caller's account server-side; the session token stops working. */
    suspend fun deleteAccount() {
        client.delete("$base/account") { auth() }.throwIfError()
    }

    /**
     * The scanned snapshot for a saved series — a raw JPEG body, no multipart.
     * [width]/[height] are the source-pixel size the hole coordinates are in, so
     * the server can scale the markers onto the (possibly downscaled) image.
     */
    suspend fun postSeriesImage(id: Long, jpeg: ByteArray, width: Int, height: Int) {
        client.post("$base/series/$id/image") {
            auth()
            parameter("width", width)
            parameter("height", height)
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }.throwIfError()
    }

    suspend fun getSeriesImage(id: Long): ByteArray =
        client.get("$base/series/$id/image") { auth() }.parseOrThrow()
}
