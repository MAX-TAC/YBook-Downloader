package com.maxim.ybookdownloader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getStringExtra(Intent.EXTRA_TEXT)
        setContent { YBookApp(initial) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

data class BookUiState(
    val url: String = "",
    val title: String? = null,
    val coverUrl: String? = null,
    val bookId: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val epubReady: Boolean = false
)

class MainViewModel : ViewModel() {
    private lateinit var repository: BookRepository
    private lateinit var tokenStore: TokenStore
    private var epubFile: java.io.File? = null
    private var currentTitle: String = "book"
    var state by mutableStateOf(BookUiState())
        private set

    fun init(context: android.content.Context) {
        if (!::repository.isInitialized) {
            repository = BookRepository(context.applicationContext)
            tokenStore = TokenStore(context.applicationContext)
        }
    }

    fun setUrl(value: String) { state = state.copy(url = value, error = null) }

    fun loadFromText(text: String) {
        setUrl(text)
        BookUrlParser.parse(text)?.let { loadBook(it) }
    }

    fun loadBook(ref: BookReference) {
        val token = tokenStore.getToken()
        if (token == null) { state = state.copy(error = "Сначала войдите через Яндекс"); return }
        state = state.copy(busy = true, error = null, bookId = ref.id)
        viewModelScope.launch {
            runCatching { repository.getBookInfo(ref.id, token) }
                .onSuccess { info -> state = state.copy(busy = false, title = info.title, coverUrl = info.coverUrl) }
                .onFailure { state = state.copy(busy = false, error = it.message ?: "Не удалось получить книгу") }
        }
    }

    fun download(onReady: () -> Unit = {}) {
        val id = state.bookId ?: return
        val title = state.title ?: "book"
        val token = tokenStore.getToken() ?: return
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.downloadEpub(id, token, title) } }
                .onSuccess { file -> epubFile = file; currentTitle = title; state = state.copy(busy = false, epubReady = true); onReady() }
                .onFailure { state = state.copy(busy = false, error = it.message ?: "Ошибка загрузки") }
        }
    }

    fun export(context: android.content.Context, format: BookExporter.Format, share: Boolean) {
        val file = epubFile ?: return
        val title = currentTitle
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val uri = BookExporter(context.applicationContext).export(file, title, format)
                if (share) withContext(Dispatchers.Main) { shareUri(context, uri, format.mime) }
            }.onFailure { withContext(Dispatchers.Main) { state = state.copy(error = it.message ?: "Ошибка экспорта") } }
        }
    }

    fun share(context: android.content.Context) {
        val file = epubFile ?: return
        // First export to Downloads so Kindle receives a stable content:// URI.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val uri = BookExporter(context.applicationContext).export(file, currentTitle, BookExporter.Format.EPUB)
                withContext(Dispatchers.Main) { shareUri(context, uri, BookExporter.Format.EPUB.mime) }
            }.onFailure { withContext(Dispatchers.Main) { state = state.copy(error = it.message ?: "Ошибка подготовки книги") } }
        }
    }

    fun logout() { tokenStore.clear(); state = BookUiState() }
    fun hasToken(): Boolean = ::tokenStore.isInitialized && tokenStore.getToken() != null

    private fun shareUri(context: android.content.Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("Book", uri)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить книгу"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YBookApp(initialText: String?, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    vm.init(context)
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    var input by remember { mutableStateOf(initialText ?: "") }
    var showFormats by remember { mutableStateOf(false) }
    val state = vm.state

    LaunchedEffect(initialText) { if (!initialText.isNullOrBlank()) vm.loadFromText(initialText) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("YBook Downloader") }) }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; vm.setUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ссылка на книгу") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { BookUrlParser.parse(input)?.let(vm::loadBook) ?: vm.setUrl(input) }) { Text("Найти книгу") }
                    OutlinedButton(onClick = {
                        authLauncher.launch(Intent(context, AuthActivity::class.java))
                    }) { Text(if (vm.hasToken()) "Яндекс ✓" else "Войти") }
                }

                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                state.title?.let { title ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            state.coverUrl?.let { AsyncImage(model = it, contentDescription = "Обложка", modifier = Modifier.size(180.dp)) }
                            Spacer(Modifier.height(8.dp))
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { vm.download() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.epubReady) "Скачать заново" else "Скачать EPUB")
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.share(context) }, enabled = state.epubReady, modifier = Modifier.fillMaxWidth()) {
                                Text("Отправить в Kindle")
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { showFormats = true }, enabled = state.epubReady, modifier = Modifier.fillMaxWidth()) {
                                Text("Экспортировать в другой формат")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFormats) {
        AlertDialog(
            onDismissRequest = { showFormats = false },
            title = { Text("Формат") },
            text = {
                Column {
                    BookExporter.Format.entries.forEach { format ->
                        TextButton(onClick = { showFormats = false; vm.export(context, format, share = false) }, modifier = Modifier.fillMaxWidth()) {
                            Text(format.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFormats = false }) { Text("Отмена") } }
        )
    }
}
