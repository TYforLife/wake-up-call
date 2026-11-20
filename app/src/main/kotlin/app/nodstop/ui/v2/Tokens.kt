package app.nodstop.ui.v2

/**
 * The v2 design's colours (PLAN Run 8, M1), exactly as `docs/design/tokens.v2.json` has them. Plain values (no
 * Compose types) so a JVM test can compare them with the design file and fail if they ever drift.
 */
object Tokens {
    val light: Map<String, Long> = mapOf(
        "bg" to 0xFFFFFFFF, "fg" to 0xFF111111, "muted" to 0xFF6B6B6B, "faint" to 0xFFA3A3A3,
        "line" to 0xFFECECEC, "fill" to 0xFFF4F4F4, "primary" to 0xFF111111, "on-primary" to 0xFFFFFFFF,
        "accent" to 0xFFFF5A1F, "danger" to 0xFFD92D20,
        "map-bg" to 0xFFEEF0F2, "map-road" to 0xFFFFFFFF, "map-park" to 0xFFE3EEDF, "map-water" to 0xFFD6E6F5,
    )
    val dark: Map<String, Long> = mapOf(
        "bg" to 0xFF000000, "fg" to 0xFFF5F5F5, "muted" to 0xFF9A9A9A, "faint" to 0xFF5C5C5C,
        "line" to 0xFF1F1F1F, "fill" to 0xFF161616, "primary" to 0xFFF5F5F5, "on-primary" to 0xFF000000,
        "accent" to 0xFFFF6A33, "danger" to 0xFFFF5C50,
        "map-bg" to 0xFF121314, "map-road" to 0xFF24262A, "map-park" to 0xFF16211A, "map-water" to 0xFF0F1A26,
    )

    /** Spacing, radii and sizes from `docs/design/system/tokens.json`, in dp. */
    const val SPACE_1 = 4
    const val SPACE_2 = 8
    const val SPACE_3 = 12
    const val SPACE_4 = 16
    const val SPACE_6 = 24
    const val SPACE_8 = 32
    const val RADIUS_CONTROL = 10
    const val RADIUS_BUTTON = 16
    const val RADIUS_SHEET = 24
    const val TAP = 48
    const val BUTTON = 56
    const val ROW = 64
    const val HOLD = 200
}
