package com.maxim.ybookdownloader.data

import android.content.Context
import com.maxim.ybookdownloader.util.BookReference
import com.maxim.ybookdownloader.util.ResourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BookRepository(private val context: Context) {
    private val api = Retrofit.Builder()
        .baseUrl(BookmateApiFactory.BASE_URL)
        .client(OkHttpClient.Builder().build())
        .build()
        .create(BookmateApi::class.java)

    data class ResourceInfo(
        val id: String,
        val type: ResourceType,
        val title: String,
        val coverUrl: String?,
        val rawJson: String
    )

    data class AudioTrack(
        val number: Int,
        val minUrl: String?,
        val maxUrl: String?
    )

    suspend fun getResourceInfo(ref: BookReference, token: String): ResourceInfo {
        val response = when (ref.type) {
            ResourceType.BOOK -> api.getBookInfo(ref.id, token)
            ResourceType.AUDIOBOOK -> api.getAudiobookInfo(ref.id, token)
        }
        check(response.isSuccessful) { "Ошибка API: HTTP ${response.code()}" }

        val body = response.body()?.string() ?: error("Пустой ответ API")
        val root = Json.parseToJsonElement(body).jsonObject
        val objectName = when (ref.type) {
            ResourceType.BOOK -> "book"
            ResourceType.AUDIOBOOK -> "audiobook"
        }
        val resource = root[objectName]?.jsonObject
            ?: error("В ответе нет объекта $objectName")

        val title = resource["title"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "Без названия"
        val cover = resource["cover"]?.jsonObject
            ?.get("large")
            ?.jsonPrimitive
            ?.contentOrNull

        return ResourceInfo(ref.id, ref.type, title, cover, body)
    }

    /**
     * Сохраняет рабочую EPUB-копию во внутреннем каталоге приложения.
     * Если Yandex/Bookmate отдаёт EPUB с пустым cover.jpg, подставляем настоящую
     * обложку из metadata API. Это важно для Kindle и других внешних читалок.
     */
    suspend fun downloadEpub(
        id: String,
        token: String,
        coverUrl: String? = null,
        force: Boolean = false
    ): File {
        val booksDir = File(context.filesDir, "books").apply {
            if (!exists() && !mkdirs()) error("Не удалось создать внутренний каталог книг")
        }
        val file = File(booksDir, "$id.epub")

        if (force || !file.exists() || file.length() == 0L) {
            val response = api.downloadEpub(id, token)
            check(response.isSuccessful) { "Ошибка скачивания: HTTP ${response.code()}" }
            try {
                val body = response.body() ?: error("Пустой файл")
                body.byteStream().use { input ->
                    file.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                check(file.length() > 0L) { "Получен пустой EPUB" }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

        if (!coverUrl.isNullOrBlank()) {
            runCatching { ensureEmbeddedCover(file, coverUrl, token) }
        }
        return file
    }

    suspend fun getAudiobookTracks(id: String, token: String): List<AudioTrack> {
        val response = api.getAudiobookPlaylist(id, token)
        check(response.isSuccessful) { "Ошибка плейлиста: HTTP ${response.code()}" }
        val body = response.body()?.string() ?: error("Пустой плейлист аудиокниги")
        val root = Json.parseToJsonElement(body).jsonObject
        val tracks = root["tracks"]?.jsonArray ?: error("В аудиокниге не найдены дорожки")

        return tracks.mapIndexedNotNull { index, element ->
            val track = element.jsonObject
            val offline = track["offline"]?.jsonObject ?: return@mapIndexedNotNull null
            val minUrl = offline["min_bit_rate"]?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull
                ?.toDirectM4aUrl()
            val maxUrl = offline["max_bit_rate"]?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull
                ?.toDirectM4aUrl()

            if (minUrl == null && maxUrl == null) return@mapIndexedNotNull null
            AudioTrack(
                number = index + 1,
                minUrl = minUrl ?: maxUrl,
                maxUrl = maxUrl ?: minUrl
            )
        }
    }

    suspend fun downloadAudioTrack(url: String, token: String, target: File): File {
        val response = api.downloadByUrl(url, token)
        check(response.isSuccessful) { "Ошибка скачивания аудио: HTTP ${response.code()}" }
        val body = response.body() ?: error("Получен пустой аудиофайл")
        target.parentFile?.mkdirs()
        try {
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(target.length() > 0L) { "Получен пустой аудиофайл" }
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private suspend fun ensureEmbeddedCover(epub: File, coverUrl: String, token: String) {
        val coverEntryName = findCoverEntry(epub) ?: return

        ZipFile(epub).use { zip ->
            val existing = zip.getEntry(coverEntryName)
            if (existing != null && existing.size > MIN_VALID_COVER_BYTES) return
        }

        val response = api.downloadByUrl(coverUrl, token)
        if (!response.isSuccessful) return
        val bytes = response.body()?.bytes() ?: return
        if (bytes.size <= MIN_VALID_COVER_BYTES) return

        replaceZipEntry(epub, coverEntryName, bytes)
    }

    private fun findCoverEntry(epub: File): String? {
        ZipFile(epub).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?.let { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }
            val opfPath = container
                ?.let { FULL_PATH_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf", true) }?.name
                ?: return null

            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opf = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }

            val coverId = META_COVER_REGEX.find(opf)?.groupValues?.getOrNull(1)
            val href = when {
                coverId != null -> findItemHrefById(opf, coverId)
                else -> findEpub3CoverHref(opf)
            }

            if (href != null) {
                val base = opfPath.substringBeforeLast('/', "")
                return if (base.isBlank()) href else "$base/$href"
            }

            return zip.entries().asSequence()
                .map { it.name }
                .firstOrNull { name ->
                    name.contains("cover", true) &&
                        (name.endsWith(".jpg", true) ||
                            name.endsWith(".jpeg", true) ||
                            name.endsWith(".png", true) ||
                            name.endsWith(".webp", true))
                }
        }
    }

    private fun findItemHrefById(opf: String, id: String): String? {
        val item = ITEM_TAG_REGEX.findAll(opf)
            .map { it.value }
            .firstOrNull { tag -> ID_ATTR_REGEX.find(tag)?.groupValues?.getOrNull(1) == id }
            ?: return null
        return HREF_ATTR_REGEX.find(item)?.groupValues?.getOrNull(1)
    }

    private fun findEpub3CoverHref(opf: String): String? {
        val item = ITEM_TAG_REGEX.findAll(opf)
            .map { it.value }
            .firstOrNull { tag ->
                PROPERTIES_ATTR_REGEX.find(tag)?.groupValues?.getOrNull(1)
                    ?.split(Regex("\\s+"))
                    ?.any { it == "cover-image" } == true
            }
            ?: return null
        return HREF_ATTR_REGEX.find(item)?.groupValues?.getOrNull(1)
    }

    private fun replaceZipEntry(epub: File, targetEntry: String, replacement: ByteArray) {
        val temp = File(epub.parentFile, "${epub.name}.cover.tmp")
        var replaced = false

        ZipFile(epub).use { source ->
            ZipOutputStream(temp.outputStream().buffered()).use { output ->
                source.entries().asSequence().forEach { entry ->
                    val isTarget = entry.name == targetEntry
                    val newEntry = if (isTarget) {
                        // Для заменяемой обложки нельзя копировать старые size/CRC.
                        ZipEntry(entry.name).apply { time = entry.time }
                    } else {
                        // Сохраняем свойства остальных записей. Это особенно важно для
                        // стандартного EPUB-файла mimetype, который должен оставаться STORED.
                        ZipEntry(entry)
                    }

                    output.putNextEntry(newEntry)
                    if (!entry.isDirectory) {
                        if (isTarget) {
                            output.write(replacement)
                            replaced = true
                        } else {
                            source.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
                    output.closeEntry()
                }

                // Иногда manifest ссылается на cover.jpg, но самой записи в архиве нет.
                if (!replaced) {
                    output.putNextEntry(ZipEntry(targetEntry))
                    output.write(replacement)
                    output.closeEntry()
                }
            }
        }

        if (!temp.renameTo(epub)) {
            temp.copyTo(epub, overwrite = true)
            temp.delete()
        }
    }

    private fun String.toDirectM4aUrl(): String =
        replace(Regex("\\.m3u8(?=\\?|$)", RegexOption.IGNORE_CASE), ".m4a")

    private companion object {
        const val MIN_VALID_COVER_BYTES = 1024
        val FULL_PATH_REGEX = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val META_COVER_REGEX = Regex(
            """<meta[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val ITEM_TAG_REGEX = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
        val ID_ATTR_REGEX = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val HREF_ATTR_REGEX = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val PROPERTIES_ATTR_REGEX = Regex("""\bproperties\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    }
}
