package org.tasks.http

import org.tasks.api.ApiQueryArgs
import org.tasks.api.ApiQueryEngine
import org.tasks.api.ApiValues
import org.tasks.api.ApiWriter
import org.tasks.api.TasksContract.Lists
import org.tasks.api.TasksContract.Places
import org.tasks.api.TasksContract.Reminders
import org.tasks.api.TasksContract.Tags
import org.tasks.api.TasksContract.TaskTags
import org.tasks.api.TasksContract.Tasks

/**
 * The same writes the content provider performs, in the same order of validation, so an HTTP
 * client and a provider client cannot get different answers out of the same request.
 */
class DatabaseTaskApi(
    private val engine: ApiQueryEngine,
    private val writer: ApiWriter,
) : TaskApi {

    override suspend fun query(path: String, args: ApiQueryArgs) = engine.query(path, args)

    override suspend fun queryById(path: String, id: Long) = engine.queryById(path, id)

    override suspend fun insert(path: String, values: ApiValues): ApiInsertion = when (path) {
        Tasks.PATH -> ApiInsertion(writer.insertTask(values), created = null)
        Reminders.PATH -> ApiInsertion(writer.insertReminder(values), created = null)
        TaskTags.PATH -> ApiInsertion(writer.insertTaskTag(values), created = null)
        Lists.PATH -> ApiInsertion(writer.insertList(values), created = null)
        Tags.PATH -> writer.insertTag(values).let { ApiInsertion(it.id, it.created) }
        Places.PATH -> writer.insertPlace(values).let { ApiInsertion(it.id, it.created) }
        else -> throw IllegalArgumentException("/$path does not accept POST")
    }

    override suspend fun update(path: String, id: Long, values: ApiValues): Int = when (path) {
        Tasks.PATH -> writer.updateTask(id, values)
        Reminders.PATH -> writer.updateReminder(id, values)
        Lists.PATH -> writer.updateList(id, values)
        Tags.PATH -> writer.updateTag(id, values)
        Places.PATH -> writer.updatePlace(id, values)
        else -> throw IllegalArgumentException("/$path does not accept PATCH")
    }

    override suspend fun delete(path: String, id: Long): Int = when (path) {
        Tasks.PATH -> writer.deleteTask(id)
        Reminders.PATH -> writer.deleteReminder(id)
        TaskTags.PATH -> writer.deleteTaskTag(id)
        Lists.PATH -> writer.deleteList(id)
        Tags.PATH -> writer.deleteTag(id)
        Places.PATH -> writer.deletePlace(id)
        else -> throw IllegalArgumentException("/$path does not accept DELETE")
    }

    override suspend fun deleteTaskTag(taskId: Long, tagId: Long): Int =
        writer.deleteTaskTag(taskId, tagId)
}
