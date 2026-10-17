package org.tasks.http

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.tasks.api.ApiQueryArgs
import org.tasks.api.ApiRows
import org.tasks.api.ApiValues
import org.tasks.api.TasksContract.TaskTags
import org.tasks.api.TasksContract.Tasks

@RunWith(RobolectricTestRunner::class)
class TaskApiRouterTest {

    private val api = FakeTaskApi()
    private val router = TaskApiRouter(api, TOKEN)

    @Test
    fun healthNeedsNoToken() = runTest {
        val response = router.handle(get("/health"))

        assertEquals(200, response.status)
        assertTrue(response.body, response.body.contains("\"status\":\"ok\""))
    }

    @Test
    fun everythingElseNeedsTheToken() = runTest {
        assertEquals(401, router.handle(get("/v0/tasks")).status)
        assertEquals(401, router.handle(get("/v0/tasks", "wrong")).status)
        assertEquals(200, router.handle(get("/v0/tasks", TOKEN)).status)
    }

    @Test
    fun readingACollectionReturnsAPage() = runTest {
        api.tasks = ApiRows(
            listOf(Tasks.ID, Tasks.TITLE),
            listOf(arrayOf<Any?>(7L, "Buy milk"), arrayOf<Any?>(8L, null)),
            12,
        )

        val response = router.handle(get("/v0/tasks?limit=2", TOKEN))

        assertEquals(200, response.status)
        val body = Json.parseToJsonElement(response.body).jsonObject
        assertEquals("2", api.queries.single().second.single("limit"))
        assertEquals(12, body["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, body["offset"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["has_more"]!!.jsonPrimitive.content)
        val rows = body["rows"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals(7, rows[0].jsonObject[Tasks.ID]!!.jsonPrimitive.content.toInt())
        assertEquals("Buy milk", rows[0].jsonObject[Tasks.TITLE]!!.jsonPrimitive.content)
        assertEquals(JsonNull, rows[1].jsonObject[Tasks.TITLE])
    }

    @Test
    fun anUnknownParameterIsTheCallersFault() = runTest {
        assertEquals(400, router.handle(get("/v0/tasks?nope=1", TOKEN)).status)
        assertEquals(400, router.handle(get("/v0/tasks?sort=sideways", TOKEN)).status)
    }

    @Test
    fun anUnknownCollectionIsNotFound() = runTest {
        assertEquals(404, router.handle(get("/v0/nope", TOKEN)).status)
        assertEquals(404, router.handle(get("/v1/tasks", TOKEN)).status)
    }

    @Test
    fun readingOneRowThatIsNotThereIsNotFound() = runTest {
        api.byId = ApiRows(listOf(Tasks.ID), emptyList(), 0)

        assertEquals(404, router.handle(get("/v0/tasks/9", TOKEN)).status)
    }

    @Test
    fun readingOneRowReturnsIt() = runTest {
        api.byId = ApiRows(listOf(Tasks.ID), listOf(arrayOf<Any?>(9L)), 1)

        val response = router.handle(get("/v0/tasks/9", TOKEN))

        assertEquals(200, response.status)
        assertEquals(9, Json.parseToJsonElement(response.body).jsonObject[Tasks.ID]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun creatingATaskReturnsItsIdAndLocation() = runTest {
        api.insertedId = 42

        val response = router.handle(
            post("/v0/tasks", """{"title":"Buy milk","due_date":"2026-09-12"}"""),
        )

        assertEquals(201, response.status)
        assertEquals("/v0/tasks/42", response.headers["Location"])
        assertEquals(42, Json.parseToJsonElement(response.body).jsonObject["id"]!!.jsonPrimitive.content.toInt())
        val (path, values) = api.inserts.single()
        assertEquals(Tasks.PATH, path)
        assertEquals("Buy milk", values.get(Tasks.TITLE))
        assertEquals("2026-09-12", values.get(Tasks.DUE_DATE))
    }

    @Test
    fun anInsertWithoutABodyIsRefused() = runTest {
        assertEquals(400, router.handle(post("/v0/tasks", "")).status)
        assertEquals(400, router.handle(post("/v0/tasks", "not json")).status)
        assertEquals(400, router.handle(post("/v0/tasks", "[]")).status)
    }

    @Test
    fun updatingAndDeletingReportWhatChanged() = runTest {
        assertEquals(200, router.handle(patch("/v0/tasks/7", """{"title":"Renamed"}""")).status)
        assertEquals(200, router.handle(delete("/v0/tasks/7")).status)

        api.updated = 0
        api.deleted = 0
        assertEquals(404, router.handle(patch("/v0/tasks/7", """{"title":"Renamed"}""")).status)
        assertEquals(404, router.handle(delete("/v0/tasks/7")).status)
    }

    @Test
    fun aCollectionThatDoesNotTakeTheMethodSaysWhichOnesItDoes() = runTest {
        val response = router.handle(patch("/v0/${TaskTags.PATH}/1", """{"tag_id":2}"""))

        assertEquals(405, response.status)
        assertEquals("GET, POST, DELETE", response.headers["Allow"])
        assertEquals(405, router.handle(post("/v0/accounts", """{"name":"x"}""")).status)
        assertEquals(405, router.handle(get("/v0/tasks/1", TOKEN, method = "BREW")).status)
    }

    @Test
    fun takingATagOffATaskUsesThePair() = runTest {
        val response = router.handle(delete("/v0/${TaskTags.PATH}?task_id=5&tag_id=3"))

        assertEquals(200, response.status)
        assertEquals(5L to 3L, api.takenTagLink)
        assertEquals(401, router.handle(delete("/v0/${TaskTags.PATH}?task_id=5")).status)
    }

    @Test
    fun anUnexpectedFailureIsTheAppsFault() = runTest {
        api.failure = RuntimeException("boom")

        assertEquals(500, router.handle(get("/v0/tasks", TOKEN)).status)
    }

    private fun get(target: String, token: String? = null, method: String = "GET") =
        HttpRequest(method, target, headers(token))

    private fun post(target: String, body: String, token: String? = TOKEN) =
        HttpRequest("POST", target, headers(token), body)

    private fun patch(target: String, body: String, token: String? = TOKEN) =
        HttpRequest("PATCH", target, headers(token), body)

    private fun delete(target: String, token: String? = TOKEN) =
        HttpRequest("DELETE", target, headers(token))

    private fun headers(token: String?): Map<String, String> =
        token?.let { mapOf("authorization" to "Bearer $it") }.orEmpty()

    private companion object {
        const val TOKEN = "s3cret"
    }
}

private class FakeTaskApi : TaskApi {

    var tasks = ApiRows(emptyList(), emptyList(), 0)
    var byId: ApiRows? = null
    var insertedId = 1L
    var updated = 1
    var deleted = 1
    var tagLink = 1
    var failure: Exception? = null

    val queries = mutableListOf<Pair<String, ApiQueryArgs>>()
    val inserts = mutableListOf<Pair<String, ApiValues>>()
    var takenTagLink: Pair<Long, Long>? = null

    override suspend fun query(path: String, args: ApiQueryArgs): ApiRows {
        failure?.let { throw it }
        queries.add(path to args)
        return tasks
    }

    override suspend fun queryById(path: String, id: Long): ApiRows {
        failure?.let { throw it }
        return byId ?: ApiRows(emptyList(), emptyList(), 0)
    }

    override suspend fun insert(path: String, values: ApiValues): ApiInsertion {
        failure?.let { throw it }
        inserts.add(path to values)
        return ApiInsertion(insertedId, created = null)
    }

    override suspend fun update(path: String, id: Long, values: ApiValues): Int {
        failure?.let { throw it }
        return updated
    }

    override suspend fun delete(path: String, id: Long): Int {
        failure?.let { throw it }
        return deleted
    }

    override suspend fun deleteTaskTag(taskId: Long, tagId: Long): Int {
        failure?.let { throw it }
        takenTagLink = taskId to tagId
        return tagLink
    }
}
