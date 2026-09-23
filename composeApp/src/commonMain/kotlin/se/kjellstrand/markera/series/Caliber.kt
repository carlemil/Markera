package se.kjellstrand.markera.series

/**
 * A caliber a series can be tagged with: the built-ins below, or one the user
 * added on this device. [label] is both the UI text and the wire value, and it is
 * the identity — two calibers are equal when their labels are, whatever the
 * diameter. The server checks only a label's shape ([isValidCaliberLabel],
 * `CALIBER_SHAPE` in `server/src/main/kotlin/markera/server/Server.kt`), so a new
 * one needs no server change as long as it fits. Labels are stored in the
 * production database: add and reorder freely, never rename a built-in.
 *
 * Constant names: an identifier cannot start with a digit, so a trailing letter
 * group is hoisted to the front (`22lr` → [LR22], `9mm` → [MM9]) and an all-digit
 * label gets a `C` prefix (`32` → [C32], `6.5x55` → [C65X55]); `.` and `-` drop out.
 *
 * [diameterMm] is the nominal bullet diameter. It sizes the hit dots the app draws
 * and the hole a hand-placed hit is edge-gauged with ([holeRadiusMm]) — the
 * backend knows nothing about it.
 */
class Caliber(val label: String, val diameterMm: Float) {

    /** [BUILT_IN] order; the user's own and unknown labels sort after every built-in. */
    val ordinal: Int
        get() = BUILT_IN.indexOf(this).let { if (it < 0) BUILT_IN.size else it }

    override fun equals(other: Any?): Boolean = other is Caliber && other.label == label
    override fun hashCode(): Int = label.hashCode()
    override fun toString(): String = label

    companion object {
        /** An unknown caliber is drawn at the calibration size, i.e. exactly like [C32]. */
        val NONE = Caliber("-", 7.92f)

        // Kantantända.
        val LR22 = Caliber("22lr", 5.66f)
        val WMR22 = Caliber("22wmr", 5.70f)
        val HMR17 = Caliber("17hmr", 4.37f)

        // Pistol / revolver.
        val C32 = Caliber("32", 7.92f)
        val C380 = Caliber("380", 9.02f)
        val MM9 = Caliber("9mm", 9.02f)
        val C38 = Caliber("38", 9.07f)
        val C357 = Caliber("357", 9.07f)
        val C40 = Caliber("40", 10.16f)
        val MM10 = Caliber("10mm", 10.16f)
        val C44 = Caliber("44", 10.90f)
        val C45 = Caliber("45", 11.45f)

        // Gevär / rifle.
        val C223 = Caliber("223", 5.69f)
        val C243 = Caliber("243", 6.17f)
        val C65X55 = Caliber("6.5x55", 6.71f)
        val CM65 = Caliber("6.5cm", 6.71f)
        val C270 = Caliber("270", 7.04f)
        val C308 = Caliber("308", 7.82f)
        val C3006 = Caliber("30-06", 7.82f)
        val C762X39 = Caliber("7.62x39", 7.90f)
        val C8X57 = Caliber("8x57", 8.20f)
        val C93X62 = Caliber("9.3x62", 9.30f)
        val WM300 = Caliber("300wm", 7.82f)

        /** The fixed calibers, [NONE] first; the UI order. */
        val BUILT_IN = listOf(
            NONE, LR22, WMR22, HMR17,
            C32, C380, MM9, C38, C357, C40, MM10, C44, C45,
            C223, C243, C65X55, CM65, C270, C308, C3006, C762X39, C8X57, C93X62, WM300,
        )

        /**
         * A built-in, else one of the user's [custom] ones. A label matching neither
         * (a custom caliber since removed, or added on another device) keeps its label
         * with [NONE]'s diameter, so History and Statistik still show what was saved;
         * only a blank one is [NONE]. Never throws.
         */
        fun fromLabel(label: String, custom: List<Caliber> = emptyList()): Caliber =
            if (label.isBlank()) {
                NONE
            } else {
                BUILT_IN.firstOrNull { it.label == label }
                    ?: custom.firstOrNull { it.label == label }
                    ?: Caliber(label, NONE.diameterMm)
            }
    }
}

/** The server's `CALIBER_SHAPE`: a longer or odder label is a 400. */
private val CALIBER_SHAPE = Regex("[A-Za-z0-9 .,/-]{1,16}")

/** The longest label the server takes. */
const val MAX_CALIBER_LABEL_LENGTH = 16

/** The bullet diameters a user's own caliber may have, in mm. */
val CALIBER_DIAMETER_RANGE_MM = 1f..20f

/** A label the server accepts: 1–16 of `[A-Za-z0-9 .,/-]`, and not blank. */
fun isValidCaliberLabel(label: String): Boolean = label.isNotBlank() && CALIBER_SHAPE.matches(label)

/** A typed diameter in mm, `.` or `,` as the decimal separator; null unless in [CALIBER_DIAMETER_RANGE_MM]. */
fun parseCaliberDiameter(text: String): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in CALIBER_DIAMETER_RANGE_MM }

/**
 * The user's own calibers as `label=diameter;…` for the device store. A label
 * cannot hold `;` or `=` ([isValidCaliberLabel]), so neither needs escaping.
 */
fun List<Caliber>.encodeCustomCalibers(): String = joinToString(";") { "${it.label}=${it.diameterMm}" }

/** Never throws: an unparsable entry is dropped, null or blank is none. */
fun decodeCustomCalibers(text: String?): List<Caliber> =
    text.orEmpty().split(";").mapNotNull { entry ->
        val label = entry.substringBefore("=")
        val diameter = entry.substringAfter("=", "").toFloatOrNull()
        if (isValidCaliberLabel(label) && diameter != null) Caliber(label, diameter) else null
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

/**
 * Radius in mm of the hole a hand-placed hit is edge-gauged with; null for
 * [Caliber.NONE], whose callers keep their own fallback.
 */
fun Caliber.holeRadiusMm(): Double? = if (this == Caliber.NONE) null else diameterMm / 2.0
