package se.kjellstrand.markera.series

/**
 * The calibers a series can be tagged with. Declaration order is the UI order
 * and [label] is both the UI text and the wire value the backend validates,
 * so keep them in sync with `CALIBERS` in `server/`.
 */
enum class Caliber(val label: String) {
    NONE("-"),
    LR22("22lr"),
    C32("32"),
    C38("38"),
    C357("357"),
    C45("45"),
    C44("44"),
    MM9("9mm"),
    MM10("10mm");

    companion object {
        /** Unknown labels degrade to [NONE] rather than throwing. */
        fun fromLabel(label: String): Caliber =
            entries.firstOrNull { it.label == label } ?: NONE
    }
}
