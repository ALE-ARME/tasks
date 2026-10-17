package org.tasks.http

import org.tasks.api.ApiQueryArgs
import org.tasks.api.ApiRows
import org.tasks.api.ApiValues

/**
 * The tasks API as the HTTP server sees it: the same collections, parameters and writes as the
 * content provider at `content://org.tasks.api/v0`.
 */
interface TaskApi {

    suspend fun query(path: String, args: ApiQueryArgs): ApiRows

    suspend fun queryById(path: String, id: Long): ApiRows

    suspend fun insert(path: String, values: ApiValues): ApiInsertion

    suspend fun update(path: String, id: Long, values: ApiValues): Int

    suspend fun delete(path: String, id: Long): Int

    suspend fun deleteTaskTag(taskId: Long, tagId: Long): Int
}

data class ApiInsertion(val id: Long, val created: Boolean?)
