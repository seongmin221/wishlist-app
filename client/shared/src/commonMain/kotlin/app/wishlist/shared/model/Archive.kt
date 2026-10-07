@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package app.wishlist.shared.model

import kotlin.native.ObjCName
import kotlin.time.Instant

/** Identity and historical display only; this is not an archive API or restore contract. */
data class Archive(
    val id: String,
    val title: String,
    val originalPurposeId: String,
    val purposeSnapshot: ArchivePurposeSnapshot,
    val createdAt: Instant,
) {
    val createdAtIso: String get() = createdAt.toString()
}

data class ArchivePurposeSnapshot(
    val name: String,
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription") val description: String?,
    val colorKey: String,
    val iconKey: String,
)
