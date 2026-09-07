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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
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

    // The editor: server-rendered rows and markers, then one inline script mutates them and PUTs the lot back.
    get("/admin/series/{id}") {
        if (unauthorized(password)) return@get
        val seriesId = pathId()
        val series = db.getSeries(seriesId) ?: return@get notFound("Unknown series")
        val userId = db.seriesOwner(seriesId)
        val holes = series.holes.mapIndexed(::holeRow).joinToString("")
        val hasImage = imageFile(images, seriesId).isFile
        val photo = if (!hasImage) "" else {
            """<div class="shot"><img src="/admin/series/$seriesId/image">${geometrySvg(series)}${markers(series)}</div>"""
        }
        // Without the frame size there is nowhere to put a marker, so holes can only be added position-less.
        // With a placeable photo, clicking it is the only way to add a hole — a position-less
        // "Add hole" row next to a placed one just left an empty typed row behind.
        val placeable = hasImage && series.imageWidth != null && series.imageHeight != null
        val add = if (placeable) "" else """<button id="add">Add hole</button> """
        val note = if (placeable) "" else {
            """<p class="note">No photo with a stored frame size, so markers cannot be placed &mdash; """ +
                """"Add hole" adds one without a position.</p>"""
        }
        respondHtml(
            page(
                "Series ${series.id}",
                """<a href="/admin/users/$userId">&larr; user $userId</a>""" +
                    "<p>${time(series.timestamp)} &middot; ${caliberSelect(series.caliber)} &middot; " +
                    """total <span id="total">${series.holes.sumOf { it.ring }}</span></p>""" +
                    // Table on the left, photo on the right; .cols wraps to a stack on a narrow window.
                    """<div class="cols"><div>""" +
                    table(listOf("x", "y", "detected", "manual", "distanceMm", "kind", ""), holes) +
                    """<p>$add<button id="save">Save</button> """ +
                    """<button id="delete" data-user="$userId">Delete</button>""" +
                    """<span id="msg"></span></p></div><div>""" +
                    photo + note + "</div></div>" +
                    """<template id="row">${holeRow(-1, Hole(ring = 0, innerTen = false))}</template>""" +
                    """<script type="application/json" id="series">${blob(series.copy(hasImage = hasImage))}""" +
                    "</script>" +
                    "<script>$EDITOR_JS</script>",
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

    // Same removal as `DELETE /series/{id}`, for any user's series.
    delete("/admin/series/{id}") {
        if (unauthorized(password)) return@delete
        val seriesId = pathId()
        if (db.seriesOwner(seriesId) == null) return@delete notFound("Unknown series")
        db.deleteSeries(seriesId)
        imageFile(images, seriesId).delete()
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
 * `data-i` is the hole's index, which is how the editor script pairs a marker with its table row.
 */
private fun markers(series: Series): String {
    val width = series.imageWidth ?: return ""
    val height = series.imageHeight ?: return ""
    return series.holes.mapIndexed { i, h ->
        val x = h.x ?: return@mapIndexed ""
        val y = h.y ?: return@mapIndexed ""
        val color = if (h.detectedRing == null) "#ffb74d" else "#9ccc65"
        """<div class="hit" data-i="$i" style="left:${round2(x / width * 100)}%;""" +
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

/** One editable hole row. Cell order is fixed — the script addresses x/y/detected/mm/kind by index. */
private fun holeRow(i: Int, h: Hole) =
    """<tr data-i="$i"><td>${num(h.x)}</td><td>${num(h.y)}</td>""" +
        """<td${if (overridden(h)) """ class="dim"""" else ""}>${detectedScore(h)}</td><td>${manualSelect(h)}</td>""" +
        """<td>${num(h.distanceMm)}</td><td>${kind(h)}</td><td><button class="del">Delete</button></td></tr>"""

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

private fun caliberSelect(current: String) = CALIBERS.joinToString("", """<select id="caliber">""", "</select>") {
    """<option${if (it == current) " selected" else ""}>${esc(it)}</option>"""
}

/** Defaults included, so the script sees every field (a missing `innerTen` would read as undefined). */
private val stateJson = Json { encodeDefaults = true }

/** The page's starting state for the script. `</` is escaped so nothing in it can close the script tag. */
private fun blob(series: Series) = stateJson.encodeToString(series).replace("</", "<\\/")

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
select,button{font:inherit;background:#1c2416;color:#e6ead9;border:1px solid #35402c;padding:2px 6px}
button{cursor:pointer}
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
    S.holes.reduce((t, h) => t + (h ? h.ring : 0), 0);
const mark = i => shot ? shot.querySelector('.hit[data-i="' + i + '"]') : null;

// The un-projection from scoreHits (HitScoring.kt): offset from the digit-row centre, rotated by -rotation,
// the minor component stretched back to a circle, scaled by 100 mm / semiMajor (TARGET_BLACK_RING_RADIUS_MM).
function distanceMm(h) {
  const g = S.geometry;
  if (!g || h.x == null) return null;
  const dx = h.x - g.centreX, dy = h.y - g.centreY;
  const c = Math.cos(g.ringRotationRad), s = Math.sin(g.ringRotationRad);
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
});

tbl.addEventListener('click', e => {
  if (!e.target.classList.contains('del')) return;
  const tr = e.target.closest('tr'), m = mark(tr.dataset.i);
  S.holes[tr.dataset.i] = null;
  tr.remove();
  if (m) m.remove();
  total();
});

if (placeable) {
  let drag = null;
  const at = e => {
    const r = img.getBoundingClientRect();
    return [Math.round((e.clientX - r.left) / r.width * S.imageWidth),
            Math.round((e.clientY - r.top) / r.height * S.imageHeight)];
  };
  shot.addEventListener('pointerdown', e => {
    const m = e.target.closest('.hit');
    if (!m) return;
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
  shot.addEventListener('pointerup', () => drag = null);
  // A click that is not on a marker drops a new hole there; a drag ends on its own marker, so it is skipped.
  shot.addEventListener('click', e => {
    if (e.target.closest('.hit')) return;
    const p = at(e);
    addHole(p[0], p[1]);
  });
}

const add = document.getElementById('add');
if (add) add.onclick = () => addHole(null, null);

document.getElementById('save').onclick = () => {
  const msg = document.getElementById('msg');
  msg.textContent = 'saving...';
  fetch(location.pathname, {
    method: 'PUT',
    credentials: 'include',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({timestamp: S.timestamp, caliber: document.getElementById('caliber').value,
                          geometry: S.geometry,
                          // Deleted rows are null; a positionless hole nobody gave a score is an "Add hole" left behind.
                          holes: S.holes.filter(h => h && !(h.x == null && h.detectedRing == null && h.ring === 0))})
  }).then(r => r.status === 204 ? location.reload()
                                : r.text().then(t => msg.textContent = r.status + ' ' + t),
          e => msg.textContent = e);
};

const del = document.getElementById('delete');
del.onclick = () => {
  if (!confirm('Delete series ' + S.id + '?')) return;
  const msg = document.getElementById('msg');
  msg.textContent = 'deleting...';
  fetch(location.pathname, {method: 'DELETE', credentials: 'include'})
    .then(r => r.status === 204 ? location.href = '/admin/users/' + del.dataset.user
                                : r.text().then(t => msg.textContent = r.status + ' ' + t),
          e => msg.textContent = e);
};
"""
