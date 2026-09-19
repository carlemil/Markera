package se.kjellstrand.markera.series

/**
 * The calibers a series can be tagged with. Declaration order is the UI order
 * and [label] is both the UI text and the wire value. The server checks only
 * a label's shape (1–16 of `[A-Za-z0-9 .,/-]`, `CALIBER_SHAPE` in
 * `server/src/main/kotlin/markera/server/Server.kt`), so a new one needs no
 * server change as long as it fits. Labels are stored in the production
 * database: add and reorder freely, never rename.
 *
 * Constant names: an identifier cannot start with a digit, so a trailing letter
 * group is hoisted to the front (`22lr` → [LR22], `9mm` → [MM9]) and an all-digit
 * label gets a `C` prefix (`32` → [C32], `6.5x55` → [C65X55]); `.` and `-` drop out.
 *
 * [diameterMm] is the nominal bullet diameter, used only to size the hit dots the
 * app draws — the backend knows nothing about it.
 */
enum class Caliber(val label: String, val diameterMm: Float) {
    /** An unknown caliber is drawn at the calibration size, i.e. exactly like [C32]. */
    NONE("-", 7.92f),

    // Kantantända.
    LR22("22lr", 5.66f),
    WMR22("22wmr", 5.70f),
    HMR17("17hmr", 4.37f),

    // Pistol / revolver.
    C32("32", 7.92f),
    C380("380", 9.02f),
    MM9("9mm", 9.02f),
    C38("38", 9.07f),
    C357("357", 9.07f),
    C40("40", 10.16f),
    MM10("10mm", 10.16f),
    C44("44", 10.90f),
    C45("45", 11.45f),

    // Gevär / rifle.
    C223("223", 5.69f),
    C243("243", 6.17f),
    C65X55("6.5x55", 6.71f),
    CM65("6.5cm", 6.71f),
    C270("270", 7.04f),
    C308("308", 7.82f),
    C3006("30-06", 7.82f),
    C762X39("7.62x39", 7.90f),
    C8X57("8x57", 8.20f),
    C93X62("9.3x62", 9.30f),
    WM300("300wm", 7.82f);

    companion object {
        /** Unknown labels degrade to [NONE] rather than throwing. */
        fun fromLabel(label: String): Caliber =
            entries.firstOrNull { it.label == label } ?: NONE
    }
}

/**
 * The hit-dot radius the user calibrated by eye, against a caliber 32 target.
 * A calibration knob, not the physical hole size: every other caliber scales off
 * it by bullet diameter.
 */
const val HIT_DOT_RADIUS_32_MM = 2.5f

/**
 * Hit dots keep a little translucency so overlapping shots still read as a cloud
 * rather than one blob. A knob the user set by eye on the phone: 0.30 was too
 * faint to read against the target, 0.80 keeps the overlap visible.
 */
const val HIT_DOT_ALPHA = 0.80f

/**
 * Dot radius in target millimetres for this caliber. The ratio is taken first so
 * caliber 32 lands on exactly [HIT_DOT_RADIUS_32_MM] instead of a Float rounding
 * of it.
 */
fun Caliber.hitDotRadiusMm(): Float =
    HIT_DOT_RADIUS_32_MM * (diameterMm / Caliber.C32.diameterMm)
