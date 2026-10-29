package com.gaplin.music

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val DownloadAccent = Color(0xFFB88AFF)
private val DownloadAccentDeep = Color(0xFF7D4DCE)
private val DownloadCard = Color(0xFF101E3B)
private val DownloadCardRaised = Color(0xFF172746)
private val DownloadMuted = Color(0xFF9BA9C7)
private val DownloadGreen = Color(0xFF62D5A7)
private val DownloadPink = Color(0xFFFF72B5)

private data class DownloadUiTask(
    val id: String,
    val title: String,
    val source: RemoteMediaResult,
    val audioOnly: Boolean,
    val progress: Float = 0f,
    val etaSeconds: Long = 0L,
    val status: String = "Na fila",
    val error: String? = null
)

@Composable
fun DownloadsScreen(
    onLibraryRefresh: () -> Unit,
    onPlayDownloaded: (DownloadedMedia) -> Unit
) {
    val context = LocalContext.current
    val manager = remember { YtDlpDownloadManager(context) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var engineReady by remember { mutableStateOf(false) }
    var engineVersion by remember { mutableStateOf("verificando...") }
    var engineError by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSource by rememberSaveable { mutableStateOf("Pesquisar") }
    var selectedSection by rememberSaveable { mutableStateOf("Pesquisar") }
    var selectedResult by remember { mutableStateOf<RemoteMediaResult?>(null) }
    var results by remember { mutableStateOf<List<RemoteMediaResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<List<DownloadedMedia>>(emptyList()) }
    val tasks = remember { mutableStateListOf<DownloadUiTask>() }
    val downloadMutex = remember { Mutex() }
    var cookiesConfigured by remember { mutableStateOf(manager.hasYoutubeCookies()) }
    var cookieMessage by remember { mutableStateOf<String?>(null) }
    val cookiesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val count = manager.importYoutubeCookies(uri)
                    cookiesConfigured = true
                    cookieMessage = "$count cookies do YouTube importados com segurança."
                } catch (error: Exception) {
                    cookieMessage = error.message ?: "Não foi possível importar os cookies."
                }
            }
        }
    }

    LaunchedEffect(manager) {
        try {
            manager.initialize()
            engineVersion = manager.engineVersion()
            engineReady = true
            downloaded = manager.listDownloaded()
        } catch (error: Exception) {
            engineError = error.message ?: "Não foi possível iniciar o motor de downloads."
        }
    }

    fun performSearch() {
        val value = searchQuery.trim()
        if (value.isEmpty()) {
            searchError = "Digite o nome de uma música, artista ou cole um link."
            return
        }
        if (value.startsWith("https://", true)) {
            val playlist = value.contains("list=", true) && !value.contains("watch?v=", true)
            selectedResult = RemoteMediaResult(
                id = value.hashCode().toString(),
                title = "Conteúdo do link",
                author = runCatching { android.net.Uri.parse(value).host ?: "Link externo" }.getOrDefault("Link externo"),
                duration = "",
                thumbnailUrl = null,
                url = value,
                kind = if (playlist) RemoteMediaKind.PLAYLIST else RemoteMediaKind.VIDEO,
                subtitle = "Link informado"
            )
            searchError = null
            return
        }
        val query = when (selectedSource) {
            "Música" -> value + " música"
            "Mais" -> value + " playlist"
            else -> value
        }
        scope.launch {
            searching = true
            searchError = null
            results = emptyList()
            try {
                results = YouTubeSearchRepository.search(query)
                if (results.isEmpty()) searchError = "Nenhum resultado encontrado. Tente outro termo."
            } catch (error: Exception) {
                searchError = error.message ?: "Não foi possível pesquisar no YouTube."
            } finally {
                searching = false
            }
        }
    }

    fun startDownload(item: RemoteMediaResult, audioOnly: Boolean) {
        if (!engineReady) {
            downloadMessage = engineError ?: "O motor de downloads ainda está iniciando."
            return
        }
        if (item.kind == RemoteMediaKind.CHANNEL) {
            downloadMessage = "Abra um vídeo ou uma playlist do canal para baixar."
            return
        }

        val duplicate = tasks.any { task ->
            task.source.url == item.url &&
                task.audioOnly == audioOnly &&
                task.status in setOf("Na fila", "Verificando", "Preparando", "Baixando", "Concluído")
        }
        if (duplicate) {
            downloadMessage = "Este conteúdo já está na fila ou foi baixado nesta sessão."
            selectedSection = "Fila"
            return
        }

        val taskId = item.id + "_" + System.currentTimeMillis()
        tasks.add(
            DownloadUiTask(
                id = taskId,
                title = item.title,
                source = item,
                audioOnly = audioOnly,
                status = "Na fila"
            )
        )
        selectedResult = null
        selectedSection = "Fila"
        downloadMessage = "Adicionado à fila de downloads."

        // Downloads run one at a time. The Mutex preserves FIFO order and avoids
        // racing the embedded Python runtime or publishing files concurrently.
        scope.launch {
            downloadMutex.withLock {
                val queuedIndex = tasks.indexOfFirst { it.id == taskId }
                if (queuedIndex >= 0) {
                    tasks[queuedIndex] = tasks[queuedIndex].copy(status = "Verificando")
                }
                downloading = true
                try {
                    val files = manager.download(
                        item = item,
                        audioOnly = audioOnly,
                        onStage = { stage ->
                            mainHandler.post {
                                val index = tasks.indexOfFirst { it.id == taskId }
                                if (index >= 0) {
                                    val status = if (stage == "Validando disponibilidade") "Verificando" else "Preparando"
                                    tasks[index] = tasks[index].copy(status = status)
                                }
                            }
                        },
                        onProgress = { progress, eta ->
                            mainHandler.post {
                                val index = tasks.indexOfFirst { it.id == taskId }
                                if (index >= 0) {
                                    tasks[index] = tasks[index].copy(
                                        progress = progress,
                                        etaSeconds = eta,
                                        status = "Baixando"
                                    )
                                }
                            }
                        }
                    )
                    downloaded = manager.listDownloaded()
                    val index = tasks.indexOfFirst { it.id == taskId }
                    if (index >= 0) {
                        tasks[index] = tasks[index].copy(
                            progress = 100f,
                            etaSeconds = 0L,
                            status = "Concluído"
                        )
                    }
                    downloadMessage = if (files.size == 1) {
                        "Download concluído: " + files.first().fileName
                    } else {
                        files.size.toString() + " arquivos adicionados à pasta Downloads/Gaplin Music."
                    }
                    onLibraryRefresh()
                } catch (error: Exception) {
                    val message = error.message ?: "Falha ao baixar esta mídia."
                    val index = tasks.indexOfFirst { it.id == taskId }
                    if (index >= 0) {
                        tasks[index] = tasks[index].copy(status = "Falhou", error = message)
                    }
                    downloadMessage = message
                } finally {
                    downloading = false
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (downloading) "Seu download está em andamento"
                    else downloaded.size.toString() + " concluídos • yt-dlp " + engineVersion,
                    color = DownloadMuted,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = {
                scope.launch { downloaded = manager.listDownloaded() }
            }) {
                Icon(Icons.Default.Refresh, "Atualizar downloads", tint = DownloadAccent)
            }
        }

        if (selectedResult == null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Pesquisar", "Fila", "Concluídos").forEach { section ->
                    FilterChip(
                        selected = selectedSection == section,
                        onClick = { selectedSection = section },
                        label = { Text(section) },
                        leadingIcon = {
                            if (section == "Fila") Icon(Icons.Default.History, null, Modifier.size(16.dp))
                            if (section == "Concluídos") Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DownloadAccentDeep.copy(alpha = .38f),
                            selectedLabelColor = Color.White,
                            labelColor = DownloadMuted,
                            selectedLeadingIconColor = DownloadAccent
                        )
                    )
                }
            }
        }

        when {
            selectedResult != null -> {
                val detailItem = selectedResult!!
                DownloadDetail(
                    item = detailItem,
                    engineReady = engineReady,
                    downloading = downloading,
                    onBack = { selectedResult = null },
                    onDownloadAudio = { startDownload(detailItem, true) },
                    onDownloadVideo = { startDownload(detailItem, false) }
                )
            }
            selectedSection == "Pesquisar" -> {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF182A50), Color(0xFF28194C), Color(0xFF101A36))
                            )
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("ENCONTRE SUA MÍDIA", color = DownloadAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                    Text("O que você quer baixar?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Pesquise no YouTube ou cole o link de uma mídia que você tem autorização para salvar.", color = DownloadMuted, fontSize = 12.sp)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                            .background(Color(0xFF0B1730)).padding(start = 12.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Link, null, tint = DownloadAccent, modifier = Modifier.size(21.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it; searchError = null },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Música, artista ou link...", color = DownloadMuted) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = DownloadAccent
                            )
                        )
                        IconButton(
                            onClick = { performSearch() },
                            enabled = !searching
                        ) {
                            Icon(Icons.Default.Search, "Pesquisar", tint = DownloadAccent, modifier = Modifier.size(27.dp))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("Pesquisar", "YouTube", "Música", "Mais").forEach { source ->
                            val selected = selectedSource == source
                            Text(
                                source,
                                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                                    .background(if (selected) DownloadAccentDeep.copy(alpha = .48f) else Color.White.copy(alpha = .06f))
                                    .clickable { selectedSource = source }
                                    .padding(horizontal = 13.dp, vertical = 8.dp),
                                color = if (selected) Color.White else DownloadMuted,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                    if (!engineReady) {
                        Text(
                            engineError ?: "Preparando o motor de downloads...",
                            color = if (engineError == null) DownloadMuted else DownloadPink,
                            fontSize = 11.sp
                        )
                    } else {
                        Text("Downloads disponíveis para conteúdos autorizados e sem DRM.", color = DownloadMuted, fontSize = 10.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                            .background(Color(0xFF0B1730).copy(alpha = .72f)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (cookiesConfigured) "Sessão do YouTube configurada" else "Conteúdo que exige sua conta",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (cookiesConfigured)
                                    "Cookies guardados somente no armazenamento privado do app."
                                else
                                    "Opcional: importe seu arquivo Netscape .txt, exportado da sua própria conta.",
                                color = DownloadMuted,
                                fontSize = 9.sp
                            )
                        }
                        if (cookiesConfigured) {
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { manager.clearYoutubeCookies() }
                                    cookiesConfigured = false
                                    cookieMessage = "Sessão removida do armazenamento do Gaplin."
                                }
                            }) {
                                Icon(Icons.Default.Refresh, "Remover sessão", tint = DownloadPink)
                            }
                        }
                        OutlinedButton(
                            onClick = { cookiesPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DownloadAccent)
                        ) {
                            Text(if (cookiesConfigured) "Trocar" else "Importar", fontSize = 10.sp)
                        }
                    }
                    if (cookieMessage != null) {
                        Text(cookieMessage!!, color = DownloadMuted, fontSize = 10.sp)
                    }
                }

                if (searching) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 25.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = DownloadAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Pesquisando no YouTube...", color = DownloadMuted, fontSize = 13.sp)
                    }
                } else if (results.isNotEmpty()) {
                    Text("RESULTADOS • " + results.size, color = DownloadAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    val visibleResults = when (selectedSource) {
                        "YouTube" -> results.filter { it.kind == RemoteMediaKind.VIDEO }
                        "Música" -> results.filter { it.kind == RemoteMediaKind.VIDEO }
                        "Mais" -> results.filter { it.kind != RemoteMediaKind.VIDEO }
                        else -> results
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 14.dp)
                    ) {
                        items(visibleResults, key = { it.kind.toString() + ":" + it.id }) { item ->
                            DownloadResultCard(
                                item = item,
                                onOpen = { selectedResult = item },
                                onQuickDownload = {
                                    if (item.kind != RemoteMediaKind.CHANNEL) startDownload(item, false)
                                    else selectedResult = item
                                }
                            )
                        }
                    }
                    if (visibleResults.isEmpty()) {
                        Text("Nenhum resultado nesta categoria. Escolha outra aba.", color = DownloadMuted, fontSize = 12.sp)
                    }
                } else if (searchError != null) {
                    EmptyDownloadState(
                        title = "Pesquisa não concluída",
                        message = searchError!!,
                        icon = Icons.Default.Info
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                    EmptyDownloadState(
                        title = "Sua próxima mídia começa aqui",
                        message = "Digite uma música, artista ou vídeo para encontrar resultados no YouTube.",
                        icon = Icons.Default.Search
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
            selectedSection == "Fila" -> {
                if (tasks.isEmpty()) {
                    EmptyDownloadState(
                        title = "Sua fila está vazia",
                        message = "Quando iniciar um download, o progresso aparecerá nesta tela.",
                        icon = Icons.Default.CloudDownload
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            DownloadTaskCard(task, onRetry = { startDownload(task.source, task.audioOnly) })
                        }
                    }
                }
            }
            else -> {
                if (downloaded.isEmpty()) {
                    EmptyDownloadState(
                        title = "Nenhum arquivo concluído",
                        message = "Os arquivos baixados aparecerão aqui e também na pasta Downloads/Gaplin Music.",
                        icon = Icons.Default.VideoLibrary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(downloaded, key = { it.uri.toString() }) { file ->
                            DownloadedFileCard(file, onPlay = { onPlayDownloaded(file) })
                        }
                    }
                }
            }
        }

        if (downloadMessage != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(DownloadCardRaised).padding(11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val success = downloadMessage!!.contains("concluído", true) || downloadMessage!!.contains("adicionados", true)
                Icon(
                    if (success) Icons.Default.CheckCircle else Icons.Default.Info,
                    null,
                    tint = if (success) DownloadGreen else DownloadAccent
                )
                Spacer(Modifier.width(8.dp))
                Text(downloadMessage!!, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { downloadMessage = null }) { Text("OK", color = DownloadAccent) }
            }
        }
    }
}

@Composable
private fun DownloadResultCard(
    item: RemoteMediaResult,
    onOpen: () -> Unit,
    onQuickDownload: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(DownloadCard).clickable(onClick = onOpen).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = "Capa de " + item.title,
            modifier = Modifier.width(112.dp).height(70.dp).clip(RoundedCornerShape(10.dp))
                .background(DownloadCardRaised),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.author, color = DownloadMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (item.kind) {
                    RemoteMediaKind.VIDEO -> listOf(item.duration, item.subtitle).filter { it.isNotBlank() }.joinToString(" • ")
                    RemoteMediaKind.PLAYLIST -> item.subtitle.ifBlank { "Playlist" }
                    RemoteMediaKind.CHANNEL -> "Canal"
                },
                color = DownloadMuted.copy(alpha = .8f),
                fontSize = 10.sp,
                maxLines = 1
            )
        }
        IconButton(onClick = onQuickDownload) {
            Icon(
                if (item.kind == RemoteMediaKind.CHANNEL) Icons.Default.ArrowBack else Icons.Default.Download,
                if (item.kind == RemoteMediaKind.CHANNEL) "Abrir canal" else "Baixar vídeo",
                tint = DownloadAccent
            )
        }
    }
}

@Composable
private fun DownloadDetail(
    item: RemoteMediaResult,
    engineReady: Boolean,
    downloading: Boolean,
    onBack: () -> Unit,
    onDownloadAudio: () -> Unit,
    onDownloadVideo: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar", tint = DownloadAccent) }
            Text("Detalhes do conteúdo", color = Color.White, fontWeight = FontWeight.Bold)
        }
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = "Prévia de " + item.title,
            modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp))
                .background(DownloadCard),
            contentScale = ContentScale.Crop
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(item.author, color = DownloadMuted, fontSize = 13.sp)
            if (item.duration.isNotBlank() || item.subtitle.isNotBlank()) {
                Text(
                    listOf(item.duration, item.subtitle).filter { it.isNotBlank() }.joinToString(" • "),
                    color = DownloadMuted,
                    fontSize = 11.sp
                )
            }
        }
        if (item.kind == RemoteMediaKind.CHANNEL) {
            EmptyDownloadState(
                title = "Escolha um vídeo",
                message = "A pesquisa encontrou um canal. Abra a pesquisa e escolha um vídeo específico para baixar.",
                icon = Icons.Default.VideoLibrary
            )
        } else {
            Button(
                onClick = onDownloadVideo,
                enabled = engineReady,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DownloadAccentDeep, contentColor = Color.White)
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (downloading) "Adicionar à fila" else if (item.kind == RemoteMediaKind.PLAYLIST) "Baixar playlist" else "Baixar vídeo", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onDownloadAudio,
                enabled = engineReady,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DownloadAccent)
            ) {
                Icon(Icons.Default.Headphones, null)
                Spacer(Modifier.width(8.dp))
                Text(if (downloading) "Adicionar áudio à fila" else "Baixar somente áudio")
            }
            Text(
                "O áudio é salvo no formato original disponível (por exemplo, M4A ou WebM), sem conversão. Qualidade e formatos dependem da fonte.",
                color = DownloadMuted,
                fontSize = 10.sp
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DownloadCard).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, null, tint = DownloadAccent)
            Spacer(Modifier.width(9.dp))
            Text(
                "Use apenas conteúdos que você criou, possui ou tem autorização para baixar. O Gaplin Music não remove DRM nem contorna controles de acesso.",
                color = DownloadMuted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(task: DownloadUiTask, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DownloadCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (task.status == "Concluído") Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                null,
                tint = if (task.status == "Concluído") DownloadGreen else DownloadAccent
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(task.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (task.audioOnly) "Somente áudio" else "Vídeo", color = DownloadMuted, fontSize = 10.sp)
            }
            Text(task.status, color = if (task.status == "Falhou") DownloadPink else DownloadMuted, fontSize = 10.sp)
        }
        if (task.status == "Baixando" || task.status == "Preparando" || task.status == "Verificando") {
            LinearProgressIndicator(
                progress = (task.progress / 100f).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                color = DownloadAccent,
                trackColor = DownloadCardRaised
            )
            val etaText = if (task.etaSeconds > 0) "aprox. " + task.etaSeconds + "s restantes" else "calculando tempo"
            Text(
                if (task.status == "Verificando") "Verificando se a fonte disponibiliza este conteúdo..."
                else if (task.status == "Preparando") "Preparando o arquivo..."
                else task.progress.toInt().toString() + "% • " + etaText,
                color = DownloadMuted,
                fontSize = 10.sp
            )
        }
        if (task.error != null) {
            Text(task.error, color = DownloadPink, fontSize = 10.sp)
            if (task.status == "Falhou") {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DownloadAccent)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Tentar novamente", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DownloadedFileCard(file: DownloadedMedia, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(DownloadCard).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(43.dp).clip(RoundedCornerShape(12.dp)).background(DownloadAccentDeep.copy(alpha = .25f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (file.audioOnly) Icons.Default.LibraryMusic else Icons.Default.Movie, null, tint = DownloadAccent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(file.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(file.fileName, color = DownloadMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Concluído", color = DownloadGreen, fontSize = 10.sp)
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Default.PlayArrow, "Reproduzir", tint = DownloadAccent, modifier = Modifier.size(27.dp))
        }
    }
}

@Composable
private fun EmptyDownloadState(
    title: String,
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(19.dp)).background(DownloadAccentDeep.copy(alpha = .22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = DownloadAccent, modifier = Modifier.size(27.dp))
        }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(message, color = DownloadMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
