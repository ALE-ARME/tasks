package org.tasks.markdown

import android.content.Context
import android.os.Build
import android.os.Environment
import co.touchlab.kermit.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tasks.data.dao.TaskDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkdownSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao
) {
    fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    suspend fun syncToMarkdown() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("markdown_sync_prefs", Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean("enabled", true)
                if (!enabled) return@withContext

                if (!isStoragePermissionGranted()) {
                    Logger.w("MarkdownSyncManager") { "Storage permission MANAGE_EXTERNAL_STORAGE not granted" }
                    return@withContext
                }

                val defaultPath = File(
                    Environment.getExternalStorageDirectory(),
                    "OBSIDIAN/RECORDS-OF-THE-ABYSS/tasks.md"
                ).absolutePath
                val filePath = prefs.getString("file_path", defaultPath) ?: defaultPath

                val file = File(filePath)
                val parentDir = file.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }

                val tasks = taskDao.getActiveTasks()
                val sb = StringBuilder()
                sb.append("# Tasks\n\n")

                val pending = tasks.filter { !it.isCompleted }
                val completed = tasks.filter { it.isCompleted }

                sb.append("## Da Completare\n")
                if (pending.isEmpty()) {
                    sb.append("_Nessun task in sospeso_\n\n")
                } else {
                    pending.forEach { task ->
                        sb.append("- [ ] ${task.title}\n")
                        val notes = task.notes
                        if (!notes.isNullOrBlank()) {
                            val indentedNotes = notes.replace("\n", "\n    ")
                            sb.append("    $indentedNotes\n")
                        }
                    }
                    sb.append("\n")
                }

                sb.append("## Completati\n")
                if (completed.isEmpty()) {
                    sb.append("_Nessun task completato_\n\n")
                } else {
                    completed.take(50).forEach { task ->
                        sb.append("- [x] ${task.title}\n")
                    }
                    sb.append("\n")
                }

                file.writeText(sb.toString())
                Logger.d("MarkdownSyncManager") { "Successfully synced tasks to $filePath" }
            } catch (e: Exception) {
                Logger.e(e) { "Error syncing tasks to markdown file" }
            }
        }
    }
}
