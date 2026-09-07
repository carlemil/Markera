package markera.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Read-only admin pages behind HTTP Basic (user `admin`, [password]). Registered only when
 * `ADMIN_PASSWORD` is set.
 */
fun Route.adminRoutes(db: Db, images: File, password: String) {
    get("/admin") {
        if (unauthorized(password)) return@get
        val rows = db.listUsers().joinToString("") { u ->
            val link = """<a href="/admin/users/${u.id}">"""
            row(
                "$link${u.id}</a>",
                esc(u.provider),
                "$link${esc(u.subject)}</a>",
                esc(u.name.orEmpty()),
                u.seriesCount,
                href = "/admin/users/${u.id}",
            )
        }
        respondHtml(page("Users", table(listOf("id", "provider", "subject", "name", "series"), rows)))
    }

    get("/admin/users/{id}") {
        if (unauthorized(password)) return@get
        val user = db.getUser(pathId()) ?: return@get notFound("Unknown user")
        val rows = db.listSeries(user.id).joinToString("") { s ->
            row(
                """<a href="/admin/series/${s.id}">${s.id}</a>""",
                time(s.timestamp),
                esc(s.caliber),
                s.holes.size,
                s.holes.sumOf { it.ring },
                s.holes.count { kind(it).isNotEmpty() },
                if (imageFile(images, s.id).isFile) "&#10003;" else "",
                href = "/admin/series/${s.id}",
            )
        }
        respondHtml(
            page(
                user.name ?: "${user.provider} / ${user.subject}",
                """<a href="/admin">&larr; users</a>""" +
                    table(listOf("id", "timestamp", "caliber", "holes", "total", "edited", "image"), rows),
            )
        )
    }

    get("/admin/series/{id}") {
        if (unauthorized(password)) return@get
        val seriesId = pathId()
        val series = db.getSeries(seriesId) ?: return@get notFound("Unknown series")
        val userId = db.seriesOwner(seriesId)
        val holes = series.holes.joinToString("") { row(it.x, it.y, it.ring, it.innerTen, it.distanceMm, kind(it)) }
        val image = if (!imageFile(images, seriesId).isFile) "" else {
            """<div class="shot"><img src="/admin/series/$seriesId/image">${markers(series)}</div>"""
        }
        respondHtml(
            page(
                "Series ${series.id}",
                """<a href="/admin/users/$userId">&larr; user $userId</a>""" +
                    "<p>${time(series.timestamp)} &middot; ${esc(series.caliber)} &middot; " +
                    "total ${series.holes.sumOf { it.ring }}</p>" +
                    table(listOf("x", "y", "ring", "innerTen", "distanceMm", "kind"), holes) +
                    image,
            )
        )
    }

    // The one write the admin pages have: the same replace as `PUT /series/{id}`, for any user's series.
    put("/admin/series/{id}") {
        if (unauthorized(password)) return@put
        val seriesId = pathId()
        if (db.seriesOwner(seriesId) == null) return@put notFound("Unknown series")
        val req = call.receive<SeriesRequest>()
        if (invalid(req)) return@put
        db.replaceSeries(seriesId, req)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/admin/series/{id}/image") {
        if (unauthorized(password)) return@get
        val file = imageFile(images, pathId())
        if (file.isFile) call.respondFile(file) else notFound("No image")
    }
}

/**
 * HTTP Basic by hand — ktor-server-auth would be a whole dependency for one password. Responds 401 (and
 * returns true) unless the caller sent exactly `admin:[password]`.
 */
private suspend fun RoutingContext.unauthorized(password: String): Boolean {
    val credentials = call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
        ?.let { runCatching { Base64.getDecoder().decode(it.substring(6).trim()) }.getOrNull() }
    if (credentials != null && MessageDigest.isEqual(credentials, "admin:$password".toByteArray())) return false
    call.response.header(HttpHeaders.WWWAuthenticate, """Basic realm="markera-admin"""")
    call.respondText("admin login required", status = HttpStatusCode.Unauthorized)
    return true
}

/**
 * The holes as absolutely positioned markers over the JPEG. `x`/`y` are pixels of the frame the app scored,
 * and the upload keeps that frame's aspect, so the image fraction places them at any rendered size. Nothing
 * is drawn for series stored before the upload carried the frame size, or for typed holes (no position).
 */
private fun markers(series: Series): String {
    val width = series.imageWidth ?: return ""
    val height = series.imageHeight ?: return ""
    return series.holes.joinToString("") { h ->
        val x = h.x ?: return@joinToString ""
        val y = h.y ?: return@joinToString ""
        val color = if (h.detectedRing == null) "#ffb74d" else "#9ccc65"
        """<div class="hit" style="left:${round2(x / width * 100)}%;top:${round2(y / height * 100)}%;""" +
            """border-color:$color;color:$color">${score(h.ring, h.innerTen)}</div>"""
    }
}

/** Empty for an untouched detection; everything else is training signal (and what the "edited" count counts). */
private fun kind(h: Hole): String {
    val score = when {
        h.detectedRing == null -> if (h.x == null) "typed" else "manual"
        h.ring != h.detectedRing || h.innerTen != (h.detectedInnerTen == true) ->
            "${score(h.detectedRing, h.detectedInnerTen == true)} &rarr; ${score(h.ring, h.innerTen)}"
        else -> ""
    }
    // The user dragged the marker off the detector's position.
    if (h.detectedX == null || (h.x == h.detectedX && h.y == h.detectedY)) return score
    return if (score.isEmpty()) "moved" else "$score, moved"
}

private fun score(ring: Int, innerTen: Boolean) = if (innerTen) "X" else ring.toString()

private fun RoutingContext.pathId() = call.parameters["id"]?.toLongOrNull() ?: -1L

private suspend fun RoutingContext.notFound(what: String) =
    call.respondText(page(what, ""), ContentType.Text.Html, HttpStatusCode.NotFound)

private suspend fun RoutingContext.respondHtml(html: String) = call.respondText(html, ContentType.Text.Html)

private fun esc(value: String) = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * Cells are already-escaped HTML or numbers; every string taken from the database goes through [esc] first.
 * [href] (built from ids only) makes the whole row clickable — the id cell keeps its link for the no-JS case.
 */
private fun row(vararg cells: Any?, href: String? = null) =
    cells.joinToString("", "<tr${href?.let { """ onclick="location.href='$it'"""" }.orEmpty()}>", "</tr>") {
        "<td>${if (it is Double) round2(it) else it ?: ""}</td>"
    }

/** At most two decimals, no trailing zeros: 1.23456 -> 1.23, 4.25 -> 4.25, 8.0 -> 8. */
private fun round2(value: Double) = "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

private val localMinutes = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val sqliteUtc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** ISO instants and SQLite's UTC `datetime('now')` shown as local wall clock; the raw value if unparsable. */
private fun time(value: String): String {
    val instant = runCatching { Instant.parse(value) }
        .recoverCatching { LocalDateTime.parse(value, sqliteUtc).toInstant(ZoneOffset.UTC) }
        .getOrNull() ?: return esc(value)
    return localMinutes.format(instant)
}

private fun table(headers: List<String>, rows: String) =
    "<table><tr>${headers.joinToString("") { "<th>$it</th>" }}</tr>$rows</table>"

private fun page(title: String, body: String) = """<!doctype html>
<html><head><meta charset="utf-8"><title>${esc(title)}</title><style>
body{background:#12160f;color:#e6ead9;font:14px system-ui,sans-serif;margin:24px}
a{color:#9ccc65}
h1{font-size:18px;margin:0 0 12px}
table{border-collapse:collapse;margin-top:12px}
th,td{border:1px solid #35402c;padding:4px 10px;text-align:left}
th{background:#1c2416}
tr[onclick]{cursor:pointer}
tr[onclick]:hover td{background:#1c2416}
img{display:block;max-width:480px;margin-top:12px;border:1px solid #35402c}
.shot{position:relative;display:inline-block}
.hit{position:absolute;transform:translate(-50%,-50%);width:20px;height:20px;border:1px solid;border-radius:50%;
font-size:10px;line-height:20px;text-align:center;text-shadow:0 0 3px #000}
</style></head><body><h1>${esc(title)}</h1>$body</body></html>"""
