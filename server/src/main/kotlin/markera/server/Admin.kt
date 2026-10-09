package markera.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
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
 * Admin pages behind HTTP Basic (user `admin`, [password]). Registered only when
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
                u.lastUsedAt?.let { time(it) },
                href = "/admin/users/${u.id}",
            )
        }
        val suggestions = db.listSuggestions().size
        respondHtml(
            page(
                "Users",
                """<a href="/admin/suggestions">suggestions ($suggestions)</a>""" +
                    """<label style="margin-left:16px"><input type="checkbox" id="hide-empty"> hide users with 0 series</label>""" +
                    usersTable(listOf("id", "provider", "subject", "name", "series", "last used"), rows) +
                    "<script>$USERS_JS</script>",
            )
        )
    }

    // The suggestion box, newest first: the record of every suggestion, including any whose mail never went out.
    get("/admin/suggestions") {
        if (unauthorized(password)) return@get
        val rows = db.listSuggestions().joinToString("") { s ->
            row(
                s.id,
                time(s.createdAt),
                """<strong>${esc(s.title)}</strong><div class="text">${esc(s.description)}</div>""",
                s.email?.let { """<a href="mailto:${esc(it)}">${esc(it)}</a>""" },
                s.userId?.let { """<a href="/admin/users/$it">${esc(s.userName ?: "user $it")}</a>""" },
                esc(listOfNotNull(s.platform, s.appVersion).joinToString(" ")),
                s.mailedAt?.let { time(it) } ?: "<em>not mailed</em>",
            )
        }
        respondHtml(
            page(
                "Suggestions",
                """<a href="/admin">&larr; users</a>""" +
                    table(listOf("id", "received", "suggestion", "e-mail", "user", "app", "mailed"), rows),
            )
        )
    }

    get("/admin/users/{id}") {
        if (unauthorized(password)) return@get
        val user = db.getUser(pathId()) ?: return@get notFound("Unknown user")
        // Soft-deleted series ride along greyed, so they can be opened and restored.
        val series = db.listSeries(user.id, includeDeleted = true)
        val rows = series.joinToString("") { s ->
            row(
                """<a href="/admin/series/${s.id}">${s.id}</a>""",
                time(s.timestamp),
                esc(s.caliber),
                s.holes.size,
                s.holes.sumOf { it.ring },
                s.holes.count { kind(it).isNotEmpty() },
                if (imageFile(images, s.id).isFile) "&#10003;" else "",
                if (s.deleted) time(s.updatedAt) else "",
                esc(s.tag.orEmpty()),
                href = "/admin/series/${s.id}",
                gone = s.deleted,
            )
        }
        val label = user.name ?: "${user.provider} / ${user.subject}"
        // The count is every row of the table, soft-deleted ones included: the delete takes those too.
        val confirm = "Delete user $label and their ${series.size} series, with every hole and photo? " +
            "This cannot be undone."
        respondHtml(
            page(
                label,
                """<a href="/admin">&larr; users</a>""" +
                    table(listOf("id", "timestamp", "caliber", "holes", "total", "edited", "image", "deleted", "tag"), rows) +
                    """<p><button id="delete-user">Delete user</button> <span id="msg"></span></p>""" +
                    """<script>const MSG = ${jsString(confirm)};$DELETE_USER_JS</script>""",
            )
        )
    }

    // The editor: server-rendered rows and markers, then one inline script mutates them and PUTs the lot back.
    get("/admin/series/{id}") {
        if (unauthorized(password)) return@get
        val seriesId = pathId()
        // Deleted holes ride along greyed out: they are training data, not part of the series' score.
        // A soft-deleted series opens too, but read-only: no editor script, just Restore.
        val series = db.getSeries(seriesId, includeDeleted = true) ?: return@get notFound("Unknown series")
        val userId = db.seriesOwner(seriesId, includeDeleted = true)
        val holes = series.holes.mapIndexed { i, h -> holeRow(i, h, readOnly = h.deleted || series.deleted) }.joinToString("")
        val hasImage = imageFile(images, seriesId).isFile
        val photo = if (!hasImage) "" else {
            """<div class="shot"><img src="/admin/series/$seriesId/image">${geometrySvg(series)}${markers(series)}</div>"""
        }
        // Without the frame size there is nowhere to put a marker, so holes can only be added position-less.
        // With a placeable photo, clicking it is the only way to add a hole — a position-less
        // "Add hole" row next to a placed one just left an empty typed row behind.
        val placeable = hasImage && series.imageWidth != null && series.imageHeight != null
        val add = if (placeable) "" else """<button id="add">Add hole</button> """
        val note = if (placeable || series.deleted) "" else {
            """<p class="note">No photo with a stored frame size, so markers cannot be placed &mdash; """ +
                """"Add hole" adds one without a position.</p>"""
        }
        val caliber = if (series.deleted) esc(series.caliber) else caliberInput(series.caliber)
        val actions = if (series.deleted) """<button id="restore">Restore</button>""" else {
            """$add<button id="undo" disabled>Undo delete</button> <button id="delete" data-user="$userId">Delete</button>"""
        }
        val script = if (series.deleted) "<script>$RESTORE_JS</script>" else {
            """<template id="row">${holeRow(-1, Hole(ring = 0, innerTen = false))}</template>""" +
                """<script type="application/json" id="series">${blob(series.copy(hasImage = hasImage))}""" +
                "</script>" +
                "<script>$EDITOR_JS</script>"
        }
        respondHtml(
            page(
                "Series ${series.id}",
                """<a href="/admin/users/$userId">&larr; user $userId</a>""" +
                    (if (series.deleted) """<p class="gone">Deleted ${time(series.updatedAt)}</p>""" else "") +
                    "<p>${time(series.timestamp)} &middot; $caliber &middot; " +
                    """total <span id="total">${series.holes.filterNot { it.deleted }.sumOf { it.ring }}</span></p>""" +
                    // Table on the left, photo on the right; .cols wraps to a stack on a narrow window.
                    """<div class="cols"><div>""" +
                    table(listOf("x", "y", "detected", "manual", "distanceMm", "kind", ""), holes) +
                    """<p>$actions<span id="msg"></span></p></div><div>""" +
                    photo + note + "</div></div>" +
                    script,
            )
        )
    }

    // Edit any user's series: the same replace as `PUT /series/{id}`.
    put("/admin/series/{id}") {
        if (unauthorized(password) || notFromPage()) return@put
        val seriesId = pathId()
        if (db.seriesOwner(seriesId) == null) return@put notFound("Unknown series")
        val req = receiveSeries() ?: return@put
        if (invalid(req)) return@put
        db.replaceSeries(seriesId, req)
        call.respond(HttpStatusCode.NoContent)
    }

    // Same removal as `DELETE /series/{id}`, for any user's series.
    delete("/admin/series/{id}") {
        if (unauthorized(password) || notFromPage()) return@delete
        val seriesId = pathId()
        if (db.seriesOwner(seriesId) == null) return@delete notFound("Unknown series")
        db.deleteSeries(seriesId)
        call.respond(HttpStatusCode.NoContent)
    }

    // Undoes a soft delete (the app's or the admin page's); the holes and image were kept, so they come back too.
    post("/admin/series/{id}/restore") {
        if (unauthorized(password) || notFromPage()) return@post
        val seriesId = pathId()
        if (db.seriesOwner(seriesId, includeDeleted = true) == null) return@post notFound("Unknown series")
        db.restoreSeries(seriesId)
        call.respond(HttpStatusCode.NoContent)
    }

    // The hard delete, the one the series delete above is not: the same path as `DELETE /account`, so the
    // user, their sessions, every series (soft-deleted ones included), the holes and the JPEGs all go for good.
    delete("/admin/users/{id}") {
        if (unauthorized(password) || notFromPage()) return@delete
        val userId = pathId()
        if (db.getUser(userId) == null) return@delete notFound("Unknown user")
        db.deleteAccount(userId).forEach { imageFile(images, it).delete() }
        call.respond(HttpStatusCode.NoContent)
    }

    get("/admin/series/{id}/image") {
        if (unauthorized(password)) return@get
        val file = imageFile(images, pathId())
        if (!file.isFile) return@get notFound("No image")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondFile(file)
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
 * Every write needs `X-Admin: 1`. The browser's cached Basic credentials ride along on any cross-site form post,
 * but a custom header forces a CORS preflight, which this server never answers, so only the admin page's own
 * `fetch` can send one. Responds 403 (and returns true) without it.
 */
private suspend fun RoutingContext.notFromPage(): Boolean {
    if (call.request.headers["X-Admin"] == "1") return false
    call.respondText("X-Admin header required", status = HttpStatusCode.Forbidden)
    return true
}

/**
 * The holes as absolutely positioned markers over the JPEG. `x`/`y` are pixels of the frame the app scored,
 * and the upload keeps that frame's aspect, so the image fraction places them at any rendered size. Nothing
 * is drawn for series stored before the upload carried the frame size, or for typed holes (no position).
 * `data-i` is the hole's index, which is how the editor script pairs a marker with its table row.
 */
private fun markers(series: Series): String {
    val width = series.imageWidth ?: return ""
    val height = series.imageHeight ?: return ""
    return series.holes.mapIndexed { i, h ->
        val x = h.x ?: return@mapIndexed ""
        val y = h.y ?: return@mapIndexed ""
        val color = when {
            h.deleted -> DELETED_COLOR
            h.detectedRing == null -> "#ffb74d"
            else -> "#9ccc65"
        }
        """<div class="hit${if (h.deleted) " gone" else ""}" data-i="$i" style="left:${round2(x / width * 100)}%;""" +
            """top:${round2(y / height * 100)}%;border-color:$color;color:$color">${score(h.ring, h.innerTen)}</div>"""
    }.joinToString("")
}

/**
 * The scored geometry over the JPEG: the fitted 6/7 ellipse plus a cross at the digit-row centre. The
 * viewBox is the frame the app measured in, so the same fractions place it as the markers; the overlay
 * takes no pointer events, so dragging and clicking holes still reaches the image.
 */
private fun geometrySvg(series: Series): String {
    val g = series.geometry ?: return ""
    val width = series.imageWidth ?: return ""
    val height = series.imageHeight ?: return ""
    val arm = g.ringSemiMajor * 0.06
    return """<svg class="geom" viewBox="0 0 $width $height" preserveAspectRatio="none">""" +
        """<ellipse cx="${round2(g.ringCx)}" cy="${round2(g.ringCy)}" rx="${round2(g.ringSemiMajor)}" """ +
        """ry="${round2(g.ringSemiMinor)}" transform="rotate(${round2(Math.toDegrees(g.ringRotationRad))} """ +
        """${round2(g.ringCx)} ${round2(g.ringCy)})"/>""" +
        """<path d="M${round2(g.centreX - arm)} ${round2(g.centreY)}H${round2(g.centreX + arm)}""" +
        """M${round2(g.centreX)} ${round2(g.centreY - arm)}V${round2(g.centreY + arm)}"/></svg>"""
}

/**
 * One editable hole row. Cell order is fixed — the script addresses x/y/detected/mm/kind by index. A
 * [readOnly] row (a deleted hole, or any hole of a deleted series) is greyed: shown for the record, never edited
 * or re-saved.
 */
private fun holeRow(i: Int, h: Hole, readOnly: Boolean = h.deleted) =
    """<tr data-i="$i"${if (readOnly) """ class="gone"""" else ""}><td>${num(h.x)}</td><td>${num(h.y)}</td>""" +
        """<td${if (overridden(h)) """ class="dim"""" else ""}>${detectedScore(h)}</td>""" +
        """<td>${if (readOnly) score(h.ring, h.innerTen) else manualSelect(h)}</td>""" +
        """<td>${num(h.distanceMm)}</td><td>${kind(h)}</td>""" +
        """<td>${if (readOnly) "" else """<button class="del">Delete</button>"""}</td></tr>"""

/** The marker and row colour of a hole the user deleted in the app. */
private const val DELETED_COLOR = "#8a6fb3"

/** The confirmed score differs from the detected one, i.e. the manual column overrides the detected cell. */
private fun overridden(h: Hole) =
    h.detectedRing != null && (h.ring != h.detectedRing || h.innerTen != (h.detectedInnerTen == true))

private fun detectedScore(h: Hole) = h.detectedRing?.let { score(it, h.detectedInnerTen == true) }.orEmpty()

/**
 * The override: 0..10 plus X (X is ring 10 with innerTen, which is why the two collapsed into one column).
 * A detected hole also gets an empty option — picking it falls back to the detected score. A hole without a
 * detection has nothing to fall back to, so it keeps its score selected and is offered no empty option.
 */
private fun manualSelect(h: Hole): String {
    val chosen = if (h.detectedRing != null && !overridden(h)) null else score(h.ring, h.innerTen)
    val empty = if (h.detectedRing == null) "" else """<option value=""${sel(chosen == null)}></option>"""
    return (0..10).joinToString(
        separator = "",
        prefix = """<select class="manual">$empty""",
        postfix = """<option value="X"${sel(chosen == "X")}>X</option></select>""",
    ) { ring -> """<option value="$ring"${sel(chosen == ring.toString())}>$ring</option>""" }
}

private fun sel(selected: Boolean) = if (selected) " selected" else ""

/** Free text: the server checks only the label's shape, and a bad one comes back as a 400 in `#msg`. */
private fun caliberInput(current: String) = """<input id="caliber" value="${esc(current)}" maxlength="16" size="8">"""

/** Defaults included, so the script sees every field (a missing `innerTen` would read as undefined). */
private val stateJson = Json { encodeDefaults = true }

/**
 * The page's starting state for the script. Every `<` is escaped, so nothing in it can close the script tag or
 * open a `<!--` that changes how the parser reads it; `<` only ever sits inside JSON strings, where `\u003c` means the same.
 */
private fun blob(series: Series) = stateJson.encodeToString(series).replace("<", "\\u003c")

/** A database string as a JS literal: JSON quotes it, and [blob]'s escape keeps it inside its script tag. */
private fun jsString(value: String) = stateJson.encodeToString(value).replace("<", "\\u003c")

/** Empty for an untouched detection; everything else is training signal (and what the "edited" count counts). */
private fun kind(h: Hole): String {
    if (h.deleted) return "deleted"
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
private fun row(vararg cells: Any?, href: String? = null, gone: Boolean = false) =
    cells.joinToString(
        "",
        "<tr${if (gone) """ class="gone"""" else ""}${href?.let { """ onclick="location.href='$it'"""" }.orEmpty()}>",
        "</tr>",
    ) {
        "<td>${if (it is Double) num(it) else it ?: ""}</td>"
    }

/** Millimetres and pixel positions are shown as whole numbers; the extra digits are noise here. */
private fun num(value: Double?) = value?.let { "%.0f".format(Locale.ROOT, it) } ?: ""

/** Marker percentages, where whole numbers would visibly snap the markers to a coarse grid. */
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

/** The users table plus a filter row, one input per column; [USERS_JS] sorts and filters it in the browser. */
private fun usersTable(headers: List<String>, rows: String) =
    """<table id="users"><thead><tr class="sortable">${headers.joinToString("") { "<th>$it</th>" }}</tr>""" +
        """<tr class="filters">${headers.indices.joinToString("") { """<th><input data-col="$it" size="8"></th>""" }}</tr>""" +
        "</thead><tbody>$rows</tbody></table>"

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
img{display:block;max-width:960px}
.cols{display:flex;gap:24px;align-items:flex-start;flex-wrap:wrap}
/* Margin and border live on the box, not the img: markers and the ring are placed as percentages of
   this box, so its padding box must be exactly the image pixels for a click to land where it points. */
.shot{position:relative;display:inline-block;margin-top:12px;border:1px solid #35402c}
.hit{position:absolute;transform:translate(-50%,-50%);width:20px;height:20px;border:1px solid;border-radius:50%;
font-size:13px;line-height:20px;text-align:center;text-shadow:0 0 3px #000;cursor:crosshair;touch-action:none}
.geom{position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;fill:none;stroke:#9ccc65;
stroke-width:1;vector-effect:non-scaling-stroke}
.dim{color:#6f7a63;text-decoration:line-through}
.gone{color:#8a6fb3;font-style:italic}
.hit.gone{border-style:dashed;cursor:default}
select,button,input{font:inherit;background:#1c2416;color:#e6ead9;border:1px solid #35402c;padding:2px 6px}
button{cursor:pointer}
.sortable th{cursor:pointer;user-select:none}
th[data-sort=asc]::after{content:" \25B2"}
th[data-sort=desc]::after{content:" \25BC"}
.text{white-space:pre-wrap;max-width:640px;margin-top:4px}
.note{color:#a8b39a}
#msg{margin-left:8px}
</style></head><body><h1>${esc(title)}</h1>$body</body></html>"""

/**
 * The editor. Server-rendered rows and markers stay put; this only mutates the ones that change, keeping
 * the truth in [S] (the embedded JSON) and PUTting it back. No template literals — Kotlin would eat the `$`.
 */
private const val EDITOR_JS = """
const S = JSON.parse(document.getElementById('series').textContent);
const tbl = document.querySelector('table'), tb = tbl.tBodies[0];
const shot = document.querySelector('.shot'), img = shot ? shot.querySelector('img') : null;
const placeable = !!(shot && S.imageWidth && S.imageHeight);
const sc = (ring, x) => x ? 'X' : String(ring);
const num = v => v == null ? '' : String(Math.round(v));
const total = () => document.getElementById('total').textContent =
    S.holes.reduce((t, h) => t + (h && !h.deleted && !h.removed ? h.ring : 0), 0);
const mark = i => shot ? shot.querySelector('.hit[data-i="' + i + '"]') : null;

// The un-projection from scoreHits (TargetPlane.of in the app): the digit centre's polar line with respect to
// the 6/7 ellipse is the vanishing line; sending it to infinity leaves an ellipse centred on the centre, whose
// quadratic form B measures the point (100 mm = TARGET_BLACK_RING_RADIUS_MM on the rim).
function distanceMm(h) {
  const g = S.geometry;
  if (!g || h.x == null) return null;
  const dx = h.x - g.centreX, dy = h.y - g.centreY;
  const c = Math.cos(g.ringRotationRad), s = Math.sin(g.ringRotationRad);
  const ia = 1 / (g.ringSemiMajor * g.ringSemiMajor), ib = 1 / (g.ringSemiMinor * g.ringSemiMinor);
  const a11 = c * c * ia + s * s * ib, a12 = c * s * (ia - ib), a22 = s * s * ia + c * c * ib;
  const mx = g.ringCx - g.centreX, my = g.ringCy - g.centreY;
  const amx = a11 * mx + a12 * my, amy = a12 * mx + a22 * my;
  const l3 = mx * amx + my * amy - 1;
  if (l3 < -1e-9) {
    const v1 = -amx / l3, v2 = -amy / l3;
    // k = Pinv^T C Pinv with Pinv = [[1,0,0],[0,1,0],[-v1,-v2,1]], C = [[a11,a12,-amx],[a12,a22,-amy],[-amx,-amy,l3]].
    const k11 = a11 - 2 * v1 * -amx + v1 * v1 * l3, k12 = a12 - v2 * -amx - v1 * -amy + v1 * v2 * l3;
    const k22 = a22 - 2 * v2 * -amy + v2 * v2 * l3, f = -l3;
    const w = v1 * dx + v2 * dy + 1, px = dx / w, py = dy / w;
    const q = (k11 * px * px + 2 * k12 * px * py + k22 * py * py) / f;
    if (f > 0 && q >= 0) return Math.sqrt(q) * 100;
  }
  // Fallback, the old weak-perspective model: rotate, stretch the minor component, scale.
  const xR = dx * c + dy * s;
  const yC = (-dx * s + dy * c) * (g.ringSemiMajor / g.ringSemiMinor);
  return Math.sqrt(xR * xR + yC * yC) * (100 / g.ringSemiMajor);
}

function kind(h) {
  let k = '';
  if (h.detectedRing == null) k = h.x == null ? 'typed' : 'manual';
  else if (h.ring !== h.detectedRing || h.innerTen !== (h.detectedInnerTen === true))
    k = sc(h.detectedRing, h.detectedInnerTen === true) + ' → ' + sc(h.ring, h.innerTen);
  if (h.detectedX == null || (h.x === h.detectedX && h.y === h.detectedY)) return k;
  return k ? k + ', moved' : 'moved';
}

function refresh(i) {
  const h = S.holes[i], r = tb.querySelector('tr[data-i="' + i + '"]'), m = mark(i);
  r.cells[0].textContent = num(h.x);
  r.cells[1].textContent = num(h.y);
  r.cells[2].classList.toggle('dim', r.querySelector('select.manual').value !== '');
  r.cells[4].textContent = num(h.distanceMm);
  r.cells[5].textContent = kind(h);
  if (m) {
    m.style.left = (h.x / S.imageWidth * 100) + '%';
    m.style.top = (h.y / S.imageHeight * 100) + '%';
    m.textContent = sc(h.ring, h.innerTen);
  }
  total();
}

function addHole(x, y) {
  const h = {x: x, y: y, ring: 0, innerTen: false, distanceMm: null,
      detectedRing: null, detectedInnerTen: null, detectedX: null, detectedY: null};
  h.distanceMm = distanceMm(h);
  const i = S.holes.push(h) - 1;
  const tr = document.getElementById('row').content.firstElementChild.cloneNode(true);
  tr.dataset.i = i;
  tb.appendChild(tr);
  if (placeable && x != null) {
    const m = document.createElement('div');
    m.className = 'hit';
    m.dataset.i = i;
    m.style.borderColor = m.style.color = '#ffb74d';
    shot.appendChild(m);
  }
  refresh(i);
  save();
}

tbl.addEventListener('change', e => {
  const s = e.target.closest('select.manual');
  if (!s) return;
  const i = s.closest('tr').dataset.i, h = S.holes[i];
  // Empty means "no override": fall back to the detected score, which is the only way the option exists.
  const v = s.value === '' ? sc(h.detectedRing, h.detectedInnerTen === true) : s.value;
  h.innerTen = v === 'X';
  h.ring = h.innerTen ? 10 : Number(v);
  refresh(i);
  save();
});

// Deleting hides the row and marker and flags the hole; Undo unhides in reverse order. The stack
// lives in this page only — a reload starts over with nothing to undo.
const undone = [], undo = document.getElementById('undo');
const setRemoved = (i, removed) => {
  const h = S.holes[i], tr = tb.querySelector('tr[data-i="' + i + '"]'), m = mark(i);
  h.removed = removed;
  tr.hidden = removed;
  if (m) m.hidden = removed;
  undo.disabled = undone.length === 0;
  total();
  save();
};
tbl.addEventListener('click', e => {
  if (!e.target.classList.contains('del')) return;
  const i = e.target.closest('tr').dataset.i;
  undone.push(i);
  setRemoved(i, true);
});
undo.onclick = () => { if (undone.length) setRemoved(undone.pop(), false); };

if (placeable) {
  let drag = null;
  const at = e => {
    const r = img.getBoundingClientRect();
    return [Math.round((e.clientX - r.left) / r.width * S.imageWidth),
            Math.round((e.clientY - r.top) / r.height * S.imageHeight)];
  };
  shot.addEventListener('pointerdown', e => {
    const m = e.target.closest('.hit');
    if (!m || m.classList.contains('gone')) return;
    e.preventDefault();
    m.setPointerCapture(e.pointerId);
    drag = m;
  });
  shot.addEventListener('pointermove', e => {
    if (!drag) return;
    const i = drag.dataset.i, h = S.holes[i], p = at(e);
    h.x = p[0];
    h.y = p[1];
    h.distanceMm = distanceMm(h);   // null without geometry: nothing left to measure against
    refresh(i);
  });
  shot.addEventListener('pointerup', () => { if (drag) save(); drag = null; });
  // A click that is not on a marker drops a new hole there; a drag ends on its own marker, so it is skipped.
  shot.addEventListener('click', e => {
    if (e.target.closest('.hit')) return;
    const p = at(e);
    addHole(p[0], p[1]);
  });
}

const add = document.getElementById('add');
if (add) add.onclick = () => addHole(null, null);

// Every edit saves at once — there is no Save button. The state stays local, so no reload afterwards.
// Each PUT waits for the one before it: two in flight could land out of order and leave the older state stored.
document.getElementById('caliber').onchange = () => save();
let queue = Promise.resolve();
function save() {
  const msg = document.getElementById('msg');
  msg.textContent = 'saving...';
  // Snapshotted now, not when the PUT finally goes: it is this edit's state.
  const body = JSON.stringify({timestamp: S.timestamp, caliber: document.getElementById('caliber').value,
                          geometry: S.geometry,
                          // The editor cannot change the tag, but the PUT replaces the series, so it must carry it back.
                          tag: S.tag,
                          // Removed rows are flagged (Undo brings them back); a positionless hole nobody gave a score is an "Add hole" left behind;
                          // the app's soft-deleted holes stay in the database on their own and must not be re-sent.
                          holes: S.holes.filter(h => h && !h.deleted && !h.removed &&
                                                     !(h.x == null && h.detectedRing == null && h.ring === 0))});
  queue = queue.then(() => fetch(location.pathname, {
    method: 'PUT',
    credentials: 'include',
    headers: {'Content-Type': 'application/json', 'X-Admin': '1'},
    body: body
  }).then(r => r.status === 204 ? msg.textContent = 'saved'
                                : r.text().then(t => msg.textContent = r.status + ' ' + t),
          e => msg.textContent = e));
}

const del = document.getElementById('delete');
del.onclick = () => {
  if (!confirm('Delete series ' + S.id + '?')) return;
  const msg = document.getElementById('msg');
  msg.textContent = 'deleting...';
  fetch(location.pathname, {method: 'DELETE', credentials: 'include', headers: {'X-Admin': '1'}})
    .then(r => r.status === 204 ? location.href = '/admin/users/' + del.dataset.user
                                : r.text().then(t => msg.textContent = r.status + ' ' + t),
          e => msg.textContent = e);
};
"""

/**
 * The user page's one write. `MSG` is the are-you-sure text, server-rendered above this (a `const val`
 * cannot interpolate); there is nothing to reload into afterwards, so it goes back to the list.
 */
private const val DELETE_USER_JS = """
document.getElementById('delete-user').onclick = () => {
  if (!confirm(MSG)) return;
  const msg = document.getElementById('msg');
  msg.textContent = 'deleting...';
  fetch(location.pathname, {method: 'DELETE', credentials: 'include', headers: {'X-Admin': '1'}})
    .then(r => r.status === 204 ? location.href = '/admin'
                                : r.text().then(t => msg.textContent = r.status + ' ' + t),
          e => msg.textContent = e);
};
"""

/** A deleted series' page has no editor, only this: restore, then reload into the live editor. */
private const val RESTORE_JS = """
document.getElementById('restore').onclick = () => {
  const msg = document.getElementById('msg');
  msg.textContent = 'restoring...';
  fetch(location.pathname + '/restore', {method: 'POST', credentials: 'include', headers: {'X-Admin': '1'}})
    .then(r => r.status === 204 ? location.reload()
                                : r.text().then(t => msg.textContent = r.status + ' ' + t),
          e => msg.textContent = e);
};
"""

/**
 * The users page's sort and filter, all in the browser (a handful of users). A header click sorts on that column,
 * again reverses; numbers compare as numbers. Rows only move or hide, so their onclick links keep working.
 * The "hide users with 0 series" checkbox stacks on the column filters and is remembered in localStorage.
 */
private const val USERS_JS = """
const t = document.getElementById('users'), tb = t.tBodies[0], heads = t.tHead.rows[0].cells;
const inputs = Array.from(t.tHead.querySelectorAll('input'));
const text = (r, c) => r.cells[c].textContent.trim();
let col = -1, dir = 1;
Array.from(heads).forEach((h, c) => h.onclick = () => {
  dir = col === c ? -dir : 1;
  col = c;
  Array.from(heads).forEach(o => o.removeAttribute('data-sort'));
  h.dataset.sort = dir > 0 ? 'asc' : 'desc';
  Array.from(tb.rows).sort((a, b) => {
    const x = text(a, c), y = text(b, c);
    const n = x !== '' && y !== '' && !isNaN(x) && !isNaN(y);
    return dir * (n ? x - y : x.localeCompare(y));
  }).forEach(r => tb.appendChild(r));
});
const hide = document.getElementById('hide-empty'), sc = Array.from(heads).findIndex(h => h.textContent === 'series');
const apply = () => {
  const f = inputs.map(i => i.value.trim().toLowerCase());
  Array.from(tb.rows).forEach(r =>
    r.hidden = !f.every((v, c) => !v || text(r, c).toLowerCase().includes(v))
      || (hide.checked && text(r, sc) === '0'));
};
t.tHead.addEventListener('input', apply);
hide.onchange = () => { try { localStorage.hideEmpty = hide.checked ? '1' : '' } catch (e) {} apply(); };
try { hide.checked = localStorage.hideEmpty === '1' } catch (e) {}
apply();
"""
