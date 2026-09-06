package com.maxim.ybookdownloader.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.io.File

class BookRepository(private val context: Context) {
    private val api = Retrofit.Builder()
        .baseUrl(BookmateApiFactory.BASE_URL)
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BookmateApi::class.java)

    data class BookInfo(val id: String, val title: String, val coverUrl: String?, val rawJson: String)

    suspend fun getBookInfo(id: String, token: String): BookInfo {
        val response = api.getBookInfo(id, token)
        check(response.isSuccessful) { "Ошибка API: HTTP ${response.code()}" }
        val body = response.body()?.string() ?: error("Пустой ответ API")
        val root = Json.parseToJsonElement(body).jsonObject
        val book = root["book"]?.jsonObject ?: error("В ответе нет объекта book")
        val title = book["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Без названия"
        val cover = book["cover"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull
        return BookInfo(id, title, cover, body)
    }

    suspend fun downloadEpub(id: String, token: String, title: String): File {
        val response = api.downloadEpub(id, token)
        check(response.isSuccessful) { "Ошибка скачивания: HTTP ${response.code()}" }
        val file = File(context.cacheDir, "${safeName(title)}.epub")
        response.body()?.byteStream()?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            ?: error("Пустой файл")
        return file
    }

    private fun safeName(name: String): String = name.replace(Regex("[\\/:*?\"<>|]"), "").trim().ifBlank { "book" }
}
