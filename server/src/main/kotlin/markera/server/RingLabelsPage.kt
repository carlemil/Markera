package markera.server

/*
 * /admin/ring-labels: the outer page around the editor's ring mode (see `ringEditor` in Admin.kt). It picks a random
 * series, Save stores the hand-corrected 6/7 ring and centre in `ring_labels` (never the series), Skip moves on.
 * Curate's twin; the list and frame styles are curate's own.
 */

/**
 * The page's frame: Save and Skip beside the title, the overlay transparency slider, then the editor in ring mode.
 * The labelled list lives in the editor, under its note; with no series open it docks on the left.
 */
internal const val RING_BODY = """<style>
body>h1{display:inline-block;margin:0 16px 8px 0;vertical-align:middle}
.bar{display:inline-flex;gap:8px;align-items:center;flex-wrap:wrap;vertical-align:middle;margin-bottom:8px}
.ovrow{display:flex;gap:8px;align-items:center;margin-bottom:6px}
.ovrow input[type=range]{width:200px}
.curate{display:flex;gap:16px;align-items:flex-start;margin-top:8px}
.curate main{flex:1;min-width:0}
#save{border-color:#9ccc65;color:#9ccc65}
a.button{display:inline-block;padding:2px 10px;border:1px solid #9ccc65;border-radius:3px;color:#9ccc65;text-decoration:none}
#frame{display:block;width:100%;height:calc(100vh - 170px);border:1px solid #35402c;background:#12160f}
kbd{border:1px solid #35402c;border-radius:3px;padding:0 4px;font-size:12px}
</style><div class="bar"><button id="save">Save <kbd>A</kbd></button>
<button id="skip">Skip <kbd>S</kbd></button><span id="current"></span><span id="remaining"></span><span id="cmsg"></span></div>
<label class="ovrow">Overlay transparency <input type="range" id="ov" min="0" max="100" step="5" value="0"><span id="ovv">0%</span></label>
<div class="bar">{EXPORT}<span class="note">The whole set every time (photos, labels.json, README.md); the same URL
works for a script with the admin login. Labels go to their own table: the series, its holes and everything the app
reads stay as they are.</span></div>
<div><a href="/admin">&larr; users</a></div>
<div class="curate"><aside id="dock"><div id="side"><div id="counts"></div><ul id="history"></ul></div></aside><main>
<p id="none" hidden>Every series with a photo is labelled or was skipped. Pick one on the left to correct its label.</p>
<iframe id="frame" title="ring editor"></iframe></main></div>"""

/**
 * The page's script: Save reads the frame's ring (`ringGeometry`), POSTs it to /admin/ring-labels/{id} and moves on
 * to a fresh random series; Skip stores the skip and moves on. No dollar sign anywhere: Kotlin would eat it.
 */
internal const val RING_JS = """
const frame = document.getElementById('frame'), hist = document.getElementById('history');
const cur = document.getElementById('current'), msg = document.getElementById('cmsg');
const side = document.getElementById('side'), counts = document.getElementById('counts'), dock = document.getElementById('dock');
const ov = document.getElementById('ov'), ovv = document.getElementById('ovv');
const get = url => fetch(url, {credentials: 'include'}).then(r => { if (!r.ok) throw new Error(r.status + ' ' + url); return r.json(); });
const noContent = r => { if (r.status !== 204) return r.text().then(t => { throw new Error(r.status + ' ' + t); }); };
const fail = e => msg.textContent = '· ' + (e.message || e);
let current = null, busy = false, labelledN = 0, skippedN = 0, remainingN = null;

function show(id, at) {
  current = id;
  history.replaceState(null, '', id == null ? location.pathname : '?id=' + id);
  frame.hidden = id == null;
  document.getElementById('none').hidden = id != null;
  frame.src = id == null ? 'about:blank' : '/admin/series/' + id + '?embed=1&ring=1';
  cur.textContent = id == null ? '' : 'Series #' + id + ' — ' + (at ? 'labelled ' + at : 'not labelled');
  document.getElementById('save').disabled = id == null;
  // Skip is for the random pick; a series opened from the list keeps its label until it is saved again.
  document.getElementById('skip').disabled = id == null || at != null;
  markActive();
}

function renderCounts() {
  counts.textContent = labelledN + ' labelled' + (remainingN == null ? '' : ', ' + remainingN + ' left') +
    (skippedN ? ', ' + skippedN + ' skipped' : '');
}
function remaining(j) {
  document.getElementById('remaining').textContent = '· ' + j.remaining + ' left';
  remainingN = j.remaining;
  skippedN = j.skipped || 0;
  labelledN = j.labelled;
  renderCounts();
}
function next(exclude) {
  return get('/admin/ring-labels/next' + (exclude != null ? '?exclude=' + exclude : '')).then(j => { remaining(j); show(j.id, null); });
}

function loadHistory() {
  return get('/admin/ring-labels/history').then(list => {
    hist.textContent = '';
    list.forEach(c => {
      const li = document.createElement('li');
      li.dataset.id = c.id;
      li.dataset.at = c.at;
      li.className = 'approved' + (c.deleted ? ' gone' : '');
      const b = document.createElement('b');
      b.textContent = '✓ ';
      li.append(b, '#' + c.id + '  ' + c.at);
      hist.appendChild(li);
    });
    markActive();
  });
}
function markActive() {
  Array.from(hist.children).forEach(li => li.classList.toggle('active', li.dataset.id === String(current)));
}

// The editor's ring as it stands (S.geometry in the frame); null while the series has none and nothing was dragged.
function ringOf() {
  try { const w = frame.contentWindow; return w && w.ringGeometry ? w.ringGeometry() : null; } catch (e) { return null; }
}

function save() {
  if (current == null || busy || document.getElementById('save').disabled) return;
  const g = ringOf();
  if (!g) { msg.textContent = '· no ring yet: drag the dots onto the edge of the black first'; return; }
  busy = true;
  const id = current;
  msg.textContent = '· saving...';
  fetch('/admin/ring-labels/' + id, {method: 'POST', credentials: 'include',
                                     headers: {'Content-Type': 'application/json', 'X-Admin': '1'}, body: JSON.stringify(g)})
    .then(noContent)
    .then(() => { msg.textContent = '· #' + id + ' saved'; return Promise.all([loadHistory(), next()]); })
    .catch(fail)
    .finally(() => busy = false);
}

// Skip is for good: stored on the server, so the random pick never offers that series again.
function skip() {
  if (current == null || busy || document.getElementById('skip').disabled) return;
  busy = true;
  const id = current;
  fetch('/admin/ring-labels/' + id + '/skip', {method: 'POST', credentials: 'include', headers: {'X-Admin': '1'}})
    .then(noContent)
    .then(() => { msg.textContent = '· #' + id + ' skipped'; return next(id); })
    .catch(fail)
    .finally(() => busy = false);
}

document.getElementById('save').onclick = save;
document.getElementById('skip').onclick = skip;

// A and S anywhere on the page, the editor frame included.
function onKey(e) {
  if (e.ctrlKey || e.metaKey || e.altKey || e.repeat) return;
  const t = e.target;
  if (t && t.closest && t.closest('input, select, textarea')) return;
  const k = e.key.toLowerCase();
  if (k === 'a') save();
  else if (k === 's') skip();
  else return;
  e.preventDefault();
}
document.addEventListener('keydown', onKey);
// A list row opens its series; listened for on the documents, since the list moves into the frame's document.
function onListClick(e) {
  const li = e.target.closest && e.target.closest('#history li');
  if (li) show(Number(li.dataset.id), li.dataset.at);
}
document.addEventListener('click', onListClick);
// The transparency of what is drawn over the photo (the editor's --ov); remembered per browser.
function applyOv() {
  ovv.textContent = ov.value + '%';
  try { frame.contentDocument.documentElement.style.setProperty('--ov', String(1 - ov.value / 100)); } catch (e) {}
}
try { if (localStorage.ringOv) ov.value = localStorage.ringOv; } catch (e) {}
ov.oninput = () => { try { localStorage.ringOv = ov.value; } catch (e) {} applyOv(); };
applyOv();

frame.addEventListener('load', () => {
  try { frame.contentDocument.addEventListener('keydown', onKey); frame.contentDocument.addEventListener('click', onListClick); } catch (e) {}
  applyOv();
  let slot = null;
  try { slot = frame.contentDocument.getElementById('curate-slot'); } catch (e) {}
  (slot || dock).appendChild(side);
  dock.hidden = !!slot;
});

const start = new URLSearchParams(location.search).get('id');
loadHistory().then(() => {
  if (start == null) return next();
  const li = hist.querySelector('li[data-id="' + Number(start) + '"]');
  show(Number(start), li ? li.dataset.at : null);
  return get('/admin/ring-labels/next').then(remaining);
}).catch(fail);
"""

/** README.md inside the export zip: enough for a person or an agent to use the set without reading this server. */
internal const val RING_EXPORT_README = """# Markera ring and centre ground truth

Hand-corrected 6/7 rings and target centres, made in the backend's /admin/ring-labels page, for measuring and
improving the app's centre (digit-row intersection) and 6/7 ring (probe-disk ellipse fit) detection.

## Files

- `images/<seriesId>.jpg`: the stored photo of the series, exactly as the app uploaded it.
- `labels.json`: an array, one object per labelled photo, oldest series id first:

| field | meaning |
|---|---|
| `seriesId` | the series (and the photo's file name) |
| `imageWidth`, `imageHeight` | the frame all coordinates are in, in pixels. The JPEG has this frame's aspect ratio; scale by `jpegWidth / imageWidth` if the JPEG's pixel size differs |
| `corrected` | the ground truth: the hand-placed ring and centre (a geometry, below) |
| `detected` | what the series had before the correction, the app's own result (null: no ring was found) |
| `detectedFrom` | where `detected` comes from: `created` = the app's first upload of the series (the detector's ring, unless the user placed it by hand in the app); `baseline` = the oldest tracked state of a series older than the server's change tracking; `current` = the series as it stood when first labelled (no tracked state) |
| `firstAt`, `at` | first and latest save of the label (UTC) |

A geometry is `{centreX, centreY, ringCx, ringCy, ringSemiMajor, ringSemiMinor, ringRotationRad}`, pixels of the
frame above, origin top left, y down:

- `centreX`, `centreY`: the target centre (the middle of the X ring). Independent of the ellipse: under perspective
  the centre is not the ellipse's centre.
- `ringCx`, `ringCy`, `ringSemiMajor`, `ringSemiMinor`, `ringRotationRad`: the outer edge of the black (the 6/7
  boundary, 100 mm from the centre on the target) as an ellipse; the rotation is of the major axis, radians,
  clockwise on screen from the x axis.

The ellipse and the centre together fix the photo-to-target homography (the centre's polar line with respect to
the ellipse is the vanishing line): the app's `TargetPlane.of` in `vision/` and `planeOf` in the admin editor's
script compute it. The target itself: rings every 25 mm, the 6/7 edge at 100 mm, the inner X within 12.5 mm.

## Suggested measures

Centre error = the distance from `detected` to `corrected` centre, in pixels or in mm (divide by
`corrected.ringSemiMajor / 100`). Ring error = for example the largest distance between the two ellipses along the
rays from the corrected centre, or the difference of the semi-axes and rotation. Photos where `detected` is null are
the detector's misses.
"""
