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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.maxim.ybookdownloader.export.BookExporter
import com.maxim.ybookdownloader.security.TokenStore
import com.maxim.ybookdownloader.util.BookReference
import com.maxim.ybookdownloader.util.BookUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = extractSharedText(intent)
        setContent {
            YBookTheme {
                YBookApp(sharedText.value)
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
    val bookId: String? = null,
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false
)

class MainViewModel : ViewModel() {
    private lateinit var repository: BookRepository
    private lateinit var tokenStore: TokenStore
    private lateinit var historyStore: HistoryStore
    private var epubFile: File? = null
    private var currentTitle: String = "book"

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
            showError("Не удалось распознать ссылку на книгу")
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
            bookId = ref.id,
            title = null,
            coverUrl = null
        )
        epubFile = null

        viewModelScope.launch {
            runCatching { repository.getBookInfo(ref.id, token) }
                .onSuccess { info ->
                    currentTitle = info.title
                    state = state.copy(
                        busy = false,
                        title = info.title,
                        coverUrl = info.coverUrl
                    )
                }
                .onFailure {
                    state = state.copy(
                        busy = false,
                        error = it.message ?: "Не удалось получить книгу"
                    )
                }
        }
    }

    fun download(context: Context, format: BookExporter.Format) {
        val id = state.bookId ?: return
        val title = state.title ?: "book"
        val token = tokenStore.getToken() ?: run {
            showError("Сессия Яндекса не найдена. Войдите заново.")
            return
        }

        state = state.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val workingFile = epubFile?.takeIf { it.exists() }
                        ?: repository.downloadEpub(id, token)
                    val exporter = BookExporter(context.applicationContext)
                    val result = exporter.export(workingFile, title, format)
                    Triple(workingFile, exporter, result)
                }
            }.onSuccess { (file, _, result) ->
                epubFile = file
                currentTitle = title
                val item = DownloadHistoryItem(
                    bookId = id,
                    title = title,
                    coverUrl = state.coverUrl,
                    sourceUrl = state.url,
                    format = format.label,
                    mime = format.mime,
                    uri = result.uri.toString(),
                    displayPath = result.displayPath
                )
                history = historyStore.add(item)
                state = state.copy(
                    busy = false,
                    message = "Сохранено: ${result.displayPath}"
                )
            }.onFailure {
                state = state.copy(
                    busy = false,
                    error = it.message ?: "Ошибка сохранения"
                )
            }
        }
    }

    fun shareCurrent(context: Context) {
        val id = state.bookId ?: return
        val title = state.title ?: "book"
        val token = tokenStore.getToken() ?: run {
            showError("Сессия Яндекса не найдена. Войдите заново.")
            return
        }

        state = state.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    epubFile?.takeIf { it.exists() }
                        ?: repository.downloadEpub(id, token)
                }
            }.onSuccess { file ->
                epubFile = file
                currentTitle = title
                state = state.copy(busy = false)
                runCatching {
                    val uri = BookExporter(context.applicationContext).uriForSharing(file)
                    shareUri(context, uri, BookExporter.Format.EPUB.mime, "Поделиться книгой")
                }.onFailure {
                    showError(it.message ?: "Ошибка подготовки книги")
                }
            }.onFailure {
                state = state.copy(
                    busy = false,
                    error = it.message ?: "Ошибка скачивания"
                )
            }
        }
    }

    fun shareHistoryItem(context: Context, item: DownloadHistoryItem) {
        runCatching {
            shareUri(
                context,
                Uri.parse(item.uri),
                item.mime,
                "Поделиться книгой"
            )
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
        currentTitle = "book"
        state = BookUiState()
    }

    private fun shareUri(context: Context, uri: Uri, mime: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("Book", uri)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}

private enum class AppSection { HOME, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YBookApp(initialText: String?, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    vm.init(context)

    var authenticated by remember { mutableStateOf(vm.hasToken()) }
    var selectedSection by remember { mutableStateOf(AppSection.HOME) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val state = vm.state

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
            vm.showError("Без доступа к файлам Android 8/9 не может сохранить книгу в папку Загрузки")
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
            snackbarHostState.showSnackbar(
                message = text,
                duration = SnackbarDuration.Short
            )
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
                    if (selectedSection == AppSection.HOME && state.title != null) {
                        FilledIconButton(
                            onClick = { openSearch() },
                            enabled = !state.busy
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Найти другую книгу")
                        }
                    }
                    Box {
                        IconButton(onClick = { settingsExpanded = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                        DropdownMenu(
                            expanded = settingsExpanded,
                            onDismissRequest = { settingsExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Выйти из Яндекс Книг") },
                                onClick = {
                                    settingsExpanded = false
                                    vm.logout()
                                    authenticated = false
                                }
                            )
                        }
                    }
                }
            )
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
                onSearch = { openSearch() },
                onDownload = { showFormats = true },
                onShare = { vm.shareCurrent(context) }
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
                if (text.isBlank()) {
                    vm.showError("Буфер обмена пуст")
                } else {
                    searchInput = text
                }
            },
            onDismiss = { if (!state.busy) showSearchDialog = false },
            onSearch = {
                val ref = BookUrlParser.parse(searchInput)
                if (ref == null) {
                    vm.showError("Не удалось распознать ссылку на книгу")
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
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    state: BookUiState,
    onSearch: () -> Unit,
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
                        Text(
                            "Найдите книгу",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Используйте ссылку, полученную через «Поделиться» в приложении «Яндекс Книги».",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onSearch,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Найти книгу")
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
                    Spacer(Modifier.height(20.dp))
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
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Поделиться")
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
            if (items.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Очистить") }
            }
        }

        if (items.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Здесь появятся книги, которые вы сохранили на устройство.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            items.forEach { item ->
                HistoryCard(item = item, onShare = { onShare(item) })
            }
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
                AsyncImage(
                    model = it,
                    contentDescription = "Обложка",
                    modifier = Modifier.size(72.dp)
                )
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ссылка из Яндекс Книг") },
                    placeholder = { Text("https://books.yandex.ru/books/…") },
                    supportingText = {
                        Text("Вставьте ссылку, полученную через «Поделиться» в приложении «Яндекс Книги».")
                    },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = onPaste, enabled = !busy) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_paste_24),
                                contentDescription = "Вставить из буфера обмена"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSearch,
                enabled = value.isNotBlank() && !busy
            ) {
                Text("Найти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Отмена")
            }
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
        title = { Text("Скачать книгу") },
        text = {
            Column {
                BookExporter.Format.entries.forEachIndexed { index, format ->
                    TextButton(
                        onClick = { onSelect(format) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
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
                    if (index < BookExporter.Format.entries.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(onLogin: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("YBook Downloader") })
        }
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
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Войти через Яндекс")
                    }
                }
            }
        }
    }
}
