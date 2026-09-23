package app.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaxonomyCatalogTest {
    @Test fun `release taxonomy has eleven groups and eighty seven unique leaf categories`() {
        val catalog = TaxonomyCatalog.loadV1()
        assertEquals("v1",catalog.version)
        assertEquals(11,catalog.groups.size)
        assertEquals(87,catalog.categories.size)
        assertEquals(87,catalog.categories.map { it.id }.toSet().size)
        assertTrue(catalog.categories.any { it.group == "디지털·IT" && it.name == "헤드폰" })
        assertTrue(catalog.categories.any { it.group == "가구·인테리어" && it.name == "조명" })
    }

    @Test fun `candidate snapshot includes category names not only opaque ids`() {
        val catalog = TaxonomyCatalog.loadV1()
        val headphone = catalog.categories.single { it.name == "헤드폰" }
        val snapshot = catalog.snapshot(setOf(headphone.id))
        val body = OpenAiResponsesGateway(OpenAiConfig("release-snapshot-for-test", "secret"))
            .requestBody("Noise cancelling headphones",snapshot).toString()
        assertTrue(body.contains("헤드폰"))
        assertTrue(body.contains(headphone.id))
    }

    @Test fun `versioned asset matches approved product taxonomy text`() {
        val approved = File("../docs/product/references/product-taxonomy.md").readLines()
            .takeWhile { it != "## 출시 후 확장 기준" }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ") }
        assertEquals(approved,TaxonomyCatalog.loadV1().categories.map { it.name })
    }
}
