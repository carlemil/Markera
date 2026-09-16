package se.kjellstrand.markera.series

/**
 * The calibers a series can be tagged with. Declaration order is the UI order
 * and [label] is both the UI text and the wire value the backend validates,
 * so keep them in sync with `CALIBERS` in
 * `server/src/main/kotlin/markera/server/Server.kt` — a label the server does
 * not know is a 400 on save. Labels are stored in the production database:
 * add and reorder freely, never rename.
 *
 * Constant names: an identifier cannot start with a digit, so a trailing letter
 * group is hoisted to the front (`22lr` → [LR22], `9mm` → [MM9]) and an all-digit
 * label gets a `C` prefix (`32` → [C32], `6.5x55` → [C65X55]); `.` and `-` drop out.
 */
enum class Caliber(val label: String) {
    NONE("-"),

    // Kantantända.
    LR22("22lr"),
    WMR22("22wmr"),
    HMR17("17hmr"),

    // Pistol / revolver.
    C32("32"),
    C380("380"),
    MM9("9mm"),
    C38("38"),
    C357("357"),
    C40("40"),
    MM10("10mm"),
    C44("44"),
    C45("45"),

    // Gevär / rifle.
    C223("223"),
    C243("243"),
    C65X55("6.5x55"),
    CM65("6.5cm"),
    C270("270"),
    C308("308"),
    C3006("30-06"),
    C762X39("7.62x39"),
    C8X57("8x57"),
    C93X62("9.3x62"),
    WM300("300wm");

    companion object {
        /** Unknown labels degrade to [NONE] rather than throwing. */
        fun fromLabel(label: String): Caliber =
            entries.firstOrNull { it.label == label } ?: NONE
    }
}
