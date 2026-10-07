package app.http

import app.category.CategoryChange

import app.category.CategoryInput
import kotlin.test.*

class CategoryRequestParserTest {
    @Test fun `create optional defaults preserve original input`() {
        val result = assertIs<CategoryRequestParseResult.Valid<CategoryCreateRequest>>(
            parseCategoryCreateRequest("""{"parentId":"G003","name":"  My Desk  "}"""))
        assertEquals("G003", result.request.parentId)
        assertEquals(CategoryInput("  My Desk  ", null, emptyList()), result.request.input)
        assertEquals(result, parseCategoryCreateRequest("""{"name":"  My Desk  ","examples":null,"description":null,"parentId":"G003"}"""))
    }

    @Test fun `create rejects coercion unknown fields and invalid shapes`() {
        for (body in listOf(
            "[]", "null", "{", """{"parentId":"G003","name":7}""",
            """{"parentId":"G003","name":"desk","examples":[7]}""",
            """{"parentId":"G003","name":"desk","description":false}""",
            """{"parentId":"G003","name":"desk","ownerId":"forged"}""",
            """{"parentId":"G003","name":null}""",
            """{"name":"desk"}""",
        )) assertIs<CategoryRequestParseResult.Invalid>(parseCategoryCreateRequest(body), body)
    }

    @Test fun `patch distinguishes omitted fields from optional null`() {
        val renamed = assertIs<CategoryRequestParseResult.Valid<CategoryPatchRequest>>(
            parseCategoryPatchRequest("""{"expectedVersion":3,"name":"책상"}""" )).request
        assertEquals(3, renamed.expectedVersion)
        assertEquals("책상", renamed.name)
        assertEquals(CategoryChange.Keep, renamed.description)
        assertEquals(CategoryChange.Keep, renamed.examples)
        val cleared = assertIs<CategoryRequestParseResult.Valid<CategoryPatchRequest>>(
            parseCategoryPatchRequest("""{"expectedVersion":3,"description":null,"examples":null}""")).request
        assertNull(cleared.name)
        assertEquals(CategoryChange.Set<String?>(null), cleared.description)
        assertEquals(CategoryChange.Set(emptyList()), cleared.examples)
    }

    @Test fun `patch rejects parent even unchanged and requires exact positive version and changes`() {
        val parent = assertIs<CategoryRequestParseResult.Invalid>(
            parseCategoryPatchRequest("""{"expectedVersion":1,"name":"책상","parentId":"G003"}"""))
        assertEquals("CATEGORY_PARENT_IMMUTABLE", parent.code)
        for (body in listOf(
            """{"expectedVersion":1}""", """{"expectedVersion":1,"name":null}""",
            """{"expectedVersion":0,"name":"a"}""", """{"expectedVersion":-1,"name":"a"}""",
            """{"expectedVersion":"1","name":"a"}""", """{"expectedVersion":1.0,"name":"a"}""",
            """{"expectedVersion":2147483648,"name":"a"}""", """{"name":"a"}""",
            """{"expectedVersion":1,"examples":{}}""",
        )) assertIs<CategoryRequestParseResult.Invalid>(parseCategoryPatchRequest(body), body)
    }

    @Test fun `patch rejects malformed numeric tokens and exponent representations`() {
        for (token in listOf("01", "1e", "1e+", "1e0", "10e-1", "+1", "1.")) {
            assertIs<CategoryRequestParseResult.Invalid>(
                parseCategoryPatchRequest("""{"expectedVersion":$token,"name":"desk"}"""), token)
        }
    }
}
