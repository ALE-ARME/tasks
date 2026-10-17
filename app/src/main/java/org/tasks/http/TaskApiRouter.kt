package org.tasks.http

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tasks.api.ApiErrors
import org.tasks.api.ApiFailure
import org.tasks.api.ApiQueryArgs
import org.tasks.api.ApiRows
import org.tasks.api.ApiValues
import org.tasks.api.TasksContract
import org.tasks.api.TasksContract.TaskTags

/**
 * The REST face of [TaskApi]: `GET/POST/PATCH/DELETE /v0/<collection>[/<id>]`, with the parameter
 * names, writable columns and failures of the content provider API. Everything except `/health`
 * needs the password, as `Authorization: Bearer <password>` or `X-Server-Password: <password>`.
 */
class TaskApiRouter(
    private val api: TaskApi,
    private val password: String,
    private val version: String = API_VERSION,
) {

    suspend fun handle(request: HttpRequest): HttpResponse = try {
        route(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(e)
    }

    private suspend fun route(request: HttpRequest): HttpResponse {
        val segments = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments == listOf(HEALTH)) {
            return HttpResponse(200, health().toString())
        }
        if (!authorized(request)) {
            return HttpResponse(
                401,
                HttpResponse.jsonError(
                    "Send $AUTHORIZATION: Bearer <password> or $SERVER_PASSWORD: <password>.",
                ),
                headers = mapOf("WWW-Authenticate" to "Bearer"),
            )
        }
        val path = segments.getOrNull(1)
        if (segments.firstOrNull() != version || segments.size > 3) {
            return notFound(request.path)
        }
        if (path == null) {
            return HttpResponse(200, index().toString())
        }
        if (path !in TasksContract.COLLECTIONS) {
            return notFound(request.path)
        }
        val id = segments.getOrNull(2)?.let(::parseId)
        return when (request.method) {
            "GET" -> if (id == null) list(path, request) else item(path, id)
            "POST" -> when {
                id != null || path !in POSTABLE -> notAllowed(request, path)
                else -> insert(path, request)
            }
            "PATCH", "PUT" -> when {
                path !in UPDATABLE -> notAllowed(request, path)
                id == null -> HttpResponse.error(
                    400,
                    "PATCH needs an ${TasksContract.ID} - use /$version/$path/{id}",
                )
                else -> update(path, id, request)
            }
            "DELETE" -> when {
                path !in DELETABLE -> notAllowed(request, path)
                id == null && path == TaskTags.PATH -> deleteTagLink(request)
                id == null -> HttpResponse.error(
                    400,
                    "DELETE needs an ${TasksContract.ID} - use /$version/$path/{id}",
                )
                else -> delete(path, id)
            }
            else -> HttpResponse(
                405,
                HttpResponse.jsonError(
                    "${request.method} is not supported. Use ${methodsFor(path).joinToString(", ")}.",
                ),
                headers = mapOf("Allow" to methodsFor(path).joinToString(", ")),
            )
        }
    }

    private suspend fun list(path: String, request: HttpRequest): HttpResponse {
        val args = queryArgs(path, request.query)
        val rows = api.query(path, args)
        return HttpResponse(200, page(rows, args).toString())
    }

    private fun queryArgs(path: String, query: Map<String, List<String>>): ApiQueryArgs =
        ApiQueryArgs.build(TasksContract.paramsFor(path)) {
            query.forEach { (key, values) -> putAll(key, values) }
        }

    private fun page(rows: ApiRows, args: ApiQueryArgs) = buildJsonObject {
        val columns = rows.columns
        put(
            "rows",
            buildJsonArray {
                rows.rows.forEach { values ->
                    add(
                        buildJsonObject {
                            columns.forEachIndexed { index, column ->
                                put(column, values[index].toJson())
                            }
                        },
                    )
                }
            },
        )
        put("total", rows.total)
        put("offset", args.offset)
        put("has_more", args.offset + rows.size < rows.total)
    }

    private suspend fun item(path: String, id: Long): HttpResponse {
        val rows = api.queryById(path, id)
        val values = rows.rows.firstOrNull() ?: return notFound("/$version/$path/$id")
        return HttpResponse(200, row(rows.columns, values).toString())
    }

    private fun row(columns: List<String>, values: Array<Any?>) = buildJsonObject {
        columns.forEachIndexed { index, column -> put(column, values[index].toJson()) }
    }

    private suspend fun insert(path: String, request: HttpRequest): HttpResponse {
        val insertion = api.insert(path, values(request))
        return HttpResponse(
            201,
            buildJsonObject {
                put(TasksContract.ID, insertion.id)
                insertion.created?.let { put("created", it) }
            }.toString(),
            headers = mapOf("Location" to "/$version/$path/${insertion.id}"),
        )
    }

    private suspend fun update(path: String, id: Long, request: HttpRequest): HttpResponse {
        val changed = api.update(path, id, values(request))
        if (changed == 0) {
            return notFound("/$version/$path/$id")
        }
        return HttpResponse(200, buildJsonObject { put("updated", changed) }.toString())
    }

    private suspend fun delete(path: String, id: Long): HttpResponse {
        val deleted = api.delete(path, id)
        if (deleted == 0) {
            return notFound("/$version/$path/$id")
        }
        return HttpResponse(200, buildJsonObject { put("deleted", deleted) }.toString())
    }

    private suspend fun deleteTagLink(request: HttpRequest): HttpResponse {
        val args = ApiQueryArgs.build(listOf(TaskTags.PARAM_TASK, TaskTags.PARAM_TAG)) {
            request.query.forEach { (key, values) -> putAll(key, values) }
        }
        val taskId = args.long(TaskTags.PARAM_TASK)
            ?: throw IllegalArgumentException("${TaskTags.PARAM_TASK} is required")
        val tagId = args.long(TaskTags.PARAM_TAG)
            ?: throw IllegalArgumentException("${TaskTags.PARAM_TAG} is required")
        val deleted = api.deleteTaskTag(taskId, tagId)
        if (deleted == 0) {
            return HttpResponse.error(404, "No tag $tagId on task $taskId")
        }
        return HttpResponse(200, buildJsonObject { put("deleted", deleted) }.toString())
    }

    private fun values(request: HttpRequest): ApiValues {
        if (request.body.isBlank()) {
            throw IllegalArgumentException("A JSON object body is required")
        }
        val element = try {
            Json.parseToJsonElement(request.body)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("The body is not valid JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The body is not valid JSON: ${e.message}")
        }
        val body = element as? JsonObject
            ?: throw IllegalArgumentException("The body must be a JSON object")
        return ApiValues(body.mapValues { (key, value) -> value.value(key) })
    }

    private fun JsonElement.value(key: String): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> boolean
            longOrNull != null -> long
            doubleOrNull != null -> double
            else -> content
        }
        else -> throw IllegalArgumentException("'$key' must be a string, number, boolean or null")
    }

    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    private fun failure(error: Exception): HttpResponse = when (ApiErrors.classify(error)) {
        ApiFailure.NotFound -> HttpResponse.error(404, ApiErrors.explain(error))
        ApiFailure.ReadOnly -> HttpResponse.error(403, ApiErrors.explain(error))
        ApiFailure.BadRequest -> HttpResponse.error(400, ApiErrors.explain(error))
        ApiFailure.Remote -> HttpResponse.error(502, ApiErrors.explain(error))
        ApiFailure.Unknown -> HttpResponse.error(500, ApiErrors.explain(error))
    }

    private fun authorized(request: HttpRequest): Boolean {
        if (password.isEmpty()) return false
        return request.credentials().any { presented ->
            MessageDigest.isEqual(password.toByteArray(), presented.toByteArray())
        }
    }

    private fun HttpRequest.credentials(): List<String> = buildList {
        authorization?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
            ?.let { add(it.substring(BEARER.length).trim()) }
        header(SERVER_PASSWORD)?.trim()?.let { add(it) }
    }

    private fun parseId(segment: String): Long =
        segment.toLongOrNull() ?: throw IllegalArgumentException(
            "'$segment' is not a numeric ${TasksContract.ID}",
        )

    private fun notFound(target: String) =
        HttpResponse.error(404, "Nothing at $target")

    private fun notAllowed(request: HttpRequest, path: String) = HttpResponse(
        405,
        HttpResponse.jsonError(
            "${request.method} is not supported on /$version/$path." +
                " Supported: ${methodsFor(path).joinToString(", ")}.",
        ),
        headers = mapOf("Allow" to methodsFor(path).joinToString(", ")),
    )

    private fun methodsFor(path: String): List<String> = buildList {
        add("GET")
        if (path in POSTABLE) add("POST")
        if (path in UPDATABLE) add("PATCH")
        if (path in DELETABLE) add("DELETE")
    }

    private fun health() = buildJsonObject {
        put("status", "ok")
        put("version", version)
        put("authority", TasksContract.AUTHORITY)
    }

    private fun index() = buildJsonObject {
        put("version", version)
        put("authority", TasksContract.AUTHORITY)
        putJsonArray("collections") {
            TasksContract.COLLECTIONS.forEach { path ->
                add(
                    buildJsonObject {
                        put("path", path)
                        put("methods", JsonArray(methodsFor(path).map(::JsonPrimitive)))
                        put("params", JsonArray(TasksContract.paramsFor(path).map(::JsonPrimitive)))
                        put(
                            "columns",
                            JsonArray(TasksContract.columnsFor(path).map(::JsonPrimitive)),
                        )
                    },
                )
            }
        }
    }

    companion object {
        const val API_VERSION = "v0"
        const val HEALTH = "health"
        const val BEARER = "Bearer "
        const val AUTHORIZATION = "Authorization"
        const val SERVER_PASSWORD = "X-Server-Password"

        val POSTABLE = setOf(
            TasksContract.Tasks.PATH,
            TasksContract.Reminders.PATH,
            TaskTags.PATH,
            TasksContract.Lists.PATH,
            TasksContract.Tags.PATH,
            TasksContract.Places.PATH,
        )

        val UPDATABLE = setOf(
            TasksContract.Tasks.PATH,
            TasksContract.Reminders.PATH,
            TasksContract.Lists.PATH,
            TasksContract.Tags.PATH,
            TasksContract.Places.PATH,
        )

        val DELETABLE = POSTABLE
    }
}
