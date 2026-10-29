package com.gaplin.music

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gaplin.music.download.YtDlpDownloadWorker
import com.yausername.youtubedl_android.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DownloadBackground = Color(0xFF07132B)
private val DownloadSurface = Color(0xFF101F3D)
private val DownloadAccent = Color(0xFFB98BFF)
private val DownloadMuted = Color(0xFF95A5C7)

private enum class DownloadKind { VIDEO, AUDIO }

@Composable
fun DownloadCenterScreen(onRefreshLibrary: () -> Unit) {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var isInspecting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(DownloadKind.VIDEO) }
    var selectedHeight by remember { mutableIntStateOf(720) }

    val workInfos by workManager
        .getWorkInfosByTagFlow(YtDlpDownloadWorker.TAG)
        .collectAsState(initial = emptyList())

    val availableHeights = remember(info) {
        info?.formats.orEmpty()
            .filter { it.height > 0 && it.vcodec != "none" }
            .map { it.height }
            .distinct()
            .sortedDescending()
    }

    LaunchedEffect(info) {
        selectedHeight = availableHeights.firstOrNull() ?: 0
    }

    fun inspectUrl() {
        val cleanUrl = url.trim()
        val parsed = runCatching { Uri.parse(cleanUrl) }.getOrNull()
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https") || parsed.host.isNullOrBlank()) {
            message = "Cole um link HTTP ou HTTPS válido."
            return
        }
        scope.launch {
            isInspecting = true
            info = null
            message = null
            try {
                val result = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    val request = YoutubeDLRequest(cleanUrl).addOption("--no-playlist")
                    YoutubeDL.getInstance().getInfo(request)
                }
                info = result
                if (result.formats.isNullOrEmpty()) {
                    message = "A fonte não disponibilizou uma lista de formatos para este link."
                }
            } catch (error: Exception) {
                message = error.message?.lineSequence()?.firstOrNull()?.take(220)
                    ?: "Não foi possível consultar este link."
            } finally {
                isInspecting = false
            }
        }
    }

    fun startDownload() {
        val media = info ?: return
        val request = OneTimeWorkRequestBuilder<YtDlpDownloadWorker>()
            .setInputData(
                workDataOf(
                    YtDlpDownloadWorker.KEY_URL to url.trim(),
                    YtDlpDownloadWorker.KEY_TITLE to (media.title ?: "Mídia"),
                    YtDlpDownloadWorker.KEY_AUDIO_ONLY to (kind == DownloadKind.AUDIO),
                    YtDlpDownloadWorker.KEY_MAX_HEIGHT to selectedHeight
                )
            )
            .addTag(YtDlpDownloadWorker.TAG)
            .build()
        workManager.enqueue(request)
        message = "Download adicionado à fila."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Baixe mídia autorizada com o motor yt-dlp", color = DownloadMuted, fontSize = 12.sp)
            }
            Icon(Icons.Default.Download, null, tint = DownloadAccent, modifier = Modifier.size(30.dp))
        }

        Card(colors = CardDefaults.cardColors(containerColor = DownloadSurface), shape = RoundedCornerShape(22.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("NOVO DOWNLOAD", color = DownloadAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Cole o link da mídia", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        info = null
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://...") },
                    leadingIcon = { Icon(Icons.Default.Link, null, tint = DownloadAccent) },
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = ::inspectUrl,
                    enabled = url.isNotBlank() && !isInspecting,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    if (isInspecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Text("  Consultando formatos")
                    } else {
                        Icon(Icons.Default.Info, null)
                        Text("  Analisar link")
                    }
                }

                info?.let { media ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        media.title ?: "Mídia identificada",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            media.uploader,
                            media.extractor,
                            media.duration.takeIf { it > 0 }?.let(::durationLabel)
                        ).joinToString(" • "),
                        color = DownloadMuted,
                        fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == DownloadKind.VIDEO,
                            onClick = { kind = DownloadKind.VIDEO },
                            label = { Text("Vídeo MP4") },
                            leadingIcon = { Icon(Icons.Default.VideoFile, null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = kind == DownloadKind.AUDIO,
                            onClick = { kind = DownloadKind.AUDIO },
                            label = { Text("Áudio MP3") },
                            leadingIcon = { Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    if (kind == DownloadKind.VIDEO && availableHeights.isNotEmpty()) {
                        Text("QUALIDADE DISPONÍVEL", color = DownloadAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            availableHeights.take(8).forEach { height ->
                                FilterChip(
                                    selected = selectedHeight == height,
                                    onClick = { selectedHeight = height },
                                    label = { Text("${height}p") }
                                )
                            }
                        }
                    }
                    Button(
                        onClick = ::startDownload,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Download, null)
                        Text("  Iniciar download", fontWeight = FontWeight.Bold)
                    }
                }

                message?.let {
                    Text(it, color = DownloadAccent, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FILA DE DOWNLOADS", color = DownloadAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("${workInfos.count { !it.state.isFinished }} ativos • ${workInfos.count { it.state == WorkInfo.State.SUCCEEDED }} concluídos", color = DownloadMuted, fontSize = 11.sp)
            }
            TextButton(onClick = onRefreshLibrary) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Text(" Atualizar biblioteca", fontSize = 10.sp)
            }
        }

        if (workInfos.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = DownloadBackground), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, null, tint = DownloadMuted, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Sua fila está vazia", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Os downloads iniciados aparecerão aqui.", color = DownloadMuted, fontSize = 11.sp)
                    }
                }
            }
        } else {
            workInfos.forEach { workInfo ->
                DownloadJobCard(workInfo = workInfo) {
                    workManager.cancelWorkById(workInfo.id)
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, null, tint = DownloadMuted, modifier = Modifier.size(16.dp))
            Text(
                "Use apenas conteúdos que você tem direito ou autorização para salvar. O aplicativo não remove DRM nem contorna controles de acesso.",
                modifier = Modifier.padding(start = 8.dp),
                color = DownloadMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun DownloadJobCard(workInfo: WorkInfo, onCancel: () -> Unit) {
    val title = workInfo.inputData.getString(YtDlpDownloadWorker.KEY_TITLE)?.ifBlank { null } ?: "Download"
    val progress = workInfo.progress.getInt(YtDlpDownloadWorker.KEY_PROGRESS, 0).coerceIn(0, 100)
    val fileName = workInfo.outputData.getString(YtDlpDownloadWorker.KEY_FILE_NAME)
    val error = workInfo.outputData.getString(YtDlpDownloadWorker.KEY_ERROR)

    Card(colors = CardDefaults.cardColors(containerColor = DownloadBackground), shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> Icons.Default.CheckCircle
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Icons.Default.Cancel
                        else -> Icons.Default.Download
                    },
                    null,
                    tint = if (workInfo.state == WorkInfo.State.SUCCEEDED) Color(0xFF63D6A3) else DownloadAccent
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (workInfo.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Aguardando"
                            WorkInfo.State.RUNNING -> "Baixando • $progress%"
                            WorkInfo.State.SUCCEEDED -> "Concluído"
                            WorkInfo.State.FAILED -> "Falhou"
                            WorkInfo.State.CANCELLED -> "Cancelado"
                        },
                        color = DownloadMuted,
                        fontSize = 11.sp
                    )
                }
                if (!workInfo.state.isFinished) {
                    OutlinedButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, null, modifier = Modifier.size(15.dp))
                        Text("Cancelar", fontSize = 10.sp)
                    }
                }
            }
            if (workInfo.state == WorkInfo.State.RUNNING || workInfo.state == WorkInfo.State.ENQUEUED) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = DownloadAccent
                )
            }
            if (workInfo.state == WorkInfo.State.SUCCEEDED && !fileName.isNullOrBlank()) {
                Text("Salvo: $fileName", color = Color(0xFF63D6A3), fontSize = 11.sp, maxLines = 2)
            }
            if (workInfo.state == WorkInfo.State.FAILED) {
                Text(error ?: "Não foi possível concluir este download.", color = Color(0xFFFF9B9B), fontSize = 11.sp)
            }
        }
    }
}

private fun durationLabel(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remaining = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%d:%02d".format(minutes, remaining)
}
