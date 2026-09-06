package com.maxim.ybookdownloader.util

import android.net.Uri

enum class ResourceType {
    BOOK,
    AUDIOBOOK
}

data class BookReference(
    val id: String,
    val sourceUrl: String,
    val type: ResourceType
)

object BookUrlParser {
    fun parse(text: String): BookReference? {
        val candidate = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?: text.trim()

        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "books.yandex.ru" && host != "bookmate.ru") return null

        val parts = uri.pathSegments
        if (parts.size < 2) return null

        val type = when (parts[0].lowercase()) {
            "books", "book" -> ResourceType.BOOK
            "audiobooks", "audiobook" -> ResourceType.AUDIOBOOK
            else -> return null
        }

        val id = parts[1].trim()
        if (id.isBlank()) return null

        return BookReference(id = id, sourceUrl = candidate, type = type)
    }
}
