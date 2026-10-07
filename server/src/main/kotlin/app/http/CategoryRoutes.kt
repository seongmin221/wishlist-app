package app.http

import app.category.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

fun Route.categoryRoutes(service:CategoryService,ownerResolver:suspend(ApplicationCall)->UUID?) {
    get("/v1/categories") {
        val owner=ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized,"UNAUTHORIZED")
        val query=call.request.queryParameters
        val scope=query["scope"]?.let { value -> CategoryScope.entries.firstOrNull { it.name==value } }
        if(scope==null || query.getAll("scope")?.size!=1 || (query.getAll("parentId")?.size ?: 0)>1)
            return@get call.respondApiError(HttpStatusCode.BadRequest,"INVALID_CATEGORY_QUERY")
        categoryErrors(call,query=true) {
            val result=withContext(Dispatchers.IO) { service.list(owner,scope,query["parentId"]) }
            call.respondText(ApiJson.encodeToString(result.toDto()),ContentType.Application.Json)
        }
    }
    post("/v1/custom-categories") {
        val owner=ownerResolver(call) ?: return@post call.respondApiError(HttpStatusCode.Unauthorized,"UNAUTHORIZED")
        val key=parseCanonicalUuid(call.request.headers["Idempotency-Key"])
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest,"INVALID_IDEMPOTENCY_KEY")
        val request=when(val parsed=parseCategoryCreateRequest(call.receiveText())) {
            is CategoryRequestParseResult.Valid -> parsed.request
            is CategoryRequestParseResult.Invalid -> return@post call.respondCategoryInputError(parsed)
        }
        categoryErrors(call) {
            val result=withContext(Dispatchers.IO) { service.create(owner,key,request.parentId,request.input) }
            if(result.replayed) call.response.headers.append("Idempotency-Replayed","true")
            else call.response.headers.append(HttpHeaders.Location,"/v1/custom-categories/${result.category.id}")
            call.respondText(ApiJson.encodeToString(CategoryCreationDto(result.category.toDto(),result.customUsedCount)),ContentType.Application.Json,
                if(result.replayed) HttpStatusCode.OK else HttpStatusCode.Created)
        }
    }
    get("/v1/custom-categories/{id}") {
        val owner=ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized,"UNAUTHORIZED")
        val id=parseCanonicalUuid(call.parameters["id"]) ?: return@get call.respondApiError(HttpStatusCode.BadRequest,"INVALID_CATEGORY_ID")
        val result=withContext(Dispatchers.IO) { service.get(owner,id) }
            ?: return@get call.respondApiError(HttpStatusCode.NotFound,"CATEGORY_NOT_FOUND")
        call.respondText(ApiJson.encodeToString(result.toDto()),ContentType.Application.Json)
    }
    patch("/v1/custom-categories/{id}") {
        val owner=ownerResolver(call) ?: return@patch call.respondApiError(HttpStatusCode.Unauthorized,"UNAUTHORIZED")
        val id=parseCanonicalUuid(call.parameters["id"]) ?: return@patch call.respondApiError(HttpStatusCode.BadRequest,"INVALID_CATEGORY_ID")
        val request=when(val parsed=parseCategoryPatchRequest(call.receiveText())) {
            is CategoryRequestParseResult.Valid -> parsed.request
            is CategoryRequestParseResult.Invalid -> return@patch call.respondCategoryInputError(parsed)
        }
        val changes=CategoryChanges(request.name,request.description.toDomain(),request.examples.toDomain())
        categoryErrors(call) {
            val result=withContext(Dispatchers.IO) { service.patch(owner,id,request.expectedVersion,changes) }
            call.respondText(ApiJson.encodeToString(result.toDto()),ContentType.Application.Json)
        }
    }
}

private fun <T> CategoryFieldChange<T>.toDomain():CategoryChange<T> = when(this) {
    CategoryFieldChange.Keep -> CategoryChange.Keep
    is CategoryFieldChange.Set -> CategoryChange.Set(value)
}
private suspend fun ApplicationCall.respondCategoryInputError(error:CategoryRequestParseResult.Invalid) =
    respondApiError(HttpStatusCode.UnprocessableEntity,error.code,categoryFieldDetails(error.fields))
private fun categoryFieldDetails(fields:Set<String>):Map<String,JsonElement> =
    if(fields.isEmpty()) emptyMap() else mapOf("fields" to JsonArray(fields.sorted().map(::JsonPrimitive)))
private suspend fun categoryErrors(call:ApplicationCall,query:Boolean=false,block:suspend()->Unit) {
    try { block() } catch(error:CategoryException) {
        val status=when(error.code) {
            "CATEGORY_NOT_FOUND" -> HttpStatusCode.NotFound
            "CATEGORY_CREATE_RATE_LIMITED" -> HttpStatusCode.TooManyRequests
            "INVALID_CATEGORY_INPUT","INVALID_CATEGORY_PARENT" -> if(query) HttpStatusCode.BadRequest else HttpStatusCode.UnprocessableEntity
            else -> HttpStatusCode.Conflict
        }
        val details=categoryFieldDetails(error.fields).toMutableMap()
        error.currentVersion?.let { details["currentVersion"]=JsonPrimitive(it) }
        error.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter,it.toString()) }
        call.respondApiError(status,error.code,details)
    }
}
