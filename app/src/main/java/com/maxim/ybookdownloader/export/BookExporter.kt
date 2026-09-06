package com.maxim.ybookdownloader.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class BookExporter(private val context: Context) {
    enum class Format(val label: String, val extension: String, val mime: String) {
        EPUB("EPUB", "epub", "application/epub+zip"),
        FB2("FB2", "fb2", "application/xml"),
        PDF("PDF", "pdf", "application/pdf"),
        TXT("TXT", "txt", "text/plain")
    }

    fun export(epub: File, title: String, format: Format): Uri {
        val safe = safeName(title)
        val temp = File(context.cacheDir, "$safe.${format.extension}")
        when (format) {
            Format.EPUB -> epub.copyTo(temp, overwrite = true)
            Format.FB2 -> epubToFb2(epub, temp, title)
            Format.TXT -> epubToTxt(epub, temp)
            Format.PDF -> epubToPdf(epub, temp, title)
        }
        return saveToDownloads(temp, "$safe.${format.extension}", format.mime)
    }

    private fun epubToFb2(epub: File, out: File, title: String) {
        val text = extractText(epub)
        val escapedTitle = xmlEscape(title)
        val paragraphs = text.split("\\n+").map { it.trim() }.filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${xmlEscape(it)}</p>" }
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info><book-title>$escapedTitle</book-title></title-info></description>
<body>$paragraphs</body></FictionBook>"""
        out.writeText(xml, Charsets.UTF_8)
    }

    private fun epubToTxt(epub: File, out: File) = out.writeText(extractText(epub), Charsets.UTF_8)

    private fun epubToPdf(epub: File, out: File, title: String) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        val pageWidth = 595
        val pageHeight = 842
        val left = 40f
        val top = 48f
        val maxWidth = pageWidth - 80f
        val lines = mutableListOf<String>()
        lines += title
        lines += ""
        for (paragraph in extractText(epub).split("\\n+")) {
            var remaining = paragraph.trim()
            while (remaining.isNotEmpty()) {
                var count = remaining.length
                while (count > 1 && paint.measureText(remaining.substring(0, count)) > maxWidth) count--
                val cut = remaining.substring(0, count).lastIndexOf(' ').takeIf { it > 0 } ?: count
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
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvasY = top
        }
        fun finishPage() { page?.let { document.finishPage(it) }; page = null }
        startPage()
        for (line in lines) {
            if (canvasY > pageHeight - 50) { finishPage(); startPage() }
            page!!.canvas.drawText(line, left, canvasY, paint)
            canvasY += 18f
        }
        finishPage()
        out.outputStream().use { document.writeTo(it) }
        document.close()
    }

    private fun extractText(epub: File): String {
        val chunks = mutableListOf<String>()
        ZipFile(epub).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) }
                .forEach { entry ->
                    val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    chunks += html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("</p>|</div>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace(Regex("\\n{3,}"), "\n\n")
                        .trim()
                }
        }
        return chunks.joinToString("\n\n")
    }

    private fun saveToDownloads(file: File, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YBook Downloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в Downloads")
        try {
            resolver.openOutputStream(uri).use { output -> file.inputStream().use { input -> input.copyTo(output!!) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\/:*?\"<>|]"), "").trim().ifBlank { "book" }
    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
