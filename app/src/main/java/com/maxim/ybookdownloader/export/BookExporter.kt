package com.maxim.ybookdownloader.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

class BookExporter(private val context: Context) {
    enum class Format(val label: String, val extension: String, val mime: String) {
        EPUB("EPUB", "epub", "application/epub+zip"),
        FB2("FB2", "fb2", "application/xml"),
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
            // Важно: EPUB уже является готовым исходником. Не копируем его в самого себя.
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
        val paragraphs = text
            .split(Regex("\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${xmlEscape(it)}</p>" }

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
  <description>
    <title-info>
      <book-title>${xmlEscape(title)}</book-title>
      <lang>ru</lang>
    </title-info>
  </description>
  <body>
    <section>
      <title><p>${xmlEscape(title)}</p></title>
      $paragraphs
    </section>
  </body>
</FictionBook>"""
        out.writeText(xml, Charsets.UTF_8)
    }

    private fun epubToTxt(epub: File, out: File) {
        out.writeText(extractText(epub), Charsets.UTF_8)
    }

    private fun epubToPdf(epub: File, out: File, title: String) {
        val document = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
            val pageWidth = 595
            val pageHeight = 842
            val left = 40f
            val top = 48f
            val maxWidth = pageWidth - 80f
            val lines = mutableListOf<String>()
            lines += title
            lines += ""

            for (paragraph in extractText(epub).split(Regex("\\n+"))) {
                var remaining = paragraph.trim()
                while (remaining.isNotEmpty()) {
                    var count = remaining.length
                    while (count > 1 && paint.measureText(remaining.substring(0, count)) > maxWidth) {
                        count--
                    }
                    val cut = remaining.substring(0, count)
                        .lastIndexOf(' ')
                        .takeIf { it > 0 }
                        ?: count
                    lines += remaining.substring(0, cut).trim()
                    remaining = remaining.substring(cut).trimStart()
                }
                lines += ""
            }

            var page: PdfDocument.Page? = null
            var canvasY = top
            var pageNumber = 0

            fun startPage() {
                pageNumber++
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                )
                canvasY = top
            }

            fun finishPage() {
                page?.let(document::finishPage)
                page = null
            }

            startPage()
            for (line in lines) {
                if (canvasY > pageHeight - 50) {
                    finishPage()
                    startPage()
                }
                page!!.canvas.drawText(line, left, canvasY, paint)
                canvasY += 18f
            }
            finishPage()
            out.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun extractText(epub: File): String {
        val chunks = mutableListOf<String>()
        ZipFile(epub).use { zip ->
            zip.entries().asSequence()
                .filter {
                    !it.isDirectory && (
                        it.name.endsWith(".xhtml", true) ||
                            it.name.endsWith(".html", true) ||
                            it.name.endsWith(".htm", true)
                        )
                }
                .forEach { entry ->
                    val html = zip.getInputStream(entry)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    chunks += html
                        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("</p>|</div>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace(Regex("\\n{3,}"), "\n\n")
                        .trim()
                }
        }
        return chunks.joinToString("\n\n")
    }

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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Разрешите приложению доступ к файлам и повторите операцию")
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(downloads, "YBook Downloader")
        if (!appDir.exists() && !appDir.mkdirs()) {
            error("Не удалось создать папку Загрузки/YBook Downloader")
        }

        val destination = uniqueLegacyFile(appDir, name)
        file.copyTo(destination, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mime), null)
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
}
