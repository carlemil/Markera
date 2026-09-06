package markera.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import java.io.File

/**
 * Read-only, unauthenticated admin pages. Registered only when `ADMIN_UI=true`, which must stay
 * off anywhere the server is reachable from outside the LAN.
 */
fun Route.adminRoutes(db: Db, images: File) {
    get("/admin") {
        val rows = db.listUsers().joinToString("") { u ->
            val link = """<a href="/admin/users/${u.id}">"""
            row("$link${u.id}</a>", esc(u.provider), "$link${esc(u.subject)}</a>", esc(u.createdAt), u.seriesCount)
        }
        respondHtml(page("Users", table(listOf("id", "provider", "subject", "created", "series"), rows)))
    }

    get("/admin/users/{id}") {
        val user = db.getUser(pathId()) ?: return@get notFound("Unknown user")
        val rows = db.listSeries(user.id).joinToString("") { s ->
            row(
                """<a href="/admin/series/${s.id}">${s.id}</a>""",
                esc(s.timestamp),
                esc(s.caliber),
                s.holes.size,
                s.holes.sumOf { it.ring },
                if (imageFile(images, s.id).isFile) "&#10003;" else "",
            )
        }
        respondHtml(
            page(
                "${user.provider} / ${user.subject}",
                """<a href="/admin">&larr; users</a>""" +
                    table(listOf("id", "timestamp", "caliber", "holes", "total", "image"), rows),
            )
        )
    }

    get("/admin/series/{id}") {
        val seriesId = pathId()
        val series = db.getSeries(seriesId) ?: return@get notFound("Unknown series")
        val userId = db.seriesOwner(seriesId)
        val holes = series.holes.joinToString("") { row(it.x, it.y, it.ring, it.innerTen, it.distanceMm) }
        val image = if (imageFile(images, seriesId).isFile) """<img src="/admin/series/$seriesId/image">""" else ""
        respondHtml(
            page(
                "Series ${series.id}",
                """<a href="/admin/users/$userId">&larr; user $userId</a>""" +
                    "<p>${esc(series.timestamp)} &middot; ${esc(series.caliber)} &middot; " +
                    "total ${series.holes.sumOf { it.ring }}</p>" +
                    table(listOf("x", "y", "ring", "innerTen", "distanceMm"), holes) +
                    image,
            )
        )
    }

    get("/admin/series/{id}/image") {
        val file = imageFile(images, pathId())
        if (file.isFile) call.respondFile(file) else notFound("No image")
    }
}

private fun RoutingContext.pathId() = call.parameters["id"]?.toLongOrNull() ?: -1L

private suspend fun RoutingContext.notFound(what: String) =
    call.respondText(page(what, ""), ContentType.Text.Html, HttpStatusCode.NotFound)

private suspend fun RoutingContext.respondHtml(html: String) = call.respondText(html, ContentType.Text.Html)

private fun esc(value: String) = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Cells are already-escaped HTML or numbers; every string taken from the database goes through [esc] first. */
private fun row(vararg cells: Any) = cells.joinToString("", "<tr>", "</tr>") { "<td>$it</td>" }

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
img{display:block;max-width:480px;margin-top:12px;border:1px solid #35402c}
</style></head><body><h1>${esc(title)}</h1>$body</body></html>"""
