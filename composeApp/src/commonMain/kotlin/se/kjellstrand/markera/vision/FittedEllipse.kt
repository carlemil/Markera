package se.kjellstrand.markera.vision

/** The projected 6/7 ring: a tilted circle seen as an ellipse, in source-image px. */
data class FittedEllipse(
    val cx: Float,
    val cy: Float,
    val semiMajor: Float,
    val semiMinor: Float,
    /** Angle of the major axis from +x, in radians. */
    val rotationRad: Float,
)
