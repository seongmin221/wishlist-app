package app.http

import app.category.CategoryChange
import app.purpose.*
import kotlin.test.*

class PurposeRequestParserTest {
    @Test fun `create requires typed name and pinned keys and normalizes description`() {
        val valid = assertIs<PurposeParseResult.Valid<PurposeInput>>(parsePurposeCreateRequest("""{"name":"목적","colorKey":"MINT","iconKey":"TENT"}""")).request
        assertEquals(PurposeInput("목적", null, PurposeColor.MINT, PurposeIcon.TENT), valid)
        assertEquals(valid, (parsePurposeCreateRequest("""{"name":"목적","description":null,"colorKey":"MINT","iconKey":"TENT"}""") as PurposeParseResult.Valid).request)
        for ((raw, fields) in listOf(
            """{"name":"목적","colorKey":"RED","iconKey":"TENT"}""" to setOf("colorKey"),
            """{"name":"목적","colorKey":"MINT"}""" to setOf("iconKey"),
            """{"name":5,"colorKey":"MINT","iconKey":"TENT"}""" to setOf("name"),
            """{"name":"목적","description":7,"colorKey":"MINT","iconKey":"TENT"}""" to setOf("description"),
            """{"name":"목적","colorKey":"MINT","iconKey":"TENT","extra":1}""" to emptySet(),
            "[]" to emptySet(),
        )) assertEquals(fields, assertIs<PurposeParseResult.Invalid>(parsePurposeCreateRequest(raw), raw).fields, raw)
    }

    @Test fun `patch distinguishes omitted fields optional null and required null`() {
        val request = assertIs<PurposeParseResult.Valid<PurposePatchRequest>>(parsePurposePatchRequest("""{"expectedVersion":2,"description":null,"colorKey":"PINK"}""")).request
        assertEquals(2, request.expectedVersion)
        assertEquals(PurposeChanges(description = CategoryChange.Set(null), color = PurposeColor.PINK), request.changes)
        for ((raw, fields) in listOf(
            """{"expectedVersion":2}""" to emptySet(), """{"expectedVersion":"2","name":"a"}""" to setOf("expectedVersion"),
            """{"expectedVersion":0,"name":"a"}""" to setOf("expectedVersion"), """{"expectedVersion":2,"name":null}""" to setOf("name"),
            """{"expectedVersion":2,"iconKey":null}""" to setOf("iconKey"), """{"expectedVersion":2,"name":"a","version":3}""" to emptySet(),
        )) assertEquals(fields, assertIs<PurposeParseResult.Invalid>(parsePurposePatchRequest(raw), raw).fields, raw)
    }
}
