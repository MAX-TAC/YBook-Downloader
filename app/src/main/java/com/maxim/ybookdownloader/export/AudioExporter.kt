package com.maxim.ybookdownloader.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class AudioExporter(private val context: Context) {
    data class ExportResult(
        val uri: Uri,
        val displayPath: String,
        val fileName: String
    )

    fun saveTrack(file: File, title: String, trackNumber: Int, totalTracks: Int): ExportResult {
        check(file.exists() && file.length() > 0L) { "Аудиофайл не найден" }

        val safeTitle = safeName(title)
        val digits = totalTracks.toString().length.coerceAtLeast(2)
        val fileName = if (totalTracks == 1) {
            "$safeTitle.m4a"
        } else {
            "$safeTitle - Глава ${trackNumber.toString().padStart(digits, '0')}.m4a"
        }
        val relativeFolder = "YBook Downloader/$safeTitle"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(file, fileName, relativeFolder)
        } else {
            saveLegacy(file, fileName, safeTitle)
        }
    }


    fun uriForSharing(file: File, title: String, trackNumber: Int, totalTracks: Int): Uri {
        check(file.exists() && file.length() > 0L) { "Аудиофайл не найден" }
        val safeTitle = safeName(title)
        val digits = totalTracks.toString().length.coerceAtLeast(2)
        val fileName = if (totalTracks == 1) {
            "$safeTitle.m4a"
        } else {
            "$safeTitle - Глава ${trackNumber.toString().padStart(digits, '0')}.m4a"
        }
        val shareDir = File(context.cacheDir, "audio_share").apply {
            if (!exists() && !mkdirs()) error("Не удалось подготовить аудиофайл для отправки")
        }
        val shared = File(shareDir, fileName)
        file.copyTo(shared, overwrite = true)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shared
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(file: File, fileName: String, relativeFolder: String): ExportResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_M4A)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/$relativeFolder"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать аудиофайл в папке Загрузки")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Не удалось открыть аудиофайл для записи")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return ExportResult(
                uri = uri,
                displayPath = "Загрузки/$relativeFolder/$fileName",
                fileName = fileName
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(file: File, fileName: String, safeTitle: String): ExportResult {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Разрешите приложению доступ к файлам и повторите операцию")
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(downloads, "YBook Downloader/$safeTitle").apply {
            if (!exists() && !mkdirs()) error("Не удалось создать папку аудиокниги")
        }
        val target = File(appDir, fileName)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(MIME_M4A), null)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        return ExportResult(
            uri = uri,
            displayPath = "Загрузки/YBook Downloader/$safeTitle/$fileName",
            fileName = fileName
        )
    }

    companion object {
        const val MIME_M4A = "audio/mp4"

        fun safeName(value: String): String = value
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "audiobook" }
            .take(120)
    }
}
