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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.maxim.ybookdownloader.data.BookRepository
import com.maxim.ybookdownloader.export.BookExporter
import com.maxim.ybookdownloader.security.TokenStore
import com.maxim.ybookdownloader.util.BookReference
import com.maxim.ybookdownloader.util.BookUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val busy: Boolean = false,
    val epubReady: Boolean = false
)

class MainViewModel : ViewModel() {
    private lateinit var repository: BookRepository
    private lateinit var tokenStore: TokenStore
    private var epubFile: File? = null
    private var currentTitle: String = "book"

    var state by mutableStateOf(BookUiState())
        private set

    fun init(context: Context) {
        if (!::repository.isInitialized) {
            repository = BookRepository(context.applicationContext)
            tokenStore = TokenStore(context.applicationContext)
        }
    }

    fun hasToken(): Boolean = ::tokenStore.isInitialized && tokenStore.getToken() != null

    fun setUrl(value: String) {
        state = state.copy(url = value, error = null, message = null)
    }

    fun showError(message: String) {
        state = state.copy(error = message, message = null)
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
            coverUrl = null,
            epubReady = false
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

    /** Скачивает рабочую копию EPUB и сразу сохраняет видимую копию в Downloads. */
    fun downloadAndSave(context: Context) {
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
                    val workingFile = repository.downloadEpub(id, token)
                    val result = BookExporter(context.applicationContext)
                        .export(workingFile, title, BookExporter.Format.EPUB)
                    workingFile to result
                }
            }.onSuccess { (file, result) ->
                epubFile = file
                currentTitle = title
                state = state.copy(
                    busy = false,
                    epubReady = true,
                    message = "Сохранено: ${result.displayPath}"
                )
            }.onFailure {
                state = state.copy(
                    busy = false,
                    error = it.message ?: "Ошибка загрузки"
                )
            }
        }
    }

    fun export(context: Context, format: BookExporter.Format) {
        val file = epubFile
        if (file == null || !file.exists()) {
            showError("Сначала скачайте EPUB")
            return
        }

        state = state.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    BookExporter(context.applicationContext).export(file, currentTitle, format)
                }
            }.onSuccess { result ->
                state = state.copy(
                    busy = false,
                    message = "Сохранено: ${result.displayPath}"
                )
            }.onFailure {
                state = state.copy(
                    busy = false,
                    error = it.message ?: "Ошибка экспорта"
                )
            }
        }
    }

    fun shareToKindle(context: Context) {
        val file = epubFile
        if (file == null || !file.exists()) {
            showError("Сначала скачайте EPUB")
            return
        }

        runCatching {
            val uri = BookExporter(context.applicationContext).uriForSharing(file)
            shareUri(context, uri, BookExporter.Format.EPUB.mime)
        }.onFailure {
            showError(it.message ?: "Ошибка подготовки книги")
        }
    }

    fun logout() {
        tokenStore.clear()
        epubFile = null
        currentTitle = "book"
        state = BookUiState()
    }

    private fun shareUri(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("Book", uri)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить книгу"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YBookApp(initialText: String?, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    vm.init(context)

    var authenticated by remember { mutableStateOf(vm.hasToken()) }
    var input by remember { mutableStateOf(initialText ?: "") }
    var showFormats by remember { mutableStateOf(false) }
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
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

    LaunchedEffect(initialText, authenticated) {
        if (authenticated && !initialText.isNullOrBlank()) {
            input = initialText
            vm.loadFromText(initialText)
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
                    TextButton(onClick = {
                        vm.logout()
                        authenticated = false
                    }) {
                        Text("Выйти")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    vm.setUrl(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ссылка на книгу") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                        if (text.isBlank()) {
                            vm.showError("Буфер обмена пуст")
                        } else {
                            input = text
                            vm.setUrl(text)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Вставить")
                }

                Button(
                    onClick = {
                        val ref = BookUrlParser.parse(input)
                        if (ref == null) vm.showError("Не удалось распознать ссылку на книгу")
                        else vm.loadBook(ref)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Найти книгу")
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            state.title?.let { title ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        state.coverUrl?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = "Обложка",
                                modifier = Modifier.size(180.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = { runWithStorageAccess { vm.downloadAndSave(context) } },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (state.epubReady) "Скачать EPUB заново" else "Скачать EPUB")
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.shareToKindle(context) },
                            enabled = state.epubReady && !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Отправить в Kindle")
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showFormats = true },
                            enabled = state.epubReady && !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Сохранить в другом формате")
                        }
                    }
                }
            }
        }
    }

    if (showFormats) {
        AlertDialog(
            onDismissRequest = { showFormats = false },
            title = { Text("Формат файла") },
            text = {
                Column {
                    BookExporter.Format.entries.forEachIndexed { index, format ->
                        TextButton(
                            onClick = {
                                showFormats = false
                                runWithStorageAccess { vm.export(context, format) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(format.label)
                        }
                        if (index < BookExporter.Format.entries.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFormats = false }) {
                    Text("Отмена")
                }
            }
        )
    }
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
