package com.maxim.ybookdownloader.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

class BookExporter(private val context: Context) {
    enum class Format(val label: String, val extension: String, val mime: String) {
        EPUB("EPUB", "epub", "application/epub+zip"),
        FB2("FB2", "fb2", "application/x-fictionbook+xml"),
        PDF("PDF", "pdf", "application/pdf"),
        TXT("TXT", "txt", "text/plain")
    }

    data class ExportResult(
        val uri: Uri,
        val displayPath: String
    )

    fun export(epub: File, title: String, format: Format): ExportResult {
        check(epub.exists()) { "Исходный EPUB не найден. Скачайте книгу заново." }

        val safe = safeName(title)
        val fileName = "$safe.${format.extension}"
        val generatedFile = when (format) {
            // EPUB уже является исходным форматом и используется только для обычного сохранения.
            Format.EPUB -> epub
            else -> createTemporaryExportFile(safe, format).also { out ->
                when (format) {
                    Format.FB2 -> epubToFb2(epub, out, title)
                    Format.PDF -> epubToPdf(epub, out, title)
                    Format.TXT -> epubToTxt(epub, out)
                    Format.EPUB -> Unit
                }
            }
        }

        return try {
            saveToDownloads(generatedFile, fileName, format.mime)
        } finally {
            if (format != Format.EPUB) generatedFile.delete()
        }
    }

    /** URI внутренней рабочей копии EPUB для передачи Kindle/другому приложению. */
    fun uriForSharing(epub: File): Uri {
        check(epub.exists()) { "EPUB не найден. Скачайте книгу заново." }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            epub
        )
    }

    private fun createTemporaryExportFile(safeTitle: String, format: Format): File {
        val exportDir = File(context.cacheDir, "exports").apply {
            if (!exists() && !mkdirs()) error("Не удалось создать временный каталог")
        }
        return File.createTempFile("${safeTitle.take(40)}-", ".${format.extension}", exportDir)
    }

    private fun epubToFb2(epub: File, out: File, title: String) {
        val text = extractText(epub)

        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.appendLine("<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">")
            writer.appendLine("  <description>")
            writer.appendLine("    <title-info>")
            writer.appendLine("      <book-title>${xmlEscape(title)}</book-title>")
            writer.appendLine("      <lang>ru</lang>")
            writer.appendLine("    </title-info>")
            writer.appendLine("  </description>")
            writer.appendLine("  <body>")
            writer.appendLine("    <section>")
            writer.appendLine("      <title><p>${xmlEscape(title)}</p></title>")

            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { paragraph ->
                    writer.appendLine("      <p>${xmlEscape(paragraph)}</p>")
                }

            writer.appendLine("    </section>")
            writer.appendLine("  </body>")
            writer.appendLine("</FictionBook>")
        }
    }

    private fun epubToTxt(epub: File, out: File) {
        out.writeText(extractText(epub), Charsets.UTF_8)
    }

    /**
     * Создаёт текстовый PDF. В v0.2.1 перенос строк был квадратичным по времени:
     * для каждой строки многократно измерялась почти вся оставшаяся строка.
     * breakText() сразу возвращает число помещающихся символов и поэтому
     * позволяет обрабатывать большие книги без минутного зависания.
     */
    private fun epubToPdf(epub: File, out: File, title: String) {
        val text = extractText(epub)
        val document = PdfDocument()

        val pageWidth = 595
        val pageHeight = 842
        val left = 42f
        val top = 48f
        val bottom = 48f
        val maxWidth = pageWidth - left - 42f
        val bodyLineHeight = 17f
        val paragraphGap = 6f

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = top

        fun startPage() {
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            )
            y = top
        }

        fun finishPage() {
            page?.let(document::finishPage)
            page = null
        }

        fun ensureVerticalSpace(height: Float) {
            if (page == null) startPage()
            if (y + height > pageHeight - bottom) {
                finishPage()
                startPage()
            }
        }

        fun drawLine(line: String, paint: Paint, lineHeight: Float) {
            ensureVerticalSpace(lineHeight)
            page!!.canvas.drawText(line, left, y, paint)
            y += lineHeight
        }

        fun drawWrappedParagraph(paragraph: String, paint: Paint, lineHeight: Float) {
            val value = paragraph.trim()
            if (value.isEmpty()) {
                y += paragraphGap
                return
            }

            var start = 0
            while (start < value.length) {
                var count = paint.breakText(
                    value,
                    start,
                    value.length,
                    true,
                    maxWidth,
                    null
                ).coerceAtLeast(1)

                var end = (start + count).coerceAtMost(value.length)

                // Если строка обрывается внутри слова, переносим целое слово на следующую строку.
                if (end < value.length) {
                    var wordBreak = end - 1
                    while (wordBreak > start && !value[wordBreak].isWhitespace()) {
                        wordBreak--
                    }
                    if (wordBreak > start) end = wordBreak
                }

                val line = value.substring(start, end).trim()
                if (line.isNotEmpty()) drawLine(line, paint, lineHeight)

                start = end
                while (start < value.length && value[start].isWhitespace()) start++
            }
            y += paragraphGap
        }

        try {
            startPage()
            drawWrappedParagraph(title, titlePaint, 24f)
            y += 8f

            text.lineSequence().forEach { paragraph ->
                drawWrappedParagraph(paragraph, bodyPaint, bodyLineHeight)
            }

            finishPage()
            out.outputStream().buffered().use(document::writeTo)
        } finally {
            if (page != null) {
                runCatching { finishPage() }
            }
            document.close()
        }
    }

    /**
     * Извлекает только видимый текст из HTML/XHTML внутри EPUB.
     *
     * Предыдущая реализация удаляла HTML-теги регулярным выражением, но оставляла
     * содержимое <style>...</style>. Поэтому CSS вроде body,div,p{text-align:...}
     * попадал в TXT/FB2/PDF как обычный текст. Теперь сначала берём только <body>,
     * удаляем невидимые элементы и изображения, а затем Android Html декодирует
     * HTML-сущности и формирует читаемый текст.
     */
    private fun extractText(epub: File): String {
        val chunks = mutableListOf<String>()

        ZipFile(epub).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && (
                        entry.name.endsWith(".xhtml", true) ||
                            entry.name.endsWith(".html", true) ||
                            entry.name.endsWith(".htm", true)
                        )
                }
                .forEach { entry ->
                    val html = zip.getInputStream(entry)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }

                    val body = BODY_REGEX.find(html)?.groupValues?.get(1)
                        ?: html.replace(HEAD_REGEX, " ")

                    val cleanedHtml = body
                        .replace(COMMENT_REGEX, " ")
                        .replace(STYLE_REGEX, " ")
                        .replace(SCRIPT_REGEX, " ")
                        .replace(NOSCRIPT_REGEX, " ")
                        .replace(SVG_REGEX, " ")
                        .replace(IMG_REGEX, " ")

                    val plain = Html.fromHtml(
                        cleanedHtml,
                        Html.FROM_HTML_MODE_LEGACY
                    ).toString()

                    val normalized = normalizeExtractedText(plain)
                    if (normalized.isNotBlank()) chunks += normalized
                }
        }

        return chunks.joinToString("\n\n")
    }

    private fun normalizeExtractedText(value: String): String = value
        .replace('\u00A0', ' ')
        .replace("\u00AD", "") // soft hyphen
        .replace("\u200B", "")
        .replace("\u200C", "")
        .replace("\u200D", "")
        .replace("\uFEFF", "")
        .replace("\uFFFC", "") // placeholder, который Html может создать для <img>
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n[ \\t]+"), "\n")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun saveToDownloads(file: File, name: String, mime: String): ExportResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsScoped(file, name, mime)
        } else {
            saveToDownloadsLegacy(file, name, mime)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsScoped(file: File, name: String, mime: String): ExportResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/YBook Downloader"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в папке Загрузки")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Не удалось открыть файл для записи")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return ExportResult(uri, "Загрузки/YBook Downloader/$name")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToDownloadsLegacy(file: File, name: String, mime: String): ExportResult {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Разрешите приложению доступ к файлам и повторите операцию")
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(downloads, "YBook Downloader")
        if (!appDir.exists() && !appDir.mkdirs()) {
            error("Не удалось создать папку Загрузки/YBook Downloader")
        }

        val destination = uniqueLegacyFile(appDir, name)
        file.copyTo(destination, overwrite = false)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destination.absolutePath),
            arrayOf(mime),
            null
        )
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destination
        )
        return ExportResult(uri, "Загрузки/YBook Downloader/${destination.name}")
    }

    private fun uniqueLegacyFile(directory: File, requestedName: String): File {
        val first = File(directory, requestedName)
        if (!first.exists()) return first

        val dot = requestedName.lastIndexOf('.')
        val stem = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val ext = if (dot > 0) requestedName.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = File(directory, "$stem ($counter)$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    private fun safeName(name: String): String = name
        .replace(Regex("[\\/:*?\"<>|]"), "")
        .trim()
        .ifBlank { "book" }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        val BODY_REGEX = Regex("<body\\b[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val HEAD_REGEX = Regex("<head\\b[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val COMMENT_REGEX = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))
        val STYLE_REGEX = Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val SCRIPT_REGEX = Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val NOSCRIPT_REGEX = Regex("<noscript\\b[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val SVG_REGEX = Regex("<svg\\b[^>]*>.*?</svg>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val IMG_REGEX = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    }
}
