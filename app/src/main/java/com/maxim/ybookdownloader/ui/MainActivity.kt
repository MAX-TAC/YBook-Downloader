package com.maxim.ybookdownloader.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.maxim.ybookdownloader.R
import com.maxim.ybookdownloader.data.BookRepository
import com.maxim.ybookdownloader.data.DownloadHistoryItem
import com.maxim.ybookdownloader.data.HistoryStore
import com.maxim.ybookdownloader.export.AudioExporter
import com.maxim.ybookdownloader.export.BookExporter
import com.maxim.ybookdownloader.security.TokenStore
import com.maxim.ybookdownloader.util.BookReference
import com.maxim.ybookdownloader.util.BookUrlParser
import com.maxim.ybookdownloader.util.ResourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)
    private val themeMode = mutableStateOf(ThemeMode.SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = extractSharedText(intent)
        val themeStore = ThemeStore(applicationContext)
        themeMode.value = themeStore.get()
        setContent {
            YBookTheme(themeMode.value) {
                YBookApp(
                    initialText = sharedText.value,
                    themeMode = themeMode.value,
                    onThemeModeChange = { mode ->
                        themeStore.set(mode)
                        themeMode.value = mode
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText.value = extractSharedText(intent)
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
    }
}

data class BookUiState(
    val url: String = "",
    val title: String? = null,
    val coverUrl: String? = null,
    val authors: List<String> = emptyList(),
    val bookId: String? = null,
    val resourceType: ResourceType? = null,
    val textId: String? = null,
    val audioId: String? = null,
    val workKey: String = "",
    val error: String? = null,
    val message: String? = null,
    val progressLabel: String? = null,
    val busy: Boolean = false
)

enum class AudioQuality(val label: String, val isMax: Boolean) {
    NORMAL("M4A • Обычное качество", false),
    MAX("M4A • Максимальное качество", true)
}

private fun compactChapterList(numbers: List<Int>): String {
    val sorted = numbers.distinct().sorted()
    if (sorted.isEmpty()) return ""
    val ranges = mutableListOf<String>()
    var start = sorted.first()
    var previous = start
    sorted.drop(1).forEach { current ->
        if (current == previous + 1) {
            previous = current
        } else {
            ranges += if (start == previous) "$start" else "$start–$previous"
            start = current
            previous = current
        }
    }
    ranges += if (start == previous) "$start" else "$start–$previous"
    return ranges.joinToString(", ")
}

class MainViewModel : ViewModel() {
    private lateinit var repository: BookRepository
    private lateinit var tokenStore: TokenStore
    private lateinit var historyStore: HistoryStore
    private var epubFile: File? = null
    private var textInfo: BookRepository.ResourceInfo? = null
    private var audioInfo: BookRepository.ResourceInfo? = null

    var state by mutableStateOf(BookUiState())
        private set

    var history by mutableStateOf<List<DownloadHistoryItem>>(emptyList())
        private set

    fun init(context: Context) {
        if (!::repository.isInitialized) {
            val appContext = context.applicationContext
            repository = BookRepository(appContext)
            tokenStore = TokenStore(appContext)
            historyStore = HistoryStore(appContext)
            history = historyStore.load()
        }
    }

    fun hasToken(): Boolean = ::tokenStore.isInitialized && tokenStore.getToken() != null

    fun setUrl(value: String) {
        state = state.copy(url = value, error = null)
    }

    fun showError(message: String) {
        state = state.copy(error = message, message = null)
    }

    fun clearNotice() {
        state = state.copy(error = null, message = null)
    }

    fun loadFromText(text: String) {
        setUrl(text)
        val reference = BookUrlParser.parse(text)
        if (reference == null) {
            showError("Не удалось распознать ссылку из Яндекс Книг")
        } else {
            loadBook(reference)
        }
    }

    fun loadBook(ref: BookReference) {
        val token = tokenStore.getToken()
        if (token == null) {
            showError("Сессия Яндекса не найдена. Войдите заново.")
            return
        }

        state = state.copy(
            busy = true,
            error = null,
            message = null,
            progressLabel = "Поиск доступных версий…",
            title = null,
            coverUrl = null,
            authors = emptyList(),
            bookId = null,
            resourceType = null,
            textId = null,
            audioId = null,
            workKey = ""
        )
        epubFile = null
        textInfo = null
        audioInfo = null

        viewModelScope.launch {
            runCatching { repository.getResourceBundle(ref, token) }
                .onSuccess { bundle ->
                    textInfo = bundle.text
                    audioInfo = bundle.audio

                    // Независимо от исходной ссылки сначала показываем текстовую версию,
                    // если она существует. Пользователь может переключиться на аудио сверху.
                    val selected = bundle.text ?: bundle.audio
                        ?: error("Не найдена доступная версия произведения")
                    state = state.copy(
                        busy = false,
                        progressLabel = null,
                        title = selected.title,
                        coverUrl = selected.coverUrl,
                        authors = selected.authors,
                        bookId = selected.id,
                        resourceType = selected.type,
                        textId = bundle.text?.id,
                        audioId = bundle.audio?.id,
                        workKey = bundle.workKey
                    )
                }
                .onFailure {
                    state = state.copy(
                        busy = false,
                        progressLabel = null,
                        error = it.message ?: "Не удалось получить произведение"
                    )
                }
        }
    }

    fun selectResourceType(type: ResourceType) {
        val info = when (type) {
            ResourceType.BOOK -> textInfo
            ResourceType.AUDIOBOOK -> audioInfo
        } ?: return

        epubFile = if (type == ResourceType.BOOK) epubFile else null
        state = state.copy(
            title = info.title,
            coverUrl = info.coverUrl,
            authors = info.authors,
            bookId = info.id,
            resourceType = info.type,
            error = null,
            message = null
        )
    }

    fun download(context: Context, format: BookExporter.Format) {
        if (state.resourceType != ResourceType.BOOK) {
            showError("Сначала выберите вкладку «Текст»")
            return
        }
        val id = state.bookId ?: return
        val title = state.title ?: "book"
        val token = tokenStore.getToken() ?: run {
            showError("Сессия Яндекса не найдена. Войдите заново.")
            return
        }

        state = state.copy(busy = true, error = null, message = null, progressLabel = "Подготовка EPUB…")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val workingFile = epubFile?.takeIf { it.exists() }
                        ?: repository.downloadEpub(id, token, state.coverUrl)
                    val exporter = BookExporter(context.applicationContext)
                    val result = exporter.export(workingFile, title, format)
                    Pair(workingFile, result)
                }
            }.onSuccess { (file, result) ->
                epubFile = file
                val item = DownloadHistoryItem(
                    bookId = id,
                    title = title,
                    coverUrl = state.coverUrl,
                    sourceUrl = state.url,
                    resourceType = ResourceType.BOOK.name,
                    format = format.label,
                    mime = format.mime,
                    uri = result.uri.toString(),
                    displayPath = result.displayPath,
                    workKey = state.workKey,
                    authors = state.authors.joinToString(", ")
                )
                history = historyStore.add(item)
                state = state.copy(
                    busy = false,
                    progressLabel = null,
                    message = "Сохранено: ${result.displayPath}"
                )
            }.onFailure {
                state = state.copy(
                    busy = false,
                    progressLabel = null,
                    error = it.message ?: "Ошибка сохранения"
                )
            }
        }
    }

    fun loadAudiobookTracks(onLoaded: (List<BookRepository.AudioTrack>) -> Unit) {
        if (state.resourceType != ResourceType.AUDIOBOOK) {
            showError("Сначала выберите вкладку «Аудио»")
            return
        }
        val id = state.bookId ?: return
        val token = tokenStore.getToken() ?: run {
            showError("Сессия Яндекса не найдена. Войдите заново.")
            return
        }

        state = state.copy(busy = true, error = null, message = null, progressLabel = "Получение списка глав…")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.getAudiobookTracks(id, token) } }
                .onSuccess { tracks ->
                    state = state.copy(busy = false, progressLabel = null)
                    if (tracks.isEmpty()) showError("В аудиокниге не найдены доступные главы")
                    else onLoaded(tracks)
                }
                .onFailure {
                    state = state.copy(
                        busy = false,
                        progressLabel = null,
                        error = it.message ?: "Не удалось получить список глав"
                    )
                }
        }
    }

    fun downloadAudiobook(
        context: Context,
        quality: AudioQuality,
        selectedTracks: List<BookRepository.AudioTrack>,
        totalTracks: Int
    ) {
        if (state.resourceType != ResourceType.AUDIOBOOK) {
            showError("Сначала выберите вкладку «Аудио»")
            return
        }
        if (selectedTracks.isEmpty()) {
            showError("Выберите хотя бы одну главу")
            return
        }
        val id = state.bookId ?: return
        val title = state.title ?: "audiobook"
        val token = tokenStore.getToken() ?: run {
            showError("Сессия Яндекса не найдена. Войдите заново.")
            return
        }

        state = state.copy(busy = true, error = null, message = null, progressLabel = "Подготовка скачивания…")
        viewModelScope.launch {
            try {
                val exporter = AudioExporter(context.applicationContext)
                val results = mutableListOf<AudioExporter.ExportResult>()

                selectedTracks.forEachIndexed { index, track ->
                    state = state.copy(
                        progressLabel = "Скачивание главы ${index + 1} из ${selectedTracks.size}: ${track.title}"
                    )
                    val url = (if (quality.isMax) track.maxUrl else track.minUrl)
                        ?: error("Для главы ${track.number} нет ссылки выбранного качества")

                    val result = withContext(Dispatchers.IO) {
                        val workDir = File(context.cacheDir, "audio_work").apply { mkdirs() }
                        val temp = File(workDir, "$id-${track.number}.m4a")
                        try {
                            repository.downloadAudioTrack(url, token, temp)
                            exporter.saveTrack(temp, title, track.number, totalTracks)
                        } finally {
                            temp.delete()
                        }
                    }
                    results += result
                }

                val safeTitle = AudioExporter.safeName(title)
                val displayPath = if (results.size == 1) {
                    results.first().displayPath
                } else {
                    "Загрузки/YBook Downloader/$safeTitle/ (${results.size} файлов)"
                }
                val item = DownloadHistoryItem(
                    bookId = id,
                    title = title,
                    coverUrl = state.coverUrl,
                    sourceUrl = state.url,
                    resourceType = ResourceType.AUDIOBOOK.name,
                    format = "${quality.label} • ${results.size} из $totalTracks глав",
                    mime = AudioExporter.MIME_M4A,
                    uri = results.first().uri.toString(),
                    uris = results.map { it.uri.toString() },
                    displayPath = displayPath,
                    workKey = state.workKey,
                    authors = state.authors.joinToString(", "),
                    chapterNumbers = selectedTracks.map { it.number },
                    totalChapters = totalTracks,
                    audioQuality = quality.label
                )
                history = historyStore.add(item)
                state = state.copy(
                    busy = false,
                    progressLabel = null,
                    message = if (results.size == totalTracks) {
                        "Аудиокнига сохранена: $displayPath"
                    } else {
                        "Сохранено глав: ${results.size} из $totalTracks"
                    }
                )
            } catch (e: Exception) {
                state = state.copy(
                    busy = false,
                    progressLabel = null,
                    error = e.message ?: "Ошибка скачивания аудиокниги"
                )
            }
        }
    }

    /**
     * В v0.5.0 «Поделиться» принципиально НЕ скачивает ничего заново.
     * Возвращаем только уже сохранённые пользователем форматы текущего произведения.
     */
    fun shareableDownloads(): List<DownloadHistoryItem> {
        val workKey = state.workKey
        val currentTitle = BookRepository.normalize(state.title.orEmpty())
        val matching = history.filter { item ->
            (workKey.isNotBlank() && item.workKey == workKey) ||
                (item.workKey.isBlank() && BookRepository.normalize(item.title) == currentTitle)
        }

        val textItems = matching
            .filter { it.resourceType != ResourceType.AUDIOBOOK.name }
            .distinctBy { "${it.resourceType}|${it.format}" }

        val audioItems = matching.filter { it.resourceType == ResourceType.AUDIOBOOK.name }
        if (audioItems.isEmpty()) return textItems

        // Несколько отдельных скачиваний одной аудиокниги показываем одним пунктом.
        // Если главу скачивали повторно, при отправке используется самый свежий файл.
        val latestByChapter = linkedMapOf<Int, String>()
        val legacyUris = mutableListOf<String>()
        audioItems.sortedByDescending { it.createdAt }.forEach { item ->
            val itemUris = if (item.uris.isNotEmpty()) item.uris else listOf(item.uri)
            if (item.chapterNumbers.isNotEmpty() && item.chapterNumbers.size == itemUris.size) {
                item.chapterNumbers.zip(itemUris).forEach { (chapter, uri) ->
                    latestByChapter.putIfAbsent(chapter, uri)
                }
            } else {
                // История до v0.7.0 не содержала номера глав. Такие файлы тоже сохраняем.
                legacyUris += itemUris
            }
        }

        val chapterNumbers = latestByChapter.keys.sorted()
        val combinedUris = mutableListOf<String>().apply {
            chapterNumbers.forEach { chapter -> latestByChapter[chapter]?.let(::add) }
            legacyUris.distinct().filterNot { it in this }.forEach(::add)
        }
        if (combinedUris.isEmpty()) return textItems

        val latest = audioItems.maxByOrNull { it.createdAt } ?: return textItems
        val totalChapters = audioItems.maxOfOrNull { it.totalChapters } ?: 0
        val qualities = audioItems.mapNotNull { it.audioQuality.takeIf { value -> value.isNotBlank() } }.distinct()
        val qualityLabel = when {
            qualities.size == 1 -> qualities.first()
            qualities.isNotEmpty() -> "Смешанное качество"
            else -> "M4A"
        }
        val countLabel = if (chapterNumbers.isNotEmpty()) {
            if (totalChapters > 0) "${chapterNumbers.size} из $totalChapters глав" else "${chapterNumbers.size} глав"
        } else {
            "${combinedUris.size} файлов"
        }

        val audioCombined = latest.copy(
            id = "combined-audio-${state.workKey.ifBlank { currentTitle }}",
            format = "M4A • $countLabel",
            uri = combinedUris.first(),
            uris = combinedUris,
            displayPath = "Все сохранённые главы аудиокниги",
            chapterNumbers = chapterNumbers,
            totalChapters = totalChapters,
            audioQuality = qualityLabel
        )
        return textItems + audioCombined
    }

    fun shareHistoryItem(context: Context, item: DownloadHistoryItem) {
        runCatching {
            val all = if (item.uris.isNotEmpty()) item.uris else listOf(item.uri)
            val parsed = all.map(Uri::parse)
            parsed.forEach { uri ->
                context.contentResolver.openFileDescriptor(uri, "r")?.use { }
                    ?: error("Сохранённый файл больше недоступен")
            }
            if (parsed.size == 1) {
                shareUri(
                    context,
                    parsed.first(),
                    item.mime,
                    if (item.resourceType == ResourceType.AUDIOBOOK.name) "Поделиться аудиокнигой" else "Поделиться книгой"
                )
            } else {
                shareUris(context, parsed, item.mime, "Поделиться аудиокнигой")
            }
        }.onFailure {
            showError("Не удалось открыть сохранённый файл. Возможно, он был удалён из памяти устройства.")
        }
    }

    fun clearHistory() {
        historyStore.clear()
        history = emptyList()
        state = state.copy(message = "История очищена", error = null)
    }

    fun logout() {
        tokenStore.clear()
        epubFile = null
        textInfo = null
        audioInfo = null
        state = BookUiState()
    }

    private fun shareUri(context: Context, uri: Uri, mime: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("YBook", uri)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    private fun shareUris(context: Context, uris: List<Uri>, mime: String, chooserTitle: String) {
        check(uris.isNotEmpty()) { "Нет файлов для отправки" }
        if (uris.size == 1) {
            shareUri(context, uris.first(), mime, chooserTitle)
            return
        }

        val clip = ClipData.newRawUri("YBook", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = clip
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}

private enum class AppSection { HOME, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YBookApp(
    initialText: String?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    vm: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    vm.init(context)

    var authenticated by remember { mutableStateOf(vm.hasToken()) }
    var selectedSection by remember { mutableStateOf(AppSection.HOME) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(false) }
    var showAudioQuality by remember { mutableStateOf(false) }
    var showAudioChapters by remember { mutableStateOf(false) }
    var pendingAudioQuality by remember { mutableStateOf(AudioQuality.MAX) }
    var audioTracks by remember { mutableStateOf<List<BookRepository.AudioTrack>>(emptyList()) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val state = vm.state
    val shareable = vm.shareableDownloads()

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && vm.hasToken()) {
            authenticated = true
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) {
            action?.invoke()
        } else {
            vm.showError("Без доступа к файлам Android 8/9 не может сохранить файл в папку Загрузки")
        }
    }

    fun runWithStorageAccess(action: () -> Unit) {
        val needsLegacyPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

        if (needsLegacyPermission) {
            pendingStorageAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    fun openSearch(clearOldValue: Boolean = true) {
        if (clearOldValue) searchInput = ""
        showSearchDialog = true
    }

    LaunchedEffect(initialText, authenticated) {
        if (authenticated && !initialText.isNullOrBlank()) {
            selectedSection = AppSection.HOME
            searchInput = initialText
            vm.loadFromText(initialText)
        }
    }

    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message
        if (!text.isNullOrBlank()) {
            snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Short)
            vm.clearNotice()
        }
    }

    if (!authenticated) {
        LoginScreen(
            onLogin = { authLauncher.launch(Intent(context, AuthActivity::class.java)) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YBook Downloader") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedSection == AppSection.HOME && state.title != null && !state.busy) {
                FloatingActionButton(
                    onClick = { openSearch() },
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Найти другую книгу")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedSection == AppSection.HOME,
                    onClick = { selectedSection = AppSection.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Главная") }
                )
                NavigationBarItem(
                    selected = selectedSection == AppSection.HISTORY,
                    onClick = { selectedSection = AppSection.HISTORY },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("История") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (selectedSection) {
            AppSection.HOME -> HomeScreen(
                modifier = Modifier.padding(padding),
                state = state,
                hasShareableFiles = shareable.isNotEmpty(),
                onSearch = { openSearch() },
                onSelectType = vm::selectResourceType,
                onDownload = {
                    if (state.resourceType == ResourceType.AUDIOBOOK) showAudioQuality = true
                    else showFormats = true
                },
                onShare = { showShareDialog = true }
            )

            AppSection.HISTORY -> HistoryScreen(
                modifier = Modifier.padding(padding),
                items = vm.history,
                onShare = { vm.shareHistoryItem(context, it) },
                onClear = { vm.clearHistory() }
            )
        }
    }

    if (showSearchDialog) {
        SearchBookDialog(
            value = searchInput,
            busy = state.busy,
            onValueChange = { searchInput = it },
            onPaste = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    .orEmpty()
                if (text.isBlank()) vm.showError("Буфер обмена пуст") else searchInput = text
            },
            onDismiss = { if (!state.busy) showSearchDialog = false },
            onSearch = {
                val ref = BookUrlParser.parse(searchInput)
                if (ref == null) {
                    vm.showError("Не удалось распознать ссылку из Яндекс Книг")
                } else {
                    vm.setUrl(searchInput)
                    showSearchDialog = false
                    selectedSection = AppSection.HOME
                    vm.loadBook(ref)
                }
            }
        )
    }

    if (showFormats) {
        DownloadFormatDialog(
            onDismiss = { showFormats = false },
            onSelect = { format ->
                showFormats = false
                runWithStorageAccess { vm.download(context, format) }
            }
        )
    }

    if (showAudioQuality) {
        AudioQualityDialog(
            onDismiss = { showAudioQuality = false },
            onSelect = { quality ->
                showAudioQuality = false
                pendingAudioQuality = quality
                vm.loadAudiobookTracks { tracks ->
                    audioTracks = tracks
                    showAudioChapters = true
                }
            }
        )
    }

    if (showAudioChapters) {
        AudioChapterDialog(
            tracks = audioTracks,
            quality = pendingAudioQuality,
            onDismiss = { showAudioChapters = false },
            onDownload = { selected ->
                showAudioChapters = false
                runWithStorageAccess {
                    vm.downloadAudiobook(
                        context = context,
                        quality = pendingAudioQuality,
                        selectedTracks = selected,
                        totalTracks = audioTracks.size
                    )
                }
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onLogout = {
                showSettings = false
                vm.logout()
                authenticated = false
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showShareDialog) {
        ShareDownloadedDialog(
            items = shareable,
            onDismiss = { showShareDialog = false },
            onSelect = { item ->
                showShareDialog = false
                vm.shareHistoryItem(context, item)
            }
        )
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    state: BookUiState,
    hasShareableFiles: Boolean,
    onSearch: () -> Unit,
    onSelectType: (ResourceType) -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.progressLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val title = state.title
        if (title == null && !state.busy) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Найдите книгу", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Подойдёт ссылка как на текстовую, так и на аудиоверсию из приложения «Яндекс Книги». Доступные варианты приложение найдёт само.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
                            Text("Найти")
                        }
                    }
                }
            }
        }

        title?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(18.dp))
                    state.coverUrl?.let { cover ->
                        AsyncImage(
                            model = cover,
                            contentDescription = "Обложка",
                            modifier = Modifier.size(190.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    if (state.authors.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.authors.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.resourceType == ResourceType.BOOK) {
                            Button(
                                onClick = { onSelectType(ResourceType.BOOK) },
                                enabled = state.textId != null && !state.busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.MenuBook, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Текст")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSelectType(ResourceType.BOOK) },
                                enabled = state.textId != null && !state.busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.MenuBook, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Текст")
                            }
                        }

                        if (state.resourceType == ResourceType.AUDIOBOOK) {
                            Button(
                                onClick = { onSelectType(ResourceType.AUDIOBOOK) },
                                enabled = state.audioId != null && !state.busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Headphones, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Аудио")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSelectType(ResourceType.AUDIOBOOK) },
                                enabled = state.audioId != null && !state.busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Headphones, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Аудио")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onDownload,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Скачать")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onShare,
                        enabled = !state.busy && hasShareableFiles,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Поделиться")
                    }
                    if (!hasShareableFiles) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Сначала скачайте хотя бы один формат",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    modifier: Modifier,
    items: List<DownloadHistoryItem>,
    onShare: (DownloadHistoryItem) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("История скачиваний", style = MaterialTheme.typography.headlineSmall)
            if (items.isNotEmpty()) TextButton(onClick = onClear) { Text("Очистить") }
        }

        if (items.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Здесь появятся книги и аудиокниги, которые вы сохранили на устройство.",
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            items.forEach { item -> HistoryCard(item, onShare = { onShare(item) }) }
        }
    }
}

@Composable
private fun HistoryCard(item: DownloadHistoryItem, onShare: () -> Unit) {
    val date = remember(item.createdAt) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.createdAt))
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item.coverUrl?.let {
                AsyncImage(model = it, contentDescription = "Обложка", modifier = Modifier.size(72.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${item.format} • $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.resourceType == ResourceType.AUDIOBOOK.name && item.chapterNumbers.isNotEmpty()) {
                    Text(
                        "Главы: ${compactChapterList(item.chapterNumbers)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    item.displayPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Поделиться")
            }
        }
    }
}

@Composable
private fun SearchBookDialog(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    onSearch: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Найти книгу") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ссылка из Яндекс Книг") },
                placeholder = { Text("https://books.yandex.ru/…") },
                supportingText = {
                    Text("Вставьте ссылку через «Поделиться» в Яндекс Книгах. Подойдёт ссылка на текстовую или аудиоверсию.")
                },
                minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    if (value.isBlank()) {
                        IconButton(onClick = onPaste, enabled = !busy) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_paste_24),
                                contentDescription = "Вставить из буфера обмена"
                            )
                        }
                    } else {
                        IconButton(onClick = { onValueChange("") }, enabled = !busy) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить ссылку")
                        }
                    }
                }
            )
        },
        confirmButton = {
            Button(onClick = onSearch, enabled = value.isNotBlank() && !busy) { Text("Найти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
        }
    )
}

@Composable
private fun DownloadFormatDialog(
    onDismiss: () -> Unit,
    onSelect: (BookExporter.Format) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скачать текстовую книгу") },
        text = {
            Column {
                BookExporter.Format.entries.forEachIndexed { index, format ->
                    TextButton(onClick = { onSelect(format) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(format.label, fontWeight = FontWeight.Medium)
                            if (format == BookExporter.Format.EPUB) {
                                Text(
                                    "Kindle",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (index < BookExporter.Format.entries.lastIndex) HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AudioQualityDialog(
    onDismiss: () -> Unit,
    onSelect: (AudioQuality) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скачать аудиокнигу") },
        text = {
            Column {
                AudioQuality.entries.forEachIndexed { index, quality ->
                    TextButton(onClick = { onSelect(quality) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(quality.label, fontWeight = FontWeight.Medium)
                            Text(
                                if (quality.isMax) "Лучшее качество, больший размер файлов" else "Меньший размер файлов",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (index < AudioQuality.entries.lastIndex) HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}


@Composable
private fun AudioChapterDialog(
    tracks: List<BookRepository.AudioTrack>,
    quality: AudioQuality,
    onDismiss: () -> Unit,
    onDownload: (List<BookRepository.AudioTrack>) -> Unit
) {
    var selectedNumbers by remember(tracks) {
        mutableStateOf(tracks.map { it.number }.toSet())
    }
    val allSelected = tracks.isNotEmpty() && selectedNumbers.size == tracks.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите главы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    quality.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Выбрано: ${selectedNumbers.size} из ${tracks.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = {
                            selectedNumbers = if (allSelected) emptySet()
                            else tracks.map { it.number }.toSet()
                        }
                    ) {
                        Text(if (allSelected) "Снять все" else "Выбрать все")
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(tracks, key = { it.number }) { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = track.number in selectedNumbers,
                                onCheckedChange = { checked ->
                                    selectedNumbers = if (checked) {
                                        selectedNumbers + track.number
                                    } else {
                                        selectedNumbers - track.number
                                    }
                                }
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "Глава ${track.number}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (!track.title.equals("Глава ${track.number}", ignoreCase = true)) {
                                    Text(
                                        track.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDownload(tracks.filter { it.number in selectedNumbers })
                },
                enabled = selectedNumbers.isNotEmpty()
            ) {
                Text("Скачать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SettingsDialog(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Оформление",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) }
                        )
                        TextButton(
                            onClick = { onThemeModeChange(mode) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                mode.label,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сбросить авторизацию Яндекс Книг")
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_art),
                        contentDescription = "Иконка YBook Downloader",
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        "YBook Downloader",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Версия $versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/MAX-TAC/YBook-Downloader")
                                )
                            )
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = "GitHub",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("GitHub")
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ShareDownloadedDialog(
    items: List<DownloadHistoryItem>,
    onDismiss: () -> Unit,
    onSelect: (DownloadHistoryItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поделиться") },
        text = {
            Column {
                items.forEachIndexed { index, item ->
                    TextButton(onClick = { onSelect(item) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(item.format, fontWeight = FontWeight.Medium)
                            Text(
                                if (item.resourceType == ResourceType.AUDIOBOOK.name) "Аудио" else "Текст",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (item.resourceType == ResourceType.AUDIOBOOK.name && item.chapterNumbers.isNotEmpty()) {
                                Text(
                                    "Главы: ${compactChapterList(item.chapterNumbers)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (index < items.lastIndex) HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(onLogin: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("YBook Downloader") }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Вход в Яндекс Книги",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Авторизуйтесь в Яндексе, чтобы приложение могло получать книги, доступные вашей учётной записи.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                        Text("Войти через Яндекс")
                    }
                }
            }
        }
    }
}
