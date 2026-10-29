package com.gaplin.music

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ManagerSurface = Color(0xFF0D1B3A)
private val ManagerRaised = Color(0xFF17284D)
private val ManagerAccent = Color(0xFFB67CFF)
private val ManagerMuted = Color(0xFF8798BD)

@Composable
fun FileManagerScreen(
    files: List<ManagedMediaFile>,
    duplicates: List<DuplicateMediaGroup>,
    filter: ManagerFilter,
    selectedUris: Set<String>,
    loading: Boolean,
    duplicateScanRunning: Boolean,
    onFilterChange: (ManagerFilter) -> Unit,
    onToggleSelection: (ManagedMediaFile) -> Unit,
    onSelectAll: (List<ManagedMediaFile>) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onScanDuplicates: () -> Unit,
    onRefresh: () -> Unit,
    onConnectFolder: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filteredFiles = files.filter { file ->
        (filter == ManagerFilter.ALL || filter == ManagerFilter.AUDIO && file.kind == ManagedMediaKind.AUDIO ||
            filter == ManagerFilter.VIDEO && file.kind == ManagedMediaKind.VIDEO ||
            filter == ManagerFilter.IMAGE && file.kind == ManagedMediaKind.IMAGE) &&
            (file.name.contains(search, true) || file.folderPath.contains(search, true))
    }
    val duplicateFiles = duplicates.flatMap { it.files }.distinctBy { it.stableId }
    // In duplicate mode, bulk selection targets only extra copies and keeps one copy per group.
    val duplicateExtras = duplicates.flatMap { group -> group.files.drop(1) }.distinctBy { it.stableId }
    val visibleSelection = if (filter == ManagerFilter.DUPLICATES) duplicateExtras else filteredFiles

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Gerenciador inteligente", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${files.size} itens indexados · Música, vídeo e fotos", color = ManagerMuted, fontSize = 11.sp)
            }
            IconButton(onClick = onConnectFolder) {
                Icon(Icons.Default.FolderShared, "Conectar pasta com permissão de edição", tint = ManagerAccent)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Atualizar arquivos", tint = ManagerAccent)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ManagerFilter.entries.forEach { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { onFilterChange(item) },
                    label = { Text(item.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF52216E),
                        selectedLabelColor = Color.White,
                        labelColor = ManagerMuted
                    )
                )
            }
        }

        if (filter == ManagerFilter.DUPLICATES) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${duplicates.size} grupos encontrados", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Comparação exata do conteúdo (SHA-256)", color = ManagerMuted, fontSize = 10.sp)
                }
                Button(
                    onClick = onScanDuplicates,
                    enabled = !duplicateScanRunning && files.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF512477))
                ) {
                    if (duplicateScanRunning) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Default.Difference, null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(if (duplicateScanRunning) "Analisando" else "Analisar", fontSize = 11.sp)
                }
            }
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("Buscar por nome ou pasta", color = ManagerMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = ManagerAccent) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ManagerAccent,
                    unfocusedBorderColor = Color(0xFF3B4C72),
                    focusedContainerColor = ManagerSurface,
                    unfocusedContainerColor = ManagerSurface,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = ManagerAccent
                )
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${selectedUris.size} selecionado(s)",
                color = if (selectedUris.isEmpty()) ManagerMuted else ManagerAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onSelectAll(visibleSelection) }, enabled = visibleSelection.isNotEmpty()) {
                Text(
                    when {
                        visibleSelection.isNotEmpty() && visibleSelection.all { it.stableId in selectedUris } -> "Desmarcar"
                        filter == ManagerFilter.DUPLICATES -> "Selecionar cópias extras"
                        else -> "Selecionar tudo"
                    }
                )
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ManagerAccent)
            }
        } else if (filter == ManagerFilter.DUPLICATES) {
            if (duplicateScanRunning) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ManagerAccent)
                        Spacer(Modifier.height(10.dp))
                        Text("Comparando o conteúdo dos arquivos…", color = ManagerMuted, fontSize = 12.sp)
                    }
                }
            } else if (duplicates.isEmpty()) {
                ManagerEmptyState(
                    "Nenhuma duplicidade confirmada",
                    "Toque em Analisar. O Gaplin compara o conteúdo real dos arquivos, não apenas o nome."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(duplicates, key = { "${it.kind.name}:${it.sha256}" }) { group ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(ManagerSurface).padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCopy, null, tint = ManagerAccent)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${group.files.size} cópias idênticas · ${group.kind.label}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(
                                        "Até ${formatFileBytes(group.totalBytes - (group.files.maxOfOrNull { it.sizeBytes } ?: 0L))} liberáveis mantendo uma cópia",
                                        color = ManagerMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            group.files.forEach { file ->
                                ManagedFileRow(
                                    file = file,
                                    selected = file.stableId in selectedUris,
                                    onToggle = { onToggleSelection(file) }
                                )
                            }
                        }
                    }
                }
            }
        } else if (filteredFiles.isEmpty()) {
            ManagerEmptyState(
                "Nenhum arquivo nesta categoria",
                if (files.isEmpty()) "Conceda acesso à biblioteca ou adicione uma pasta pelo explorador." else "Tente outra busca ou categoria."
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(filteredFiles, key = { it.stableId }) { file ->
                    ManagedFileRow(
                        file = file,
                        selected = file.stableId in selectedUris,
                        onToggle = { onToggleSelection(file) }
                    )
                }
            }
        }

        if (selectedUris.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedButton(
                    onClick = onMove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ManagerAccent)
                ) {
                    Icon(Icons.Default.DriveFileMove, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Mover", fontSize = 12.sp)
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9B3157))
                ) {
                    Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Excluir", fontSize = 12.sp)
                }
            }
        }
    }
}


@Composable
private fun ManagedFileThumbnail(file: ManagedMediaFile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, file.stableId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(file.uri, Size(256, 256), CancellationSignal())
                } else {
                    when (file.kind) {
                        ManagedMediaKind.VIDEO, ManagedMediaKind.AUDIO -> {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, file.uri)
                                if (file.kind == ManagedMediaKind.AUDIO) {
                                    retriever.embeddedPicture?.let { bytes ->
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    }
                                } else {
                                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                }
                            } finally {
                                retriever.release()
                            }
                        }
                        ManagedMediaKind.IMAGE -> context.contentResolver.openInputStream(file.uri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }
            }.getOrNull()
        }
    }

    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF31205C), Color(0xFF16375C)))),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Prévia do arquivo",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Icon(
                when (file.kind) {
                    ManagedMediaKind.AUDIO -> Icons.Default.MusicNote
                    ManagedMediaKind.VIDEO -> Icons.Default.Movie
                    ManagedMediaKind.IMAGE -> Icons.Default.Image
                },
                contentDescription = null,
                tint = ManagerAccent,
                modifier = Modifier.size(25.dp)
            )
        }
    }
}

@Composable
private fun ManagedFileRow(
    file: ManagedMediaFile,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) ManagerRaised else ManagerSurface)
            .clickable(onClick = onToggle).padding(horizontal = 9.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ManagedFileThumbnail(file, Modifier.size(58.dp).clip(RoundedCornerShape(11.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(file.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.folderPath, color = ManagerMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatFileBytes(file.sizeBytes)} · ${if (file.isSaf) "Pasta selecionada" else "Biblioteca Android"}", color = ManagerAccent, fontSize = 9.sp)
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = ManagerAccent,
                uncheckedColor = ManagerMuted,
                checkmarkColor = Color.White
            )
        )
    }
}

@Composable
private fun ManagerEmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = ManagerAccent, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = ManagerMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun formatFileBytes(bytes: Long): String {
    if (bytes <= 0L) return "Tamanho desconhecido"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}
