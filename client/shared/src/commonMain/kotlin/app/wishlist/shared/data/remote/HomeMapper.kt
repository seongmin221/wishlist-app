package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * B4 HOME-01 body. Previews use the shared card reader (and must carry their HOME-02 anchor),
 * recentPurposes the PUR-01 summary item reader. An element with an unknown group is ignored and a
 * group the server did not send is count 0, so the three groups are always there in fixed order.
 * HOME-02 pages and windows are ITEM-02 shaped and use parseItemPage.
 */

private val invalidHome = ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE))

internal fun parseHomeSummary(body: String): ClientResult<HomeSummary> {
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: IllegalArgumentException) { // SerializationException (bad JSON) is one
        null
    } ?: return invalidHome
    val groupRows = root["actionGroups"] as? JsonArray ?: return invalidHome
    val purposeRows = root["recentPurposes"] as? JsonArray ?: return invalidHome
    val groups = mutableMapOf<HomeActionGroup, HomeGroupSummary>()
    for (row in groupRows) {
        val o = row as? JsonObject ?: return invalidHome
        val name = (o["group"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return invalidHome
        val group = HomeActionGroup.entries.firstOrNull { it.name == name } ?: continue
        if (group in groups) return invalidHome
        val count = (o["count"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return invalidHome
        val previews = (o["previews"] as? JsonArray ?: return invalidHome).map { card ->
            when (val read = readCard(card)) {
                is ClientResult.Failure -> return read
                is ClientResult.Success -> HomePreview(read.value.first, read.value.second ?: return invalidHome)
            }
        }
        groups[group] = HomeGroupSummary(group, count, previews)
    }
    val purposes = purposeRows.map { raw ->
        when (val read = parsePurposeSummaryItem(raw)) {
            is ClientResult.Failure -> return read
            is ClientResult.Success -> read.value
        }
    }
    return ClientResult.Success(HomeSummary(HomeActionGroup.entries.map { groups[it] ?: HomeGroupSummary(it, 0, emptyList()) }, purposes))
}
