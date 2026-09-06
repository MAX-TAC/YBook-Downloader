package com.maxim.ybookdownloader.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File

class BookRepository(private val context: Context) {
    private val api = Retrofit.Builder()
        .baseUrl(BookmateApiFactory.BASE_URL)
        .client(OkHttpClient.Builder().build())
        .build()
        .create(BookmateApi::class.java)

    data class BookInfo(
        val id: String,
        val title: String,
        val coverUrl: String?,
        val rawJson: String
    )

    suspend fun getBookInfo(id: String, token: String): BookInfo {
        val response = api.getBookInfo(id, token)
        check(response.isSuccessful) { "Ошибка API: HTTP ${response.code()}" }

        val body = response.body()?.string() ?: error("Пустой ответ API")
        val root = Json.parseToJsonElement(body).jsonObject
        val book = root["book"]?.jsonObject ?: error("В ответе нет объекта book")
        val title = book["title"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "Без названия"
        val cover = book["cover"]?.jsonObject
            ?.get("large")
            ?.jsonPrimitive
            ?.contentOrNull

        return BookInfo(id, title, cover, body)
    }

    /**
     * Сохраняет рабочую копию EPUB во внутреннем каталоге приложения.
     * Пользовательский файл в Downloads создаёт BookExporter.
     * Так рабочая копия не пересекается с временными файлами конвертации.
     */
    suspend fun downloadEpub(id: String, token: String, force: Boolean = false): File {
        val booksDir = File(context.filesDir, "books").apply {
            if (!exists() && !mkdirs()) error("Не удалось создать внутренний каталог книг")
        }
        val file = File(booksDir, "$id.epub")

        if (!force && file.exists() && file.length() > 0L) {
            return file
        }

        val response = api.downloadEpub(id, token)
        check(response.isSuccessful) { "Ошибка скачивания: HTTP ${response.code()}" }

        try {
            val body = response.body() ?: error("Пустой файл")
            body.byteStream().use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(file.length() > 0L) { "Получен пустой EPUB" }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
}
