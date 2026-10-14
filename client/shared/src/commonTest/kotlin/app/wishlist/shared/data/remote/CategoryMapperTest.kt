package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.*
import kotlin.test.*

/* Fixtures are the examples of docs/architecture/server/category-management-api.md (B2 contract). */
internal const val BROWSE_JSON = """{
  "scope": "BROWSE", "taxonomyVersion": "v1",
  "groups": [{
    "id": "G003", "name": "디지털·IT", "displayOrder": 2, "itemCount": 0,
    "categories": [{
      "id": "11111111-1111-4111-8111-111111111111", "name": "책상 장비",
      "parentId": "G003", "kind": "CUSTOM", "displayOrder": 15, "itemCount": 0
    }]
  }],
  "customUsedCount": 1, "customLimit": 20
}"""

internal const val CREATED_JSON = """{
  "category": {
    "id": "11111111-1111-4111-8111-111111111111", "name": "책상 장비", "parentId": "G003",
    "description": "업무 공간\n주변 장비", "examples": ["키보드", "헤드폰"], "itemCount": 0, "version": 1
  },
  "customUsedCount": 1, "customLimit": 20
}"""

internal const val PATCHED_JSON =
    """{"id":"11111111-1111-4111-8111-111111111111","name":"책상 장비","parentId":"G003","description":null,"examples":[],"itemCount":0,"version":2}"""

class CategoryMapperTest {
    private val customId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun browseExampleParses() {
        val catalog = parseCatalog(BROWSE_JSON).successValue()
        assertEquals(CategoryScope.BROWSE, catalog.scope)
        assertEquals("v1", catalog.taxonomyVersion)
        assertEquals(1, catalog.customUsedCount)
        assertEquals(20, catalog.customLimit)
        val group = catalog.groups.single()
        assertEquals(CategoryGroup("G003", "디지털·IT", 2, 0, group.categories), group)
        assertEquals(CategoryEntry(customId, "책상 장비", "G003", CategoryKind.CUSTOM, "CUSTOM", 15, 0), group.categories.single())
    }

    @Test
    fun publicLeafAndUnknownKind() {
        val body = BROWSE_JSON
            .replace("\"id\": \"$customId\"", "\"id\": \"C026\"")
            .replace("\"kind\": \"CUSTOM\"", "\"kind\": \"SHARED\"")
        val entry = parseCatalog(body).successValue().groups.single().categories.single()
        assertEquals("C026", entry.id)
        assertEquals(CategoryKind.UNKNOWN, entry.kind)
        assertEquals("SHARED", entry.rawKind)
    }

    @Test
    fun customIdsAreCanonicalLowercase() {
        val upper = BROWSE_JSON.replace(customId, customId.uppercase())
        assertEquals(customId, parseCatalog(upper).successValue().groups.single().categories.single().id)
        assertEquals(customId, parseCustomDetail(PATCHED_JSON.replace(customId, customId.uppercase())).successValue().id)
    }

    @Test
    fun createdAndDetailParse() {
        val created = parseCreated(CREATED_JSON).successValue()
        assertEquals(CustomCategoryDetail(customId, "책상 장비", "G003", "업무 공간\n주변 장비", listOf("키보드", "헤드폰"), 0, 1), created.category)
        assertEquals(1, created.customUsedCount)
        assertEquals(20, created.customLimit)
        assertEquals(CustomCategoryDetail(customId, "책상 장비", "G003", null, emptyList(), 0, 2), parseCustomDetail(PATCHED_JSON).successValue())
    }

    @Test
    fun malformedBodiesAreInvalidResponse() {
        val bad = listOf(
            "not json",
            BROWSE_JSON.replace("\"scope\": \"BROWSE\"", "\"scope\": \"ALL\""),
            BROWSE_JSON.replace("\"customLimit\": 20", "\"customLimit\": \"20\""),
            BROWSE_JSON.replace("\"name\": \"책상 장비\",", ""),
            BROWSE_JSON.replace("\"itemCount\": 0,\n    \"categories\"", "\"itemCount\": -1,\n    \"categories\""),
        )
        for (body in bad) assertEquals(ErrorKind.INVALID_RESPONSE, parseCatalog(body).error().kind, body)
        assertEquals(ErrorKind.INVALID_RESPONSE, parseCustomDetail(PATCHED_JSON.replace("\"version\":2", "\"version\":0")).error().kind)
        assertEquals(ErrorKind.INVALID_RESPONSE, parseCustomDetail(PATCHED_JSON.replace("\"examples\":[]", "\"examples\":[1]")).error().kind)
        assertIs<ClientResult.Failure>(parseCreated("""{"category":null,"customUsedCount":1,"customLimit":20}"""))
    }
}
