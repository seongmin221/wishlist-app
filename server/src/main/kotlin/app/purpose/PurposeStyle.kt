package app.purpose

/** Stable shape/color names. Apps map them to theme colors and SVGs; screen labels may change independently. */
enum class PurposeColor { CORAL, MUSTARD, PERIWINKLE, CYAN, MINT, PINK }
enum class PurposeIcon { HEART, HOME, PLANE, GIFT, TENT, MUSIC, STAR, BOOK }

object PurposeStyle {
    const val VERSION = "v1"
    val DEFAULT_COLOR = PurposeColor.CORAL
    val DEFAULT_ICON = PurposeIcon.HEART
    fun color(key: String): PurposeColor? = PurposeColor.entries.firstOrNull { it.name == key }
    fun icon(key: String): PurposeIcon? = PurposeIcon.entries.firstOrNull { it.name == key }
}
