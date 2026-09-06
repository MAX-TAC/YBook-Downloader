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
    val resourceType: String = "BOOK",
    val format: String,
    val mime: String,
    val uri: String,
    val uris: List<String> = emptyList(),
    val displayPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    // v0.5.0: общий ключ связывает текстовую и аудиоверсию одного произведения.
    // Для старой истории поле пустое; UI дополнительно умеет сопоставлять по названию.
    val workKey: String = "",
    val authors: String = "",
    // v0.7.0: для аудиокниг сохраняем конкретные номера глав, чтобы
    // повторные скачивания можно было объединять при «Поделиться».
    val chapterNumbers: List<Int> = emptyList(),
    val totalChapters: Int = 0,
    val audioQuality: String = ""
)

/**
 * Небольшое локальное хранилище истории скачиваний.
 * История хранится только внутри приложения и не затрагивает сами файлы в Downloads.
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
            addAll(load().filterNot { it.id == item.id })
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
