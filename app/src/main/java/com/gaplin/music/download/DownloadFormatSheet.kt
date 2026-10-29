package com.gaplin.music.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Media kind returned by a source adapter. */
enum class DownloadMediaKind { AUDIO, VIDEO, SUBTITLE }

/**
 * A real, source-reported download representation.
 *
 * Do not manufacture quality/size entries: adapters should only return formats
 * that the source makes available and that the user is authorized to download.
 */
data class DownloadFormat(
    val id: String,
    val kind: DownloadMediaKind,
    val title: String,
    val description: String? = null,
    val container: String? = null,
    val estimatedBytes: Long? = null,
    val bitrateKbps: Int? = null,
    val height: Int? = null,
    val isFast: Boolean = false,
    val isAvailable: Boolean = true
) {
    val sizeLabel: String
        get() = estimatedBytes?.let(::formatBytes) ?: "Tamanho indisponível"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "Tamanho indisponível"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0)
    else String.format("%.1f MB", mb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadFormatSheet(
    formats: List<DownloadFormat>,
    onDismiss: () -> Unit,
    onDownload: (DownloadFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedId by remember(formats) {
        mutableStateOf(formats.firstOrNull { it.isAvailable }?.id)
    }
    var showAllFormats by remember { mutableStateOf(false) }

    val visibleFormats = if (showAllFormats) formats else {
        val audio = formats.filter { it.kind == DownloadMediaKind.AUDIO }.take(2)
        val video = formats.filter { it.kind == DownloadMediaKind.VIDEO }.take(2)
        audio + video
    }
    val selected = formats.firstOrNull { it.id == selectedId && it.isAvailable }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Spacer(
                Modifier
                    .padding(top = 8.dp, bottom = 12.dp)
                    .height(4.dp)
                    .fillMaxWidth(0.18f)
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp)
        ) {
            Text(
                text = "Baixar mídia como",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(22.dp))

            if (formats.isEmpty()) {
                Text(
                    "Nenhum formato de download foi disponibilizado por esta fonte.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (visibleFormats.any { it.kind == DownloadMediaKind.AUDIO }) {
                        item { SectionHeading("Música") }
                        items(visibleFormats.filter { it.kind == DownloadMediaKind.AUDIO }, key = { it.id }) {
                            FormatRow(it, selectedId == it.id) { selectedId = it.id }
                        }
                    }
                    if (visibleFormats.any { it.kind == DownloadMediaKind.VIDEO }) {
                        item { SectionHeading("Vídeo") }
                        items(visibleFormats.filter { it.kind == DownloadMediaKind.VIDEO }, key = { it.id }) {
                            FormatRow(it, selectedId == it.id) { selectedId = it.id }
                        }
                    }
                    if (visibleFormats.any { it.kind == DownloadMediaKind.SUBTITLE }) {
                        item { SectionHeading("Legendas/CC") }
                        items(visibleFormats.filter { it.kind == DownloadMediaKind.SUBTITLE }, key = { it.id }) {
                            FormatRow(it, selectedId == it.id) { selectedId = it.id }
                        }
                    }
                }

                if (formats.size > visibleFormats.size) {
                    Divider(Modifier.padding(top = 12.dp, bottom = 4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showAllFormats = !showAllFormats }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showAllFormats) "Mostrar menos" else "Mais formatos",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (showAllFormats) "Recolher" else "Todos ›",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = { selected?.let(onDownload) },
                enabled = selected != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("Baixar", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FormatRow(
    format: DownloadFormat,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val icon: ImageVector = when (format.kind) {
        DownloadMediaKind.AUDIO -> Icons.Default.MusicNote
        DownloadMediaKind.VIDEO -> Icons.Default.VideoFile
        DownloadMediaKind.SUBTITLE -> Icons.Default.VideoFile
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = format.isAvailable, onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 20.dp, end = 8.dp)
        ) {
            Text(
                format.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            format.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(format.sizeLabel, style = MaterialTheme.typography.bodySmall)
            if (format.bitrateKbps != null) {
                Text(
                    "${format.bitrateKbps} kbps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (format.height != null) {
                Text(
                    "${format.height}p",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = format.isAvailable
        )
    }
}
