package com.maxim.ybookdownloader.data

import android.content.Context
import com.maxim.ybookdownloader.util.BookReference
import com.maxim.ybookdownloader.util.BookUrlParser
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BookRepository(private val context: Context) {
    private val webClient = OkHttpClient.Builder().build()
    private val api = Retrofit.Builder()
        .baseUrl(BookmateApiFactory.BASE_URL)
        .client(webClient)
        .build()
        .create(BookmateApi::class.java)

    data class ResourceInfo(
        val id: String,
        val type: ResourceType,
        val title: String,
        val coverUrl: String?,
        val authors: List<String> = emptyList(),
        val narrators: List<String> = emptyList(),
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

    data class LibraryItem(
        val id: String,
        val type: ResourceType,
        val title: String,
        val coverUrl: String?,
        val authors: List<String>,
        val sourceUrl: String,
        val workKey: String,
        val state: String? = null,
        val libraryCardUuid: String? = null
    )

    data class LibraryPage(
        val items: List<LibraryItem>,
        val cardCount: Int,
        val hasMore: Boolean
    )

    data class CatalogItem(
        val id: String,
        val type: ResourceType,
        val title: String,
        val coverUrl: String?,
        val authors: List<String>,
        val sourceUrl: String,
        val workKey: String,
        val inLibrary: Boolean = false,
        val hasText: Boolean = type == ResourceType.BOOK,
        val hasAudio: Boolean = type == ResourceType.AUDIOBOOK
    )

    data class CatalogPage(
        val items: List<CatalogItem>,
        val cursor: String,
        val hasMore: Boolean
    )

    data class HomeSection(
        val title: String,
        val url: String,
        val items: List<CatalogItem>
    )

    private data class SearchCandidate(
        val type: ResourceType,
        val id: String,
        val title: String,
        val coverUrl: String?,
        val authors: List<String>,
        val inLibrary: Boolean = false
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
        val narrators = if (ref.type == ResourceType.AUDIOBOOK) {
            extractPeople(resource, listOf("narrators", "narrators_objects"))
        } else {
            emptyList()
        }

        return ResourceInfo(ref.id, ref.type, title, cover, authors, narrators, body)
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

        // Самый надёжный источник связи между текстом и аудио — переключатель
        // «Текст / Аудио» на публичной странице Яндекс Книг. В отличие от поиска
        // он связывает именно версии одного произведения, а не просто совпадения
        // по названию. Если сайт временно недоступен, используем строгий GraphQL fallback.
        val publicLookup = runCatching { findPublicCounterpart(ref, counterpartType) }

        val counterpart = runCatching {
            if (publicLookup.isSuccess) {
                // Если публичная карточка загрузилась успешно, отсутствие кнопки
                // второй версии считаем достоверным ответом и НЕ подмешиваем
                // похожую книгу из поиска. Именно это устраняет ложные значки.
                publicLookup.getOrNull()?.let { getResourceInfo(it, token) }
            } else {
                // Fallback нужен только если сам сайт карточки не удалось прочитать.
                val candidate = searchCounterpart(initial, counterpartType, token)
                    ?: return@runCatching null
                val candidateUrl = when (candidate.type) {
                    ResourceType.BOOK -> "https://books.yandex.ru/books/${candidate.id}"
                    ResourceType.AUDIOBOOK -> "https://books.yandex.ru/audiobooks/${candidate.id}"
                }
                getResourceInfo(BookReference(candidate.id, candidateUrl, candidate.type), token)
            }
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
            .filter { candidate ->
                // Совпадения только по названию недостаточно: в каталоге много
                // разных произведений/изданий с одинаковыми названиями.
                if (initialAuthors.isEmpty()) false
                else candidate.authors.map(::normalize).any { it in initialAuthors }
            }
            .sortedByDescending { candidate ->
                candidate.authors.map(::normalize).count { it in initialAuthors }
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
        val inLibrary = obj["progress"]
            ?.let { it as? JsonObject }
            ?.get("inLibrary")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: false
        return SearchCandidate(type, id, title, cover, authors, inLibrary)
    }

    suspend fun searchCatalog(
        query: String,
        token: String,
        cursor: String = ""
    ): CatalogPage {
        val variables = buildJsonObject {
            put("query", buildJsonObject {
                put("cursor", cursor)
                put("noMisspell", false)
                put("query", query.trim())
                // Gateway accepts the whitelisted Search query only with an empty
                // types filter. Text/audio entities are filtered client-side below.
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
        check(response.isSuccessful) { "Ошибка поиска: HTTP ${response.code()}" }
        val body = response.body()?.string() ?: error("Пустой ответ поиска")
        val root = Json.parseToJsonElement(body).jsonObject
        val search = root["data"]?.let { it as? JsonObject }
            ?.get("search")?.let { it as? JsonObject }
        if (search == null) {
            val apiMessage = (root["errors"] as? JsonArray)
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
            error(apiMessage ?: "Некорректный ответ поиска")
        }
        val candidates = (search["page"] as? JsonArray).orEmpty().mapNotNull(::parseSearchCandidate)
        val nextCursor = search["cursor"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val grouped = candidates
            .groupBy { makeWorkKey(it.title, it.authors) }
            .values
            .map { versions ->
                val primary = versions.firstOrNull { it.type == ResourceType.BOOK } ?: versions.first()
                CatalogItem(
                    id = primary.id,
                    type = primary.type,
                    title = primary.title,
                    coverUrl = primary.coverUrl ?: versions.firstNotNullOfOrNull { it.coverUrl },
                    authors = primary.authors.ifEmpty { versions.flatMap { it.authors }.distinct() },
                    sourceUrl = if (primary.type == ResourceType.BOOK)
                        "https://books.yandex.ru/books/${primary.id}"
                    else "https://books.yandex.ru/audiobooks/${primary.id}",
                    workKey = makeWorkKey(primary.title, primary.authors),
                    inLibrary = versions.any { it.inLibrary },
                    // Наличие второй версии подтверждаем отдельно через карточку
                    // произведения. Сам Search может отдавать похожие издания рядом.
                    hasText = primary.type == ResourceType.BOOK,
                    hasAudio = primary.type == ResourceType.AUDIOBOOK
                )
            }

        return CatalogPage(grouped, nextCursor, nextCursor.isNotBlank() && nextCursor != cursor)
    }

    suspend fun getPopularSearches(token: String): List<String> {
        val response = api.getPopularSearches(token = token)
        if (!response.isSuccessful) return emptyList()
        val body = response.body()?.string() ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val arrays = sequenceOf(root["popular_searches"], root["data"], root["searches"])
            .mapNotNull { it as? JsonArray }
        return arrays.firstOrNull()?.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> sequenceOf("query", "text", "title", "name")
                    .mapNotNull { key -> item[key]?.jsonPrimitive?.contentOrNull }
                    .firstOrNull { it.isNotBlank() }
                else -> null
            }
        }?.filter { it.isNotBlank() }?.distinct()?.take(12).orEmpty()
    }

    /**
     * Главная страница Яндекс Книг серверно рендерится и содержит ссылки на
     * актуальные редакционные подборки. Названия и карточки читаются из HTML,
     * а недостающие данные первых карточек уточняются через REST API.
     */
    suspend fun getHomeSections(token: String): List<HomeSection> {
        val homeHtml = executeHtml(HOME_URL)
        val home = Jsoup.parse(homeHtml, HOME_URL)
        val sectionLinks = linkedMapOf<String, String>()
        home.select("a[href*='/section/all/']").forEach { anchor ->
            val href = absoluteHref(anchor)
            val title = cleanSectionTitle(anchor.text())
            if (href.isNotBlank() && title.isNotBlank() &&
                !title.equals("Показать все", ignoreCase = true) && href !in sectionLinks
            ) {
                sectionLinks[href] = title
            }
        }

        return sectionLinks.entries.take(HOME_SECTION_COUNT).mapNotNull { (url, title) ->
            runCatching {
                val parsed = parseSectionPage(url, HOME_SECTION_MAX_ITEMS)
                val enrichedPreview = parsed.take(HOME_PREVIEW_ITEMS).map { item ->
                    if (!item.coverUrl.isNullOrBlank() && item.authors.isNotEmpty() && item.title != "Книга") {
                        item
                    } else {
                        runCatching {
                            val info = getResourceInfo(
                                BookReference(item.id, item.sourceUrl, item.type),
                                token
                            )
                            item.copy(
                                title = info.title,
                                coverUrl = info.coverUrl ?: item.coverUrl,
                                authors = info.authors.ifEmpty { item.authors },
                                workKey = makeWorkKey(info.title, info.authors.ifEmpty { item.authors })
                            )
                        }.getOrDefault(item)
                    }
                }
                val mergedItems = mergeCatalogVersions(enrichedPreview + parsed.drop(HOME_PREVIEW_ITEMS))
                HomeSection(title = title, url = url, items = mergedItems)
            }.getOrNull()?.takeIf { it.items.isNotEmpty() }
        }
    }

    private fun executeHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .build()
        webClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Ошибка загрузки главной: HTTP ${response.code}" }
            return response.body?.string() ?: error("Пустая страница Яндекс Книг")
        }
    }

    private fun parseSectionPage(url: String, maxItems: Int): List<CatalogItem> {
        val doc = Jsoup.parse(executeHtml(url), url)
        val result = linkedMapOf<String, CatalogItem>()

        doc.select("a[href*='/books/'], a[href*='/audiobooks/']").forEach { anchor ->
            val href = absoluteHref(anchor)
            val ref = BookUrlParser.parse(href) ?: return@forEach
            val key = "${ref.type}|${ref.id}"
            if (key !in result && result.size >= maxItems) return@forEach

            val img = anchor.selectFirst("img") ?: findNearbyImage(anchor, ref)
            val title = extractHomeBookTitle(anchor, img)
            val cover = extractCoverUrl(anchor, img)
            val authors = extractAuthorsNear(anchor, ref)

            val previous = result[key]
            val mergedTitle = when {
                previous == null -> title
                previous.title == "Книга" && title != "Книга" -> title
                else -> previous.title
            }
            val mergedAuthors = previous?.authors?.takeIf { it.isNotEmpty() } ?: authors
            result[key] = CatalogItem(
                id = ref.id,
                type = ref.type,
                title = mergedTitle,
                coverUrl = previous?.coverUrl ?: cover,
                authors = mergedAuthors,
                sourceUrl = href,
                workKey = makeWorkKey(mergedTitle, mergedAuthors),
                inLibrary = previous?.inLibrary ?: false,
                hasText = ref.type == ResourceType.BOOK,
                hasAudio = ref.type == ResourceType.AUDIOBOOK
            )
        }
        return result.values.toList()
    }

    private fun mergeCatalogVersions(items: List<CatalogItem>): List<CatalogItem> {
        if (items.isEmpty()) return emptyList()
        return items
            .groupBy { item ->
                if (item.title == "Книга" || item.workKey.isBlank()) {
                    "${item.type}|${item.id}"
                } else {
                    item.workKey
                }
            }
            .values
            .map { versions ->
                val primary = versions.firstOrNull { it.type == ResourceType.BOOK } ?: versions.first()
                primary.copy(
                    coverUrl = primary.coverUrl ?: versions.firstNotNullOfOrNull { it.coverUrl },
                    authors = primary.authors.ifEmpty { versions.flatMap { it.authors }.distinct() },
                    inLibrary = versions.any { it.inLibrary },
                    // Не делаем вывод о второй версии только по соседней HTML-карточке:
                    // это давало ложные значки. Точная проверка выполняется лениво.
                    hasText = primary.type == ResourceType.BOOK,
                    hasAudio = primary.type == ResourceType.AUDIOBOOK
                )
            }
    }

    private fun cleanSectionTitle(raw: String): String = raw
        .replace(Regex("\\s*(?:Показать\\s+все|Всё|Все)\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun absoluteHref(anchor: Element): String {
        return anchor.absUrl("href").ifBlank {
            val raw = anchor.attr("href").trim()
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> "https://books.yandex.ru$raw"
                else -> raw
            }
        }
    }

    private fun extractHomeBookTitle(anchor: Element, img: Element?): String {
        val alt = img?.attr("alt")?.trim().orEmpty()
        val anchorText = anchor.text().trim()
        return sequenceOf(alt, anchor.attr("aria-label"), anchor.attr("title"), anchorText)
            .map { it.trim() }
            .firstOrNull {
                it.isNotBlank() &&
                    !it.equals("book cover", true) &&
                    !it.equals("image", true) &&
                    !it.equals("обложка", true)
            }
            ?: "Книга"
    }

    private fun findNearbyImage(anchor: Element, ref: BookReference): Element? {
        var current: Element? = anchor.parent()
        repeat(5) {
            val node = current ?: return null
            val refs = node.select("a[href*='/books/'], a[href*='/audiobooks/']")
                .mapNotNull { BookUrlParser.parse(absoluteHref(it)) }
                .map { "${it.type}|${it.id}" }
                .distinct()
            if (refs.size <= 2 && refs.any { it == "${ref.type}|${ref.id}" }) {
                node.selectFirst("img")?.let { return it }
            }
            current = node.parent()
        }
        return null
    }

    private fun extractCoverUrl(anchor: Element, img: Element?): String? {
        val candidates = mutableListOf<String>()
        if (img != null) {
            listOf("src", "data-src", "data-lazy-src").forEach { attr ->
                img.attr(attr).takeIf { it.isNotBlank() }?.let(candidates::add)
            }
            listOf("srcset", "data-srcset").forEach { attr ->
                img.attr(attr).takeIf { it.isNotBlank() }
                    ?.let(::pickSrcSetUrl)
                    ?.let(candidates::add)
            }
            img.parent()?.select("source[srcset], source[data-srcset]")?.forEach { source ->
                sequenceOf(source.attr("srcset"), source.attr("data-srcset"))
                    .firstOrNull { it.isNotBlank() }
                    ?.let(::pickSrcSetUrl)
                    ?.let(candidates::add)
            }
        }
        anchor.select("source[srcset], source[data-srcset]").forEach { source ->
            sequenceOf(source.attr("srcset"), source.attr("data-srcset"))
                .firstOrNull { it.isNotBlank() }
                ?.let(::pickSrcSetUrl)
                ?.let(candidates::add)
        }

        return candidates.asSequence()
            .mapNotNull(::normalizeImageUrl)
            .firstOrNull()
    }

    private fun pickSrcSetUrl(value: String): String? = value
        .split(',')
        .map { it.trim().substringBefore(' ').trim() }
        .filter { it.isNotBlank() }
        .lastOrNull()

    private fun normalizeImageUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("data:", ignoreCase = true)) return null
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://books.yandex.ru$value"
            else -> null
        }
    }

    private fun extractAuthorsNear(anchor: Element, ref: BookReference): List<String> {
        var current: Element? = anchor.parent()
        repeat(6) {
            val node = current ?: return emptyList()
            val refs = node.select("a[href*='/books/'], a[href*='/audiobooks/']")
                .mapNotNull { BookUrlParser.parse(absoluteHref(it)) }
                .map { "${it.type}|${it.id}" }
                .distinct()
            val authors = node.select("a[href*='/authors/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (authors.isNotEmpty() && refs.size <= 2 && refs.any { it == "${ref.type}|${ref.id}" }) {
                return authors
            }
            current = node.parent()
        }
        return emptyList()
    }

    private fun findPublicCounterpart(
        ref: BookReference,
        targetType: ResourceType
    ): BookReference? {
        val sourceUrl = ref.sourceUrl.takeIf { it.isNotBlank() } ?: when (ref.type) {
            ResourceType.BOOK -> "https://books.yandex.ru/books/${ref.id}"
            ResourceType.AUDIOBOOK -> "https://books.yandex.ru/audiobooks/${ref.id}"
        }
        val doc = Jsoup.parse(executeHtml(sourceUrl), sourceUrl)
        val targetLabel = if (targetType == ResourceType.BOOK) "текст" else "аудио"

        // На карточке Яндекс Книг присутствует переключатель «Текст / Аудио».
        // Ссылки из него являются прямой связью между двумя версиями произведения.
        val labelled = doc.select("a[href*='/books/'], a[href*='/audiobooks/']")
            .asSequence()
            .filter { normalize(it.text()) == targetLabel }
            .mapNotNull { anchor -> BookUrlParser.parse(absoluteHref(anchor)) }
            .firstOrNull { candidate ->
                candidate.type == targetType &&
                    !(candidate.type == ref.type && candidate.id == ref.id)
            }
        if (labelled != null) return labelled

        return null
    }

    /** Проверяет реальные доступные версии произведения по его карточке. */
    suspend fun verifyAvailability(
        ref: BookReference,
        token: String
    ): Pair<Boolean, Boolean> {
        val bundle = getResourceBundle(ref, token)
        return (bundle.text != null) to (bundle.audio != null)
    }

    private fun extractAuthors(resource: JsonObject): List<String> =
        extractPeople(resource, listOf("authors_objects", "authors"))

    private fun extractPeople(resource: JsonObject, keys: List<String>): List<String> {
        for (key in keys) {
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
        private const val HOME_URL = "https://books.yandex.ru/"
        private const val HOME_SECTION_COUNT = 4
        private const val HOME_PREVIEW_ITEMS = 10
        private const val HOME_SECTION_MAX_ITEMS = 40

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

    /** Одна страница личной библиотеки. Небольшой pageSize нужен, чтобы
     * корректно работать даже если сервер сам ограничивает максимальный limit. */
    suspend fun getMyLibraryPage(token: String, limit: Int = 20, offset: Int = 0): LibraryPage {
        val response = api.getLibraryCards(token = token, limit = limit, offset = offset)
        check(response.isSuccessful) { "Ошибка библиотеки: HTTP ${response.code()}" }
        val body = response.body()?.string() ?: error("Пустой ответ библиотеки")
        val root = Json.parseToJsonElement(body).jsonObject
        val cards = (root["library_cards"] as? JsonArray).orEmpty()
        val items = mutableListOf<LibraryItem>()
        cards.forEach { element ->
            val card = element as? JsonObject ?: return@forEach
            val cardUuid = card["uuid"]?.jsonPrimitive?.contentOrNull
            val state = card["state"]?.jsonPrimitive?.contentOrNull
            parseLibraryResource(card["book"], ResourceType.BOOK, state, cardUuid)?.let(items::add)
            parseLibraryResource(card["audiobook"], ResourceType.AUDIOBOOK, state, cardUuid)?.let(items::add)
        }
        return LibraryPage(
            items = items.distinctBy { "${it.type}|${it.id}" },
            cardCount = cards.size,
            hasMore = cards.size >= limit
        )
    }

    /**
     * В library_cards API добавляется именно UUID базовой книги (book_uuid).
     * У ссылок на аудиокниги UUID может отличаться, поэтому для аудиоверсии
     * сначала получаем связанную текстовую карточку через переключатель
     * «Текст / Аудио» на странице Яндекс Книг.
     */
    suspend fun addResourceToLibrary(
        resourceId: String,
        resourceType: ResourceType,
        sourceUrl: String,
        token: String
    ): Boolean {
        val bookUuid = if (resourceType == ResourceType.BOOK) {
            resourceId
        } else {
            val audioRef = BookReference(
                id = resourceId,
                sourceUrl = sourceUrl.ifBlank { "https://books.yandex.ru/audiobooks/$resourceId" },
                type = ResourceType.AUDIOBOOK
            )

            // library_cards принимает book_uuid. Для аудиокниги нельзя передавать
            // UUID аудиоресурса: сервер отвечает 404. Получаем связанную текстовую
            // карточку через реальный переключатель «Текст / Аудио», а GraphQL
            // используется только как строгий fallback в getResourceBundle().
            getResourceBundle(audioRef, token).text?.id
                ?: error("Не удалось определить карточку Яндекс Книг для добавления этой аудиокниги")
        }
        return addToLibrary(bookUuid, token)
    }

    suspend fun addToLibrary(bookUuid: String, token: String): Boolean {
        val payload = buildJsonObject { put("book_uuid", bookUuid) }.toString()
        val response = api.addLibraryCard(
            token = token,
            body = payload.toRequestBody(JSON_MEDIA_TYPE)
        )
        check(response.isSuccessful) { "Не удалось добавить в избранное: HTTP ${response.code()}" }
        return true
    }

    suspend fun removeFromLibrary(cardUuid: String, token: String): Boolean {
        val response = api.removeLibraryCard(cardUuid = cardUuid, token = token)
        check(response.isSuccessful) { "Не удалось удалить из избранного: HTTP ${response.code()}" }
        return true
    }

    suspend fun findLibraryCardUuid(
        bookId: String,
        token: String,
        workKey: String = ""
    ): String? {
        var offset = 0
        val limit = 20
        while (true) {
            val page = getMyLibraryPage(token, limit, offset)
            page.items.firstOrNull {
                it.id == bookId || (workKey.isNotBlank() && it.workKey == workKey)
            }?.libraryCardUuid?.let { return it }
            if (!page.hasMore || page.cardCount == 0) return null
            offset += page.cardCount
        }
    }

    private fun parseLibraryResource(
        element: JsonElement?,
        type: ResourceType,
        state: String?,
        libraryCardUuid: String?
    ): LibraryItem? {
        val resource = element as? JsonObject ?: return null
        val id = resource["uuid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val title = sequenceOf(
            resource["title"]?.jsonPrimitive?.contentOrNull,
            resource["name"]?.jsonPrimitive?.contentOrNull
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Без названия"
        val cover = (resource["cover"] as? JsonObject)?.let { coverObject ->
            coverObject["large"]?.jsonPrimitive?.contentOrNull
                ?: coverObject["url"]?.jsonPrimitive?.contentOrNull
                ?: coverObject["small"]?.jsonPrimitive?.contentOrNull
        }
        val authors = extractAuthors(resource)
        val sourceUrl = when (type) {
            ResourceType.BOOK -> "https://books.yandex.ru/books/$id"
            ResourceType.AUDIOBOOK -> "https://books.yandex.ru/audiobooks/$id"
        }
        return LibraryItem(
            id = id,
            type = type,
            title = title,
            coverUrl = cover,
            authors = authors,
            sourceUrl = sourceUrl,
            workKey = makeWorkKey(title, authors),
            state = state,
            libraryCardUuid = libraryCardUuid
        )
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
