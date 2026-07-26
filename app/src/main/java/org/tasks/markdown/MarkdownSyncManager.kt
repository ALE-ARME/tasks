package org.tasks.markdown

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import co.touchlab.kermit.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tasks.data.dao.AlarmDao
import org.tasks.data.dao.TagDao
import org.tasks.data.dao.TaskDao
import org.tasks.data.entity.Task
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkdownSyncManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val taskDao: TaskDao,
        private val tagDao: TagDao,
        private val alarmDao: AlarmDao,
    ) {
        fun isStoragePermissionGranted(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

        suspend fun syncToMarkdown() {
            withContext(Dispatchers.IO) {
                try {
                    val prefs = context.getSharedPreferences("markdown_sync_prefs", Context.MODE_PRIVATE)
                    val enabled = prefs.getBoolean("enabled", false)
                    if (!enabled) return@withContext

                    val filePath = prefs.getString("file_path", "") ?: ""
                    if (filePath.isBlank()) {
                        Logger.w("MarkdownSyncManager") { "Markdown file path is empty, skipping sync" }
                        return@withContext
                    }

                    val tasks = taskDao.getActiveTasks()
                    val sb = StringBuilder()
                    sb.append("# Tasks\n\n")

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                    val parentTasks = tasks.filter { it.parent == 0L }
                    val pending = parentTasks.filter { !it.isCompleted }
                    val completed = parentTasks.filter { it.isCompleted }

                    suspend fun formatTask(
                        task: Task,
                        indentLevel: Int,
                    ): String {
                        val res = StringBuilder()
                        val indent = "    ".repeat(indentLevel)
                        val checkbox = if (task.isCompleted) "[x]" else "[ ]"
                        res.append("$indent- $checkbox ${task.title ?: ""}")

                        // Tags
                        val tags = tagDao.getTagsForTask(task.id)
                        if (tags.isNotEmpty()) {
                            tags.forEach { tag ->
                                tag.name?.takeIf { it.isNotBlank() }?.let { tagName ->
                                    res.append(" #$tagName")
                                }
                            }
                        }

                        // Priority
                        when (task.priority) {
                            Task.Priority.HIGH -> res.append(" 🔴")
                            Task.Priority.MEDIUM -> res.append(" 🟡")
                            Task.Priority.LOW -> res.append(" 🔵")
                        }

                        // Due Date
                        if (task.dueDate > 0) {
                            res.append(" 📅 ${dateTimeFormat.format(Date(task.dueDate))}")
                        }

                        // Hide Until / Start Date
                        if (task.hideUntil > 0) {
                            res.append(" 🛫 ${dateFormat.format(Date(task.hideUntil))}")
                        }

                        // Recurrence
                        if (!task.recurrence.isNullOrBlank()) {
                            res.append(" 🔄 ${task.recurrence}")
                        }

                        // Alarms
                        val alarms = alarmDao.getAlarms(task.id)
                        if (alarms.isNotEmpty()) {
                            alarms.forEach { alarm ->
                                if (alarm.time > 0) {
                                    res.append(" ⏰ ${dateTimeFormat.format(Date(alarm.time))}")
                                }
                            }
                        }

                        res.append("\n")

                        // Notes
                        val notes = task.notes
                        if (!notes.isNullOrBlank()) {
                            val indentedNotes = notes.replace("\n", "\n$indent    ")
                            res.append("$indent    $indentedNotes\n")
                        }

                        // Subtasks
                        val subtasks = tasks.filter { it.parent == task.id }
                        for (sub in subtasks) {
                            res.append(formatTask(sub, indentLevel + 1))
                        }

                        return res.toString()
                    }

                    sb.append("## Da Completare\n")
                    if (pending.isEmpty()) {
                        sb.append("_Nessun task in sospeso_\n\n")
                    } else {
                        for (task in pending) {
                            sb.append(formatTask(task, 0))
                        }
                        sb.append("\n")
                    }

                    sb.append("## Completati\n")
                    if (completed.isEmpty()) {
                        sb.append("_Nessun task completato_\n\n")
                    } else {
                        for (task in completed.take(50)) {
                            sb.append(formatTask(task, 0))
                        }
                        sb.append("\n")
                    }

                    val content = sb.toString()
                    if (filePath.startsWith("content://")) {
                        val uri = Uri.parse(filePath)
                        context.contentResolver.openOutputStream(uri, "rwt")?.use { stream ->
                            stream.write(content.toByteArray(Charsets.UTF_8))
                        }
                    } else {
                        val file = File(filePath)
                        val parentDir = file.parentFile
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs()
                        }
                        file.writeText(content, Charsets.UTF_8)
                    }
                    Logger.d("MarkdownSyncManager") { "Successfully synced tasks to $filePath" }
                } catch (e: Exception) {
                    Logger.e(e) { "Error syncing tasks to markdown file" }
                }
            }
        }
    }
