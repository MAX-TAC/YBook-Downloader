package com.maxim.ybookdownloader.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class DownloadHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val title: String,
    val coverUrl: String? = null,
    val sourceUrl: String = "",
    val format: String,
    val mime: String,
    val uri: String,
    val displayPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Небольшое локальное хранилище истории скачиваний.
 * История хранится только внутри приложения и не затрагивает сами книги в Downloads.
 */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "download_history.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Synchronized
    fun load(): List<DownloadHistoryItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<DownloadHistoryItem>>(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(item: DownloadHistoryItem): List<DownloadHistoryItem> {
        val updated = buildList {
            add(item)
            addAll(load().filterNot { it.uri == item.uri })
        }.take(MAX_ITEMS)
        save(updated)
        return updated
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun save(items: List<DownloadHistoryItem>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(items), Charsets.UTF_8)
    }

    private companion object {
        const val MAX_ITEMS = 250
    }
}
