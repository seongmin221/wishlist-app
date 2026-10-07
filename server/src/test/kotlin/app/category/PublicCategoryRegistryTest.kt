package app.category

import app.ai.TaxonomyCatalog
import kotlin.test.*

class PublicCategoryRegistryTest {
    @Test fun `parent ids pin the public taxonomy and preserve leaf ids and order`() {
        val registry = PublicCategoryRegistry(TaxonomyCatalog.loadV1())
        assertEquals((1..11).map { "G%03d".format(it) }, registry.groups.map { it.id })
        val digital = registry.groups.single { it.id == "G003" }
        assertEquals("디지털·IT", digital.name)
        assertEquals(2, digital.displayOrder)
        assertEquals("C019", digital.categories.first().id)
        assertEquals("C033", digital.categories.last().id)
        val headphone = digital.categories.single { it.id == "C026" }
        assertEquals("헤드폰", headphone.name)
        assertEquals("G003", headphone.parentId)
        assertEquals(7, headphone.displayOrder)
        assertEquals(87, registry.groups.sumOf { it.categories.size })
        assertNull(registry.parent("C026"))
        assertNull(registry.parent("unknown"))
    }
}
