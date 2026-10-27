package app.wishlist.shared.domain

import app.wishlist.shared.model.PurposeCommand
import app.wishlist.shared.model.PurposeStyleKeys

/** B3 uses the same Unicode character policy as B2, with duplicate names allowed. */
object PurposeInputRules {
    const val NAME_LIMIT = 40
    const val DESCRIPTION_LIMIT = 200
    fun validate(command: PurposeCommand): Set<String> = buildSet {
        if (CategoryInputRules.isBlank(command.name) || CategoryInputRules.codePoints(command.name) > NAME_LIMIT ||
            CategoryInputRules.hasForbidden(command.name, false)) add("name")
        command.description?.let {
            if (CategoryInputRules.codePoints(it) > DESCRIPTION_LIMIT || CategoryInputRules.hasForbidden(it, true)) add("description")
        }
        if (command.colorKey !in PurposeStyleKeys.colorKeys) add("colorKey")
        if (command.iconKey !in PurposeStyleKeys.iconKeys) add("iconKey")
    }
}
