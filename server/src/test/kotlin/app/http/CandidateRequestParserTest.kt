package app.http

import app.candidates.CandidateFilter
import app.candidates.CandidateMoveRequest
import app.candidates.DeletionKind
import app.candidates.MoveSelection
import app.category.CategoryRef
import app.wishlist.ReadPosition
import io.ktor.http.parametersOf
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class CandidateRequestParserTest {
    private val owner = UUID.randomUUID()
    private val purpose = UUID.randomUUID()
    private val item = "55555555-5555-4555-8555-555555555555"

    private fun query(vararg pairs: Pair<String, String>, forPurpose: UUID = purpose) =
        CandidateRequestParser.query(owner, forPurpose, parametersOf(pairs.groupBy({ it.first }, { it.second })))

    @Test fun `query accepts filters limits and facet flags`() {
        assertEquals(CandidateQuery(CandidateFilter.None, 20, null, false), (query() as CandidateQueryParseResult.Valid).query)
        val custom = UUID.randomUUID()
        assertEquals(CandidateQuery(CandidateFilter.Category(CategoryRef.Public("C026")), 100, null, true),
            (query("categoryId" to "C026", "limit" to "100", "includeCategoryFacets" to "true") as CandidateQueryParseResult.Valid).query)
        assertEquals(CandidateFilter.Category(CategoryRef.Custom(custom)),
            (query("categoryId" to custom.toString()) as CandidateQueryParseResult.Valid).query.filter)
        assertEquals(CandidateFilter.Parent("G003"), (query("parentId" to "G003", "includeCategoryFacets" to "false") as CandidateQueryParseResult.Valid).query.filter)
    }

    @Test fun `query rejects unknown repeated combined and malformed values`() {
        val invalid = listOf(
            arrayOf("categoryId" to "C026", "parentId" to "G003"), arrayOf("categoryId" to "C999"), arrayOf("parentId" to "G999"),
            arrayOf("parentId" to "C026"), arrayOf("categoryId" to ""), arrayOf("limit" to "0"), arrayOf("limit" to "101"), arrayOf("limit" to "01"),
            arrayOf("limit" to "ten"), arrayOf("includeCategoryFacets" to "yes"), arrayOf("includeCategoryFacets" to "TRUE"),
            arrayOf("sort" to "new"), arrayOf("limit" to "2", "limit" to "3"), arrayOf("categoryId" to UUID.randomUUID().toString().uppercase() + "x"),
        )
        for (pairs in invalid) assertEquals(CandidateQueryParseResult.InvalidQuery, query(*pairs), pairs.toList().toString())
    }

    @Test fun `cursor is bound to owner purpose and filter but not to limit or facets`() {
        val position = ReadPosition(Instant.parse("2026-10-24T01:00:00.123456Z"), UUID.randomUUID())
        val filter = CandidateFilter.Category(CategoryRef.Public("C026"))
        val cursor = CandidateCursorCodec.encode(owner, purpose, filter, position)
        val parsed = query("categoryId" to "C026", "cursor" to cursor, "limit" to "5", "includeCategoryFacets" to "true")
        assertEquals(position, (parsed as CandidateQueryParseResult.Valid).query.after)
        assertEquals(CandidateQueryParseResult.InvalidCursor, query("cursor" to cursor))
        assertEquals(CandidateQueryParseResult.InvalidCursor, query("categoryId" to "C019", "cursor" to cursor))
        assertEquals(CandidateQueryParseResult.InvalidCursor, query("categoryId" to "C026", "cursor" to cursor, forPurpose = UUID.randomUUID()))
        assertEquals(CandidateQueryParseResult.InvalidCursor,
            CandidateRequestParser.query(UUID.randomUUID(), purpose, parametersOf(mapOf("categoryId" to listOf("C026"), "cursor" to listOf(cursor)))))
        for (bad in listOf("x", "!!", cursor.dropLast(2), "a".repeat(3000))) {
            assertEquals(CandidateQueryParseResult.InvalidCursor, query("categoryId" to "C026", "cursor" to bad), bad.take(20))
        }
        val parentCursor = CandidateCursorCodec.encode(owner, purpose, CandidateFilter.Parent("G003"), position)
        assertEquals(position, (query("parentId" to "G003", "cursor" to parentCursor) as CandidateQueryParseResult.Valid).query.after)
        assertEquals(CandidateQueryParseResult.InvalidCursor, query("cursor" to parentCursor))
    }

    @Test fun `move body accepts one to one hundred distinct selections`() {
        val parsed = CandidateRequestParser.move("""{"expectedVersion":1,"items":[{"id":"$item","expectedVersion":3,"expectedPurposeId":null}]}""")
        assertEquals(CandidateMoveRequest(1, listOf(MoveSelection(UUID.fromString(item), 3, null))), (parsed as CandidateMoveParseResult.Valid).request)
        val other = UUID.randomUUID()
        val withPurpose = CandidateRequestParser.move("""{"items":[{"expectedPurposeId":"$other","id":"$item","expectedVersion":3}],"expectedVersion":2}""")
        assertEquals(other, (withPurpose as CandidateMoveParseResult.Valid).request.items.single().expectedPurposeId)
        val hundred = (1..100).joinToString(",") { """{"id":"${UUID.randomUUID()}","expectedVersion":1,"expectedPurposeId":null}""" }
        assertEquals(100, (CandidateRequestParser.move("""{"expectedVersion":1,"items":[$hundred]}""") as CandidateMoveParseResult.Valid).request.items.size)
    }

    @Test fun `move body reports structural errors without fields and value errors by field`() {
        val selection = """{"id":"$item","expectedVersion":3,"expectedPurposeId":null}"""
        val structural = listOf("", "[]", "null", "{", """{"items":[$selection]}""", """{"expectedVersion":1}""",
            """{"expectedVersion":1,"items":[$selection],"force":true}""")
        for (raw in structural) assertEquals(CandidateMoveParseResult.Invalid(emptySet()), CandidateRequestParser.move(raw), raw)
        val hundredOne = (1..101).joinToString(",") { """{"id":"${UUID.randomUUID()}","expectedVersion":1,"expectedPurposeId":null}""" }
        val byField = mapOf(
            """{"expectedVersion":0,"items":[$selection]}""" to setOf("expectedVersion"),
            """{"expectedVersion":"1","items":[$selection]}""" to setOf("expectedVersion"),
            """{"expectedVersion":1.0,"items":[$selection]}""" to setOf("expectedVersion"),
            """{"expectedVersion":1,"items":[]}""" to setOf("items"),
            """{"expectedVersion":1,"items":{}}""" to setOf("items"),
            """{"expectedVersion":1,"items":[$hundredOne]}""" to setOf("items"),
            """{"expectedVersion":1,"items":[$selection,$selection]}""" to setOf("items"),
            """{"expectedVersion":1,"items":[$selection,{"id":"${item.uppercase()}","expectedVersion":1,"expectedPurposeId":null}]}""" to setOf("items"),
            """{"expectedVersion":1,"items":[{"id":"$item","expectedVersion":3}]}""" to setOf("items"),
            """{"expectedVersion":1,"items":[{"id":"$item","expectedVersion":3,"expectedPurposeId":null,"x":1}]}""" to setOf("items"),
            """{"expectedVersion":1,"items":[{"id":"nope","expectedVersion":3,"expectedPurposeId":null}]}""" to setOf("items"),
            """{"expectedVersion":1,"items":[{"id":"$item","expectedVersion":-1,"expectedPurposeId":null}]}""" to setOf("items"),
            """{"expectedVersion":1,"items":[{"id":"$item","expectedVersion":3,"expectedPurposeId":7}]}""" to setOf("items"),
            """{"expectedVersion":1,"items":["$item"]}""" to setOf("items"),
            """{"expectedVersion":null,"items":null}""" to setOf("expectedVersion", "items"),
        )
        for ((raw, fields) in byField) assertEquals(CandidateMoveParseResult.Invalid(fields), CandidateRequestParser.move(raw), raw.take(120))
    }

    @Test fun `impact query accepts limit and a cursor bound to owner kind resource and fingerprint`() {
        val id = UUID.randomUUID()
        val token = "a".repeat(64)
        val position = ReadPosition(Instant.parse("2026-10-24T01:00:00Z"), UUID.randomUUID())
        fun parse(kind: DeletionKind = DeletionKind.CATEGORY, resource: UUID = id, as_: UUID = owner, vararg pairs: Pair<String, String>) =
            CandidateRequestParser.impactQuery(as_, kind, resource, parametersOf(pairs.groupBy({ it.first }, { it.second })))
        assertEquals(DeletionQuery(20, null, null), (parse() as DeletionQueryParseResult.Valid).query)
        val cursor = CandidateCursorCodec.encodeImpact(owner, DeletionKind.CATEGORY, id, token, position)
        assertEquals(DeletionQuery(7, position, token), (parse(pairs = arrayOf("limit" to "7", "cursor" to cursor)) as DeletionQueryParseResult.Valid).query)
        for (pairs in listOf(arrayOf("limit" to "0"), arrayOf("limit" to "101"), arrayOf("x" to "1"), arrayOf("limit" to "1", "limit" to "2"), arrayOf("limit" to ""))) {
            assertEquals(DeletionQueryParseResult.InvalidQuery, parse(pairs = pairs), pairs.toList().toString())
        }
        assertEquals(DeletionQueryParseResult.InvalidCursor, parse(DeletionKind.PURPOSE, pairs = arrayOf("cursor" to cursor)))
        assertEquals(DeletionQueryParseResult.InvalidCursor, parse(resource = UUID.randomUUID(), pairs = arrayOf("cursor" to cursor)))
        assertEquals(DeletionQueryParseResult.InvalidCursor, parse(as_ = UUID.randomUUID(), pairs = arrayOf("cursor" to cursor)))
        assertEquals(DeletionQueryParseResult.InvalidCursor, parse(pairs = arrayOf("cursor" to CandidateCursorCodec.encode(owner, id, CandidateFilter.None, position))))
        assertEquals(DeletionQueryParseResult.InvalidCursor, parse(pairs = arrayOf("cursor" to "zz")))
    }

    @Test fun `deletion body needs a positive version and a lowercase sha256 token`() {
        val token = "0123456789abcdef".repeat(4)
        assertEquals(DeletionParseResult.Valid(3, token), CandidateRequestParser.deletion("""{"expectedVersion":3,"impactToken":"$token"}"""))
        for (raw in listOf("", "[]", "{}", """{"expectedVersion":3}""", """{"impactToken":"$token"}""", """{"expectedVersion":3,"impactToken":"$token","x":1}""")) {
            assertEquals(DeletionParseResult.Invalid(emptySet()), CandidateRequestParser.deletion(raw), raw)
        }
        val byField = mapOf(
            """{"expectedVersion":0,"impactToken":"$token"}""" to setOf("expectedVersion"),
            """{"expectedVersion":"3","impactToken":"$token"}""" to setOf("expectedVersion"),
            """{"expectedVersion":3,"impactToken":"${token.uppercase()}"}""" to setOf("impactToken"),
            """{"expectedVersion":3,"impactToken":"${token.drop(1)}"}""" to setOf("impactToken"),
            """{"expectedVersion":3,"impactToken":null}""" to setOf("impactToken"),
            """{"expectedVersion":null,"impactToken":7}""" to setOf("expectedVersion", "impactToken"),
        )
        for ((raw, fields) in byField) assertEquals(DeletionParseResult.Invalid(fields), CandidateRequestParser.deletion(raw), raw)
    }
}
