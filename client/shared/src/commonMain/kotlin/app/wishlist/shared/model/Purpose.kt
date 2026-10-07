@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package app.wishlist.shared.model

import kotlin.native.ObjCName
import kotlin.time.Instant

data class Purpose(
    val id: String,
    val name: String,
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription") val description: String?,
    val colorKey: String,
    val iconKey: String,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val createdAtIso: String get() = createdAt.toString()
    val updatedAtIso: String get() = updatedAt.toString()
}

/** Known presentation keys; Purpose preserves future raw keys without rejecting them. */
object PurposeKeys {
    val colorKeys: Set<String> = setOf("coral", "mustard", "periwinkle", "cyan", "mint", "pink")
    val iconKeys: Set<String> = setOf("heart", "home", "plane", "gift", "tent", "music", "star", "book")
}
