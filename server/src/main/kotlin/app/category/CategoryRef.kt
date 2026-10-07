package app.category

import app.common.parseCanonicalUuid
import app.ai.TaxonomyCatalog
import java.util.UUID

sealed interface CategoryRef {
    val value: String

    data class Public(override val value: String) : CategoryRef
    data class Custom(val id: UUID) : CategoryRef {
        override val value: String get() = id.toString()
    }

    companion object {
        private val publicIds by lazy { TaxonomyCatalog.loadV1().categories.map { it.id }.toSet() }

        fun parse(value: String): CategoryRef? {
            if (value in publicIds) return Public(value)
            return parseCanonicalUuid(value)?.let(::Custom)
        }
    }
}
