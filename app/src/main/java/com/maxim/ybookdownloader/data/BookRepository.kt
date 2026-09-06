package com.maxim.ybookdownloader.data

import android.content.Context
import com.maxim.ybookdownloader.util.BookReference
import com.maxim.ybookdownloader.util.ResourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import java.io.File
import java.util.Locale
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
        val authors: List<String> = emptyList(),
        val rawJson: String = ""
    )

    data class ResourceBundle(
        val text: ResourceInfo?,
        val audio: ResourceInfo?,
        val initialType: ResourceType,
        val workKey: String
    )

    data class AudioTrack(
        val number: Int,
        val title: String,
        val minUrl: String?,
        val maxUrl: String?
    )

    private data class SearchCandidate(
        val type: ResourceType,
        val id: String,
        val title: String,
        val coverUrl: String?,
        val authors: List<String>
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
            ?: resource["name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "Без названия"
        val cover = resource["cover"]?.jsonObject
            ?.let { coverObject ->
                coverObject["large"]?.jsonPrimitive?.contentOrNull
                    ?: coverObject["url"]?.jsonPrimitive?.contentOrNull
                    ?: coverObject["small"]?.jsonPrimitive?.contentOrNull
            }
        val authors = extractAuthors(resource)

        return ResourceInfo(ref.id, ref.type, title, cover, authors, body)
    }

    /**
     * Получает произведение независимо от того, какую ссылку дал пользователь.
     * Сначала читаем точный ресурс из ссылки, затем через GraphQL Search ищем
     * вторую версию с тем же названием (TextBook/AudioBook). Если поиск API
     * изменится или второй версии нет, приложение продолжит работать с исходной.
     */
    suspend fun getResourceBundle(ref: BookReference, token: String): ResourceBundle {
        val initial = getResourceInfo(ref, token)
        var text: ResourceInfo? = if (initial.type == ResourceType.BOOK) initial else null
        var audio: ResourceInfo? = if (initial.type == ResourceType.AUDIOBOOK) initial else null

        val counterpartType = if (initial.type == ResourceType.BOOK) {
            ResourceType.AUDIOBOOK
        } else {
            ResourceType.BOOK
        }

        val counterpart = runCatching {
            val candidate = searchCounterpart(initial, counterpartType, token) ?: return@runCatching null
            getResourceInfo(
                BookReference(candidate.id, ref.sourceUrl, candidate.type),
                token
            )
        }.getOrNull()

        if (counterpart?.type == ResourceType.BOOK) text = counterpart
        if (counterpart?.type == ResourceType.AUDIOBOOK) audio = counterpart

        val canonical = text ?: audio ?: initial
        return ResourceBundle(
            text = text,
            audio = audio,
            initialType = ref.type,
            workKey = makeWorkKey(canonical.title, canonical.authors)
        )
    }

    private suspend fun searchCounterpart(
        initial: ResourceInfo,
        targetType: ResourceType,
        token: String
    ): SearchCandidate? {
        val variables = buildJsonObject {
            put("query", buildJsonObject {
                put("cursor", "")
                put("noMisspell", false)
                put("query", initial.title)
                put("types", buildJsonArray { })
            })
        }
        val payload = buildJsonObject {
            put("operationName", "Search")
            put("query", GQL_SEARCH)
            put("variables", variables)
        }.toString()

        val response = api.postGraphQl(
            BookmateApiFactory.GRAPHQL_URL,
            token,
            body = payload.toRequestBody(JSON_MEDIA_TYPE)
        )
        if (!response.isSuccessful) return null

        val body = response.body()?.string() ?: return null
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val page = root["data"]?.jsonObject
            ?.get("search")?.jsonObject
            ?.get("page")?.jsonArray
            ?: return null

        val initialTitle = normalize(initial.title)
        val initialAuthors = initial.authors.map(::normalize).filter { it.isNotBlank() }.toSet()

        return page.mapNotNull(::parseSearchCandidate)
            .asSequence()
            .filter { it.type == targetType }
            .filter { normalize(it.title) == initialTitle }
            .sortedByDescending { candidate ->
                if (initialAuthors.isEmpty()) 0
                else candidate.authors.map(::normalize).count { it in initialAuthors }
            }
            .firstOrNull()
    }

    private fun parseSearchCandidate(element: JsonElement): SearchCandidate? {
        val obj = element as? JsonObject ?: return null
        val type = when (obj["__typename"]?.jsonPrimitive?.contentOrNull) {
            "TextBook" -> ResourceType.BOOK
            "AudioBook" -> ResourceType.AUDIOBOOK
            else -> return null
        }
        val book = obj["book"]?.jsonObject ?: return null
        val id = book["uuid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val title = book["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val cover = book["cover"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val authors = (book["authors"] as? JsonArray)
            ?.mapNotNull { author ->
                (author as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            }
            .orEmpty()
        return SearchCandidate(type, id, title, cover, authors)
    }

    private fun extractAuthors(resource: JsonObject): List<String> {
        val candidates = listOf("authors_objects", "authors")
        for (key in candidates) {
            val array = resource[key] as? JsonArray ?: continue
            val names = array.mapNotNull { item ->
                when (item) {
                    is JsonObject -> item["name"]?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> item.contentOrNull
                    else -> null
                }
            }.filter { it.isNotBlank() }
            if (names.isNotEmpty()) return names
        }
        return emptyList()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun makeWorkKey(title: String, authors: List<String> = emptyList()): String {
            val authorPart = authors.firstOrNull().orEmpty()
            return normalize("$title|$authorPart")
        }

        fun normalize(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
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

            val title = sequenceOf(
                track["title"]?.jsonPrimitive?.contentOrNull,
                track["name"]?.jsonPrimitive?.contentOrNull,
                track["caption"]?.jsonPrimitive?.contentOrNull,
                track["chapter_title"]?.jsonPrimitive?.contentOrNull,
                (track["part"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
            ).firstOrNull { !it.isNullOrBlank() }?.trim()
                ?: "Глава ${index + 1}"

            AudioTrack(
                number = index + 1,
                title = title,
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
            if (existing != null && existing.size > CoverRegexes.MIN_VALID_COVER_BYTES) return
        }

        val response = api.downloadByUrl(coverUrl, token)
        if (!response.isSuccessful) return
        val bytes = response.body()?.bytes() ?: return
        if (bytes.size <= CoverRegexes.MIN_VALID_COVER_BYTES) return

        replaceZipEntry(epub, coverEntryName, bytes)
    }

    private fun findCoverEntry(epub: File): String? {
        ZipFile(epub).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?.let { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }
            val opfPath = container
                ?.let { CoverRegexes.FULL_PATH_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf", true) }?.name
                ?: return null

            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opf = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }

            val coverId = CoverRegexes.META_COVER_REGEX.find(opf)?.groupValues?.getOrNull(1)
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
        val item = CoverRegexes.ITEM_TAG_REGEX.findAll(opf)
            .map { it.value }
            .firstOrNull { tag -> CoverRegexes.ID_ATTR_REGEX.find(tag)?.groupValues?.getOrNull(1) == id }
            ?: return null
        return CoverRegexes.HREF_ATTR_REGEX.find(item)?.groupValues?.getOrNull(1)
    }

    private fun findEpub3CoverHref(opf: String): String? {
        val item = CoverRegexes.ITEM_TAG_REGEX.findAll(opf)
            .map { it.value }
            .firstOrNull { tag ->
                CoverRegexes.PROPERTIES_ATTR_REGEX.find(tag)?.groupValues?.getOrNull(1)
                    ?.split(Regex("\\s+"))
                    ?.any { it == "cover-image" } == true
            }
            ?: return null
        return CoverRegexes.HREF_ATTR_REGEX.find(item)?.groupValues?.getOrNull(1)
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

    private object CoverRegexes {
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


private const val GQL_SEARCH = """
query Search(${'$'}query: SearchParamsInput!) {
  search(query: ${'$'}query) {
    page {
      __typename
      ...searchSnippetAudioBookFragment
      ...searchSnippetTextBookFragment
      ...searchSnippetComicBookFragment
      ...searchSnippetTextSerialFragment
      ...bookshelfFragment
      ...personFragment
      ...publisherFragment
      ...seriesFragment
      ...topicFragment
      ...userFragment
    }
    cursor
    rankedFilter { filterType }
    misspell { correctedText correctionType }
  }
}
fragment coverFragment on Cover { url ratio backgroundColorHex }
fragment personFragment on Person { avatar { __typename ...coverFragment } name uuid worksCount roles }
fragment bookFragment on Book { annotation name cover { __typename ...coverFragment } uuid authors { __typename ...personFragment } ageRestriction editorAnnotation }
fragment publisherFragment on Publisher { avatar { __typename ...coverFragment } name uuid worksCount }
fragment publisherBookFragment on Book { publisher { __typename ...publisherFragment } }
fragment translatorsBookFragment on Book { translators { __typename ...personFragment } }
fragment topicsBookFragment on Book { topics { name totalBook uuid } }
fragment subscriptionLevelsFragment on Book { subscriptionLevels }
fragment snippetBookFragment on Book { __typename ...bookFragment ...publisherBookFragment ...translatorsBookFragment ...topicsBookFragment ...subscriptionLevelsFragment }
fragment bookTagFragment on Tag { name value }
fragment narratorsAudioBookFragment on AudioBook { narrators { __typename ...personFragment } }
fragment progressFragment on Progress { finished inLibrary progress isPublic }
fragment progressAudioBookFragment on AudioBook { progress { __typename ...progressFragment } }
fragment listenersCountAudioBookFragment on AudioBook { listenersCount }
fragment searchSnippetAudioBookFragment on AudioBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...narratorsAudioBookFragment ...progressAudioBookFragment ...listenersCountAudioBookFragment }
fragment progressTextBookFragment on TextBook { progress { __typename ...progressFragment } }
fragment readersCountTextBookFragment on TextBook { readersCount }
fragment searchSnippetTextBookFragment on TextBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...progressTextBookFragment ...readersCountTextBookFragment }
fragment progressComicBookFragment on ComicBook { progress { __typename ...progressFragment } }
fragment readersCountComicBookFragment on ComicBook { readersCount }
fragment searchSnippetComicBookFragment on ComicBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...progressComicBookFragment ...readersCountComicBookFragment }
fragment textSerialFragment on TextSerial { book { __typename ...bookFragment } }
fragment episodesTextSerialFragment on TextSerial { episodes { total } }
fragment readersCountTextSerialFragment on TextSerial { readersCount }
fragment searchSnippetTextSerialFragment on TextSerial { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...textSerialFragment ...episodesTextSerialFragment ...readersCountTextSerialFragment }
fragment userFragment on User { avatar { __typename ...coverFragment } name uuid followersCount login }
fragment bookshelfFragment on Bookshelf { cover { __typename ...coverFragment } name uuid user { __typename ...userFragment } posts { total } followersCount description }
fragment seriesFragment on Series { authors { __typename ...personFragment } cover { __typename ...coverFragment } name uuid items { followersCount total } }
fragment topicFragment on Topic { name slug totalBook uuid parent { name slug totalBook uuid } }
"""
