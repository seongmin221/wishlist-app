@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)
package app.wishlist.shared.model
import kotlin.native.ObjCName
import kotlin.time.Instant

data class PurposeActivity(val at: Instant, val kind: String)
data class PurposePreview(val itemId: String, val imageUrl: String?)
data class ArchiveSummary(val count: Int, val recentTitles: List<String>)
data class PurposeDetail(
    val id: String, val name: String,
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription") val description: String?,
    val colorKey: String, val iconKey: String, val candidateCount: Int, val membershipVersion: Int,
    val version: Int, val activity: PurposeActivity, val createdAt: Instant, val updatedAt: Instant,
    val allowedActions: Set<String>,
)
data class PurposeSummary(
    val id: String, val name: String,
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription") val description: String?,
    val colorKey: String, val iconKey: String, val version: Int, val candidateCount: Int,
    val activity: PurposeActivity, val previews: List<PurposePreview>,
)
data class PurposeCatalog(val purposes: List<PurposeSummary>, val activeCount: Int, val purposeLimit: Int, val archiveSummary: ArchiveSummary)
data class PurposeCommand(
    val name: String,
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription") val description: String?,
    val colorKey: String, val iconKey: String,
)
data class PurposePatch(
    val name: String? = null,
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription") val description: DescriptionChange = DescriptionChange.Keep,
    val colorKey: String? = null, val iconKey: String? = null,
) {
    val isEmpty: Boolean get() = name == null && description == DescriptionChange.Keep && colorKey == null && iconKey == null
}
data class PurposeCreated(val purpose: PurposeDetail, val activeCount: Int, val purposeLimit: Int)

/** Explicit v1 resource mappings. Unknown response keys survive unchanged for neutral rendering. */
object PurposeStyleKeys {
    private val colors = mapOf("coral" to "CORAL", "mustard" to "MUSTARD", "periwinkle" to "PERIWINKLE", "cyan" to "CYAN", "mint" to "MINT", "pink" to "PINK")
    private val icons = mapOf("heart" to "HEART", "home" to "HOME", "plane" to "PLANE", "gift" to "GIFT", "tent" to "TENT", "music" to "MUSIC", "star" to "STAR", "book" to "BOOK")
    val colorKeys: Set<String> = colors.keys
    val iconKeys: Set<String> = icons.keys
    fun colorToWire(key: String): String? = colors[key]
    fun iconToWire(key: String): String? = icons[key]
    fun colorFromWire(key: String): String = colors.entries.firstOrNull { it.value == key }?.key ?: key
    fun iconFromWire(key: String): String = icons.entries.firstOrNull { it.value == key }?.key ?: key
}
