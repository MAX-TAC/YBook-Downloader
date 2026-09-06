package com.maxim.ybookdownloader.util

import android.net.Uri

data class BookReference(val id: String, val sourceUrl: String)

object BookUrlParser {
    fun parse(text: String): BookReference? {
        val candidate = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.value ?: text.trim()
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "books.yandex.ru" && host != "bookmate.ru") return null
        val parts = uri.pathSegments
        if (parts.size >= 2 && parts[0].equals("books", true)) {
            return BookReference(parts[1], candidate)
        }
        return null
    }
}
