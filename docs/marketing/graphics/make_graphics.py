"""Builds the forum graphics (EN + PL) as HTML and renders them to PNG with headless Chrome/Edge.

    python docs/marketing/graphics/make_graphics.py

Writes <name>-<lang>.html into graphics/src/ and <name>-<lang>.png into graphics/.
Texts follow the app's own strings (values/strings.xml, values-pl/strings.xml).
"""
import math
import os
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
BROWSERS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

_CLIP = 0
GREEN = "#7bd389"
YELLOW = "#ffd54f"
DAY_COLOURS = ["#4fc3f7", "#ffb74d", "#ce93d8"]

CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { background: #0f1511; }
body { font-family: 'Segoe UI', Arial, sans-serif; color: #e8efe9; width: 1200px; padding: 36px 40px 30px; }
h1 { font-size: 40px; font-weight: 700; letter-spacing: -0.5px; }
h1 span { color: %(g)s; }
.sub { font-size: 20px; color: #a9b8ad; margin-top: 6px; }
.card { background: #1a231d; border: 1px solid #2c3a30; border-radius: 16px; padding: 18px 20px; }
.label { font-size: 14px; text-transform: uppercase; letter-spacing: 1.2px; color: %(g)s; font-weight: 700; }
.brand { margin-top: 22px; display: flex; justify-content: space-between; font-size: 16px; color: #7f8f84; }
.brand b { color: %(g)s; font-size: 18px; }
""" % {"g": GREEN}


def target_svg(size, show_r, hits=(), arrow=None, ellipse=None, digits=True, ringed=(), colours=None, hit_mm=4.5):
    """A precision target: rings 25 mm apart, black (7-10) 200 mm across, X ring 12.5 mm.
    hits: (x, y) mm, x right, y down. show_r: radius in mm that fills the square."""
    s = size / 2 / show_r
    c = size / 2
    global _CLIP
    _CLIP += 1
    cid = f"clip{_CLIP}"
    out = [f'<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}" xmlns="http://www.w3.org/2000/svg">']
    out.append(f'<defs><clipPath id="{cid}"><circle cx="{c}" cy="{c}" r="{min(show_r, 150) * s}"/></clipPath></defs>')
    out.append(f'<g clip-path="url(#{cid})">')
    out.append(f'<circle cx="{c}" cy="{c}" r="{min(show_r, 150) * s}" fill="#f4f1e8"/>')
    for ring in range(5, 11):
        r = (11 - ring) * 25
        if r > show_r + 30:
            continue
        if ring == 7:
            out.append(f'<circle cx="{c}" cy="{c}" r="{r * s}" fill="#141414"/>')
        stroke = "#f4f1e8" if ring >= 8 else "#141414"
        out.append(f'<circle cx="{c}" cy="{c}" r="{r * s}" fill="none" stroke="{stroke}" stroke-width="{max(1, s * 0.6):.2f}"/>')
    out.append(f'<circle cx="{c}" cy="{c}" r="{12.5 * s}" fill="none" stroke="#f4f1e8" stroke-width="{max(0.8, s * 0.4):.2f}"/>')
    out.append("</g>")
    if digits:
        fs = 13 * s
        for ring in range(6, 10):
            rr = (11 - ring) * 25 - 12.5
            if rr * s > c:
                continue
            fill = "#141414" if ring == 6 else "#f4f1e8"
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                x, y = c + dx * rr * s, c + dy * rr * s
                out.append(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{fs:.1f}" fill="{fill}" text-anchor="middle" '
                           f'dominant-baseline="central" font-family="Arial" font-weight="700">{ring}</text>')
    if ellipse:
        ex, ey, a, b, rot = ellipse
        out.append(f'<ellipse cx="{c + ex * s:.1f}" cy="{c + ey * s:.1f}" rx="{a * s:.1f}" ry="{b * s:.1f}" '
                   f'transform="rotate({rot} {c + ex * s:.1f} {c + ey * s:.1f})" fill="none" stroke="{YELLOW}" stroke-width="2.5"/>')
    hr = max(3.0, hit_mm * s)
    for i, (x, y) in enumerate(hits):
        col = colours[i] if colours else "#64b5f6"
        out.append(f'<circle cx="{c + x * s:.1f}" cy="{c + y * s:.1f}" r="{hr:.1f}" fill="{col}" fill-opacity="0.9" stroke="#0b0b0b" stroke-width="1"/>')
    for (x, y) in ringed:
        out.append(f'<circle cx="{c + x * s:.1f}" cy="{c + y * s:.1f}" r="{hr * 2.1:.1f}" fill="none" stroke="#ff7043" stroke-width="2.5"/>')
    if arrow:
        ax, ay = c + arrow[0] * s, c + arrow[1] * s
        ang = math.atan2(ay - c, ax - c)
        h = max(7, 5 * s)
        p1 = (ax - h * math.cos(ang - 0.45), ay - h * math.sin(ang - 0.45))
        p2 = (ax - h * math.cos(ang + 0.45), ay - h * math.sin(ang + 0.45))
        for colour, w in (("#0b0b0b", 6), (GREEN, 3)):
            out.append(f'<line x1="{c}" y1="{c}" x2="{ax:.1f}" y2="{ay:.1f}" stroke="{colour}" stroke-width="{w}" stroke-linecap="round"/>')
        out.append(f'<polygon points="{ax:.1f},{ay:.1f} {p1[0]:.1f},{p1[1]:.1f} {p2[0]:.1f},{p2[1]:.1f}" fill="{GREEN}" stroke="#0b0b0b" stroke-width="1"/>')
        out.append(f'<circle cx="{c}" cy="{c}" r="3.5" fill="{GREEN}" stroke="#0b0b0b"/>')
    out.append("</svg>")
    return "".join(out)


def group(n, mx, my, sx, sy, seed, rot=0.0):
    rnd = random.Random(seed)
    pts = [(rnd.gauss(0, sx), rnd.gauss(0, sy)) for _ in range(n)]
    ax = sum(p[0] for p in pts) / n
    ay = sum(p[1] for p in pts) / n
    co, si = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    return [(mx + (x - ax) * co - (y - ay) * si, my + (x - ax) * si + (y - ay) * co) for x, y in pts]


def bars_svg(values, w, h, highlight=None, usual=None, line=None, vmax=50, vmin=30):
    out = [f'<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg">']
    n = len(values)
    bw = (w - 20) / n
    sc = lambda v: h - 10 - (v - vmin) / (vmax - vmin) * (h - 24)
    for i, v in enumerate(values):
        col = "#ff7043" if highlight and i in highlight else "#64b5f6"
        out.append(f'<rect x="{10 + i * bw + bw * 0.15:.1f}" y="{sc(v):.1f}" width="{bw * 0.7:.1f}" height="{h - 10 - sc(v):.1f}" rx="3" fill="{col}"/>')
    if usual is not None:
        out.append(f'<line x1="8" x2="{w - 8}" y1="{sc(usual):.1f}" y2="{sc(usual):.1f}" stroke="{GREEN}" stroke-width="2" stroke-dasharray="6 5"/>')
    if line:
        a, b = line
        out.append(f'<line x1="10" x2="{w - 10}" y1="{sc(a):.1f}" y2="{sc(b):.1f}" stroke="{GREEN}" stroke-width="3"/>')
    out.append("</svg>")
    return "".join(out)


def dots_svg(values, w, h, line, vmax=50, vmin=30):
    out = [f'<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg">']
    n = len(values)
    sc = lambda v: h - 10 - (v - vmin) / (vmax - vmin) * (h - 24)
    for i, v in enumerate(values):
        out.append(f'<circle cx="{14 + i * (w - 28) / (n - 1):.1f}" cy="{sc(v):.1f}" r="4" fill="#64b5f6"/>')
    a, b = line
    out.append(f'<line x1="10" x2="{w - 10}" y1="{sc(a):.1f}" y2="{sc(b):.1f}" stroke="{GREEN}" stroke-width="3"/>')
    out.append("</svg>")
    return "".join(out)


T = {
    "en": dict(
        s_title='One bad series, <span>or your sights?</span>',
        s_sub='Markera only suggests a sight correction when the offset is stable, not after one bad series.',
        s_legend=["Day 1", "Day 2", "Day 3"],
        s_legend_arrow="centre → mean point of impact",
        s_found="Insights",
        s_summary="Your group sits 25 mm low and right over 18 shots, the same way on 3 of 3 days.",
        s_varies="Not the same way every day? Then it points to technique, not the sights, and no correction is offered.",
        s_card="Sight correction",
        s_up="Up 20 mm: 5 clicks",
        s_left="Left 15 mm: 4 clicks",
        s_basis="Clicks at 25 m with 4 mm per click.",
        s_rule="Move the rear sight the way the impact should go.",
        s_half="Less than half a click? The app says leave it.",
        s_button="I adjusted the sights",
        s_steps=[("Scan your series", "Every target, per caliber"),
                 ("Stable offset", "Same way on 3+ days, about 15+ shots"),
                 ("Correct once", "In mm, or in clicks"),
                 ("Tap \u201cI adjusted the sights\u201d", "Judged only on new shots")],
        f_title='What <span>Insights</span> points out',
        f_sub='Once a caliber has about 15 shots, Markera looks through your saved series for patterns it is fairly sure of.',
        f_anat=[("What the numbers show", "Your group is about 60 percent taller than it is wide, over 40 shots."),
                ("Often caused by", "Breathing: shooting at a different point in the breath each time."),
                ("Try", "Settle the breath at the same point every shot and check the height of the front sight in the notch before you squeeze.")],
        f_tiles=[("Group off-centre", "Same way every day: sights. Varies: technique."),
                 ("Taller than wide", "Often: breathing, front sight height, grip pressure."),
                 ("Wider than tall", "Often: trigger pull going sideways, wrist, stance."),
                 ("One shot gets away", "Often: trigger control on that shot, follow-through."),
                 ("A wide day", "Today's groups much wider than your earlier ones."),
                 ("A low day or series", "Well under your own usual score."),
                 ("Trend", "Your scores rising or falling over the weeks."),
                 ("Within a session", "Later series worse (fatigue) or better (slow start).")],
        f_foot="A hint, not a coach: the causes are typical ones, not a verdict.",
        p_title='From photo to <span>progress</span>',
        p_sub='Photograph the target after a series. Markera does the rest.',
        p_steps=[("Take a photo", "Point the camera at the target"),
                 ("Holes found", "Holes, rings and centre"),
                 ("Every shot scored", "Fix anything with a tap"),
                 ("Insights", "Patterns over the weeks")],
        brand="Android and iPhone",
    ),
    "pl": dict(
        s_title='Jedna słaba seria <span>czy przyrządy?</span>',
        s_sub='Markera proponuje korektę przyrządów tylko przy stałym przesunięciu, nie po jednej słabej serii.',
        s_legend=["Dzień 1", "Dzień 2", "Dzień 3"],
        s_legend_arrow="środek → średni punkt trafienia",
        s_found="Analiza",
        s_summary="Twoje skupienie leży 25 mm nisko w prawo w 18 strzałach, tak samo w 3 z 3 dni.",
        s_varies="Nie każdego dnia tak samo? To wskazuje na technikę, nie na przyrządy, i korekty nie ma.",
        s_card="Korekta przyrządów",
        s_up="W górę 20 mm: 5 kl.",
        s_left="W lewo 15 mm: 4 kl.",
        s_basis="Kliknięcia na 25 m przy 4 mm na kliknięcie.",
        s_rule="Przesuń szczerbinkę w tę stronę, w którą mają przesunąć się trafienia.",
        s_half="Mniej niż pół kliknięcia? Aplikacja radzi zostawić.",
        s_button="Przyrządy przestawione",
        s_steps=[("Skanuj serie", "Każda tarcza, osobno dla kalibru"),
                 ("Stałe przesunięcie", "W tę samą stronę w 3+ dni, około 15+ strzałów"),
                 ("Jedna korekta", "W mm albo w kliknięciach"),
                 ("Stuknij \u201ePrzyrządy przestawione\u201d", "Oceniane tylko nowe strzały")],
        f_title='Co wskazuje <span>Analiza</span>',
        f_sub='Gdy kaliber ma około 15 strzałów, Markera przegląda zapisane serie i szuka wzorców, których jest dość pewna.',
        f_anat=[("Co pokazują liczby", "Twoje skupienie jest w 40 strzałach o około 60 procent wyższe niż szersze."),
                ("Częste przyczyny", "Oddech: strzał za każdym razem w innej fazie oddechu."),
                ("Spróbuj", "Zatrzymuj oddech przy każdym strzale w tej samej fazie i przed ściągnięciem spustu sprawdź wysokość muszki w szczerbince.")],
        f_tiles=[("Skupienie przesunięte", "Co dzień tak samo: przyrządy. Zmienia się: technika."),
                 ("Wyższe niż szersze", "Często: oddech, wysokość muszki, nacisk na chwyt."),
                 ("Szersze niż wyższe", "Często: ściąganie spustu w bok, nadgarstek, postawa."),
                 ("Odskakujący strzał", "Często: praca na spuście, brak utrzymania po strzale."),
                 ("Szerszy dzień", "Dzisiejsze skupienia wyraźnie szersze niż wcześniej."),
                 ("Słaby dzień lub seria", "Wyraźnie poniżej twojej zwykłej normy."),
                 ("Trend", "Wyniki rosną albo spadają w ciągu tygodni."),
                 ("W trakcie treningu", "Późniejsze serie gorsze (zmęczenie) albo lepsze (powolny start).")],
        f_foot="To wskazówka, nie trener: przyczyny są typowe, to nie werdykt.",
        p_title='Od zdjęcia do <span>postępów</span>',
        p_sub='Po serii zrób zdjęcie tarczy. Resztę zrobi Markera.',
        p_steps=[("Zrób zdjęcie", "Skieruj aparat na tarczę"),
                 ("Przestrzeliny znalezione", "Przestrzeliny, pierścienie i środek"),
                 ("Każdy strzał policzony", "Poprawka jednym stuknięciem"),
                 ("Analiza", "Wzorce w ciągu tygodni")],
        brand="Android i iPhone",
    ),
}


def brand(t):
    return f'<div class="brand"><b>Markera</b><span>{t["brand"]}</span></div>'


def sight_page(t):
    days = [group(6, 15, 20, 11, 11, seed) for seed in (3, 11, 29)]
    hits = [p for d in days for p in d]
    colours = [DAY_COLOURS[i // 6] for i in range(18)]
    tgt = target_svg(560, 125, hits=hits, arrow=(15, 20), colours=colours, hit_mm=3.2)
    legend = "".join(f'<span><i style="background:{c}"></i>{name}</span>' for c, name in zip(DAY_COLOURS, t["s_legend"]))
    steps = ""
    for i, (a, b) in enumerate(t["s_steps"]):
        if i:
            steps += '<div class="arr">→</div>'
        steps += f'<div class="step"><div class="num">{i + 1}</div><div class="st">{a}</div><div class="sd">{b}</div></div>'
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}
.main {{ display: flex; gap: 30px; margin-top: 26px; }}
.left {{ width: 560px; }}
.legend {{ display: flex; gap: 18px; margin-top: 12px; font-size: 16px; color: #c5d1c8; flex-wrap: wrap; }}
.legend i {{ display: inline-block; width: 13px; height: 13px; border-radius: 50%; margin-right: 6px; vertical-align: -1px; }}
.legend .ar {{ color: {GREEN}; }}
.right {{ flex: 1; display: flex; flex-direction: column; gap: 16px; }}
.summary {{ font-size: 21px; line-height: 1.35; margin-top: 8px; }}
.varies {{ font-size: 16px; color: #a9b8ad; line-height: 1.4; margin-top: 10px; }}
.corr div.v {{ font-size: 28px; font-weight: 700; margin-top: 8px; }}
.corr .basis {{ font-size: 15px; color: #a9b8ad; margin-top: 8px; }}
.corr .rule {{ font-size: 17px; margin-top: 12px; padding-top: 12px; border-top: 1px solid #2c3a30; line-height: 1.35; }}
.corr .half {{ font-size: 15px; color: #a9b8ad; margin-top: 8px; }}
.btn {{ align-self: flex-start; background: {GREEN}; color: #0f1511; font-weight: 700; font-size: 18px; padding: 11px 22px; border-radius: 24px; }}
.flow {{ display: flex; align-items: stretch; margin-top: 26px; }}
.step {{ flex: 1; background: #1a231d; border: 1px solid #2c3a30; border-radius: 14px; padding: 14px 14px; }}
.num {{ width: 28px; height: 28px; border-radius: 50%; background: {GREEN}; color: #0f1511; font-weight: 700; text-align: center; line-height: 28px; font-size: 16px; }}
.st {{ font-size: 18px; font-weight: 700; margin-top: 8px; }}
.sd {{ font-size: 15px; color: #a9b8ad; margin-top: 4px; line-height: 1.3; }}
.arr {{ color: {GREEN}; font-size: 28px; align-self: center; padding: 0 8px; }}
</style></head><body>
<h1>{t["s_title"]}</h1><div class="sub">{t["s_sub"]}</div>
<div class="main"><div class="left">{tgt}
<div class="legend">{legend}<span class="ar">━▶ {t["s_legend_arrow"]}</span></div></div>
<div class="right">
<div class="card"><div class="label">{t["s_found"]}</div><div class="summary">{t["s_summary"]}</div><div class="varies">{t["s_varies"]}</div></div>
<div class="card corr"><div class="label">{t["s_card"]}</div><div class="v">{t["s_up"]}</div><div class="v">{t["s_left"]}</div>
<div class="basis">{t["s_basis"]}</div><div class="rule">{t["s_rule"]}</div><div class="half">{t["s_half"]}</div></div>
<div class="btn">✓ {t["s_button"]}</div>
</div></div>
<div class="flow">{steps}</div>
{brand(t)}
</body></html>"""


def findings_page(t):
    S = 170
    minis = [
        target_svg(S, 80, hits=group(15, 30, -22, 9, 9, 5), arrow=(30, -22), digits=False),
        target_svg(S, 80, hits=group(15, 0, 0, 7, 22, 6), ellipse=(0, 0, 14, 44, 0), digits=False),
        target_svg(S, 80, hits=group(15, 0, 0, 22, 7, 7), ellipse=(0, 0, 44, 14, 0), digits=False),
        target_svg(S, 80, hits=group(9, 0, 0, 7, 7, 8) + [(-42, 30)], ringed=[(-42, 30)], digits=False),
        None,
        bars_svg([44, 45, 43, 46, 44, 45, 38, 37, 39], 230, 120, highlight={6, 7, 8}, usual=44.5),
        dots_svg([38, 40, 39, 41, 40, 43, 42, 44, 45, 44, 47], 230, 120, line=(38.5, 46)),
        bars_svg([46, 45, 44, 41, 40, 38], 230, 120, highlight={3, 4, 5}),
    ]
    wide = ('<div class="pair">' + target_svg(110, 80, hits=group(8, 0, 0, 7, 7, 9), digits=False)
            + target_svg(110, 80, hits=group(8, 0, 0, 20, 20, 10), digits=False) + '</div>')
    minis[4] = wide
    tiles = "".join(
        f'<div class="tile"><div class="mini">{m}</div><div class="tt">{a}</div><div class="td">{b}</div></div>'
        for m, (a, b) in zip(minis, t["f_tiles"]))
    anat = ""
    for i, (a, b) in enumerate(t["f_anat"]):
        if i:
            anat += '<div class="arr">→</div>'
        anat += f'<div class="an"><div class="label">{a}</div><div class="ab">{b}</div></div>'
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}
.anat {{ display: flex; margin-top: 24px; align-items: stretch; }}
.an {{ flex: 1; background: #1a231d; border: 1px solid #2c3a30; border-radius: 14px; padding: 14px 16px; }}
.ab {{ font-size: 17px; line-height: 1.38; margin-top: 6px; }}
.arr {{ color: {GREEN}; font-size: 28px; align-self: center; padding: 0 8px; }}
.grid {{ display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-top: 22px; }}
.tile {{ background: #1a231d; border: 1px solid #2c3a30; border-radius: 14px; padding: 14px; }}
.mini {{ height: 172px; display: flex; align-items: center; justify-content: center; }}
.pair {{ display: flex; gap: 8px; align-items: center; }}
.tt {{ font-size: 19px; font-weight: 700; margin-top: 10px; }}
.td {{ font-size: 15px; color: #a9b8ad; margin-top: 4px; line-height: 1.35; }}
.foot {{ margin-top: 18px; font-size: 17px; color: #c5d1c8; }}
</style></head><body>
<h1>{t["f_title"]}</h1><div class="sub">{t["f_sub"]}</div>
<div class="anat">{anat}</div>
<div class="grid">{tiles}</div>
<div class="foot">{t["f_foot"]}</div>
{brand(t)}
</body></html>"""


def pipeline_page(t):
    S = 180
    photo = (f'<svg width="{S}" height="{S}" viewBox="0 0 {S} {S}"><rect x="6" y="6" width="{S - 12}" height="{S - 12}" rx="18" fill="#2a332c" stroke="#3c4a40" stroke-width="2"/>'
             f'<g transform="translate(25 25)">{target_svg(S - 50, 130, hits=group(5, 6, 8, 14, 14, 12))}</g>'
             '<g stroke="#ffffff" stroke-width="4" fill="none">'
             f'<path d="M16 46 V16 H46"/><path d="M{S - 46} 16 H{S - 16} V46"/><path d="M16 {S - 46} V{S - 16} H46"/><path d="M{S - 46} {S - 16} H{S - 16} V{S - 46}"/></g></svg>')
    hits = group(5, 4, 6, 24, 24, 12)
    s = S / 2 / 110
    boxes = "".join(f'<rect x="{S / 2 + x * s - 7:.1f}" y="{S / 2 + y * s - 7:.1f}" width="14" height="14" fill="none" stroke="{GREEN}" stroke-width="2"/>' for x, y in hits)
    found = target_svg(S, 110, hits=hits).replace("</svg>", boxes + "</svg>")
    scored = '<div class="chips">' + "".join(f'<span>{v}</span>' for v in ("X", "10", "9", "9", "8")) + '</div><div class="total">46</div>'
    insight = target_svg(S, 90, hits=group(20, 22, 26, 10, 10, 21), arrow=(22, 26), ellipse=(22, 26, 22, 20, 0), digits=False)
    cols = [photo, found, scored, insight]
    steps = ""
    for i, (m, (a, b)) in enumerate(zip(cols, t["p_steps"])):
        if i:
            steps += '<div class="arr">→</div>'
        steps += f'<div class="ps"><div class="pm">{m}</div><div class="tt">{a}</div><div class="td">{b}</div></div>'
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}
.flow {{ display: flex; margin-top: 24px; align-items: stretch; }}
.ps {{ flex: 1; background: #1a231d; border: 1px solid #2c3a30; border-radius: 14px; padding: 16px; text-align: center; }}
.pm {{ height: 190px; display: flex; flex-direction: column; align-items: center; justify-content: center; }}
.arr {{ color: {GREEN}; font-size: 30px; align-self: center; padding: 0 8px; }}
.tt {{ font-size: 19px; font-weight: 700; margin-top: 10px; }}
.td {{ font-size: 15px; color: #a9b8ad; margin-top: 4px; }}
.chips {{ display: flex; gap: 6px; }}
.chips span {{ width: 38px; height: 44px; line-height: 44px; border-radius: 10px; background: #2a332c; border: 1px solid #3c4a40; font-size: 20px; font-weight: 700; }}
.total {{ margin-top: 16px; font-size: 44px; font-weight: 800; color: {GREEN}; }}
</style></head><body>
<h1>{t["p_title"]}</h1><div class="sub">{t["p_sub"]}</div>
<div class="flow">{steps}</div>
{brand(t)}
</body></html>"""


PAGES = [("sight-correction", sight_page, 1010), ("insights-findings", findings_page, 995), ("photo-to-insights", pipeline_page, 505)]


def main():
    browser = next((b for b in BROWSERS if os.path.exists(b)), None)
    if not browser:
        sys.exit("No Chrome or Edge found")
    os.makedirs(SRC, exist_ok=True)
    only = sys.argv[1:]
    for name, fn, height in PAGES:
        for lang, t in T.items():
            stem = f"{name}-{lang}"
            if only and stem not in only and name not in only:
                continue
            html = os.path.join(SRC, stem + ".html")
            with open(html, "w", encoding="utf-8") as f:
                f.write(fn(t))
            png = os.path.join(HERE, stem + ".png")
            subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                            f"--window-size=1200,{height}", f"--screenshot={png}", "file:///" + html.replace("\\", "/")],
                           check=True, capture_output=True)
            print(png)


if __name__ == "__main__":
    main()
