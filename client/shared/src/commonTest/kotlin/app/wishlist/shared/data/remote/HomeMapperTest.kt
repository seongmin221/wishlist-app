package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.*
import kotlin.test.*

/** The HOME-01 example of docs/architecture/server/wishlist-item-read-api.md (empty groups, one empty purpose). */
internal const val HOME_EMPTY_JSON = """{
  "actionGroups": [
    {"group": "ANALYSIS_IN_PROGRESS", "count": 0, "previews": []},
    {"group": "INFORMATION_COMPLETION", "count": 0, "previews": []},
    {"group": "CLASSIFICATION_REVIEW", "count": 0, "previews": []}
  ],
  "recentPurposes": [{
    "id": "00000000-0000-0000-0000-000000000003", "name": "여행", "colorKey": "CORAL", "iconKey": "HEART",
    "version": 1, "description": null, "candidateCount": 0,
    "activity": {"at": "2026-10-07T10:00:00Z", "kind": "CREATED"}, "previews": []
  }]
}"""

class HomeMapperTest {
    private val itemId = "00000000-0000-4000-8000-000000000101"

    @Test fun serverExampleKeepsEmptyGroupsAndEmptyPurpose() {
        val summary = parseHomeSummary(HOME_EMPTY_JSON).successValue()
        assertEquals(HomeActionGroup.entries.map { HomeGroupSummary(it, 0, emptyList()) }, summary.groups)
        val purpose = summary.recentPurposes.single()
        assertEquals("00000000-0000-0000-0000-000000000003", purpose.id)
        // Wire keys become the UI keys, like PUR-01.
        assertEquals("coral" to "heart", purpose.colorKey to purpose.iconKey)
        assertEquals(0, purpose.candidateCount)
        assertEquals(emptyList(), purpose.previews)
    }

    @Test fun unknownGroupIsIgnoredAndMissingGroupsAreZero() {
        val body = """{"actionGroups":[
            {"group":"SOMETHING_NEW","count":9,"previews":[{"item":$BASE_ITEM_JSON}]},
            {"group":"CLASSIFICATION_REVIEW","count":5,"previews":[{"item":$BASE_ITEM_JSON,"anchorCursor":"anc-1"}]}
        ],"recentPurposes":[]}"""
        val summary = parseHomeSummary(body).successValue()
        assertEquals(HomeActionGroup.entries, summary.groups.map { it.group })
        assertEquals(listOf(0, 0, 5), summary.groups.map { it.count })
        val preview = summary.groups.last().previews.single()
        assertEquals(itemId to "anc-1", preview.item.id to preview.anchorCursor)
        assertEquals(RequiredAction.CLASSIFICATION_REVIEW, preview.item.requiredAction)
    }

    @Test fun malformedBodiesAreInvalidResponses() {
        val bad = listOf(
            // A preview must carry its HOME-02 anchor.
            """{"actionGroups":[{"group":"CLASSIFICATION_REVIEW","count":1,"previews":[{"item":$BASE_ITEM_JSON}]}],"recentPurposes":[]}""",
            """{"actionGroups":[{"group":"CLASSIFICATION_REVIEW","count":"1","previews":[]}],"recentPurposes":[]}""",
            """{"actionGroups":[{"group":"CLASSIFICATION_REVIEW","count":-1,"previews":[]}],"recentPurposes":[]}""",
            """{"actionGroups":[],"recentPurposes":[{"id":"x"}]}""",
            """{"actionGroups":[]}""",
            """[]""",
        )
        for (body in bad) assertEquals(ErrorKind.INVALID_RESPONSE, parseHomeSummary(body).error().kind, body)
    }
}
