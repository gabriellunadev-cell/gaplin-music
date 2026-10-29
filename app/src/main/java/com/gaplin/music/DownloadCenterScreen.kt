package com.gaplin.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DownloadPanel = Color(0xFF15121E)
private val DownloadAccent = Color(0xFFBE9AFF)
private val DownloadMuted = Color(0xFFAAA3B8)

/**
 * Download is a first-class destination inside the existing Gaplin Music app.
 * Platform search/extraction is intentionally adapter-driven; this screen only
 * starts direct HTTP(S) transfers when the user supplies an actual media URL.
 */
@Composable
fun DownloadCenterScreen(
    message: String?,
    onStartDirectDownload: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var showQueue by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Central de downloads", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Músicas e vídeos em um só lugar", color = DownloadMuted, fontSize = 13.sp)
            }
            Surface(color = DownloadAccent.copy(alpha = .16f), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Download, contentDescription = null, tint = DownloadAccent, modifier = Modifier.padding(12.dp).size(25.dp))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = DownloadPanel),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BAIXAR CONTEÚDO", color = DownloadAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Cole um link de mídia", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Use links diretos de arquivos que você tem autorização para salvar. Fontes externas precisam de integração oficial ou permissão de download.",
                    color = DownloadMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://...") },
                    leadingIcon = { Icon(Icons.Default.Link, null, tint = DownloadAccent) },
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = { onStartDirectDownload(input.trim()) },
                    enabled = input.trim().startsWith("https://") || input.trim().startsWith("http://"),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Text("  Iniciar download", fontWeight = FontWeight.Bold)
                }
                if (!message.isNullOrBlank()) {
                    Text(message, color = DownloadAccent, fontSize = 12.sp)
                }
            }
        }

        Text("FONTES E FORMATOS", color = DownloadAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SourceTile("Áudio", "MP3 / M4A", Icons.Default.MusicNote, Modifier.weight(1f))
            SourceTile("Vídeo", "MP4 / formatos", Icons.Default.VideoLibrary, Modifier.weight(1f))
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable { showQueue = !showQueue },
            colors = CardDefaults.cardColors(containerColor = DownloadPanel),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, null, tint = DownloadAccent, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Fila de downloads", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Transferências gerenciadas pelo Android", color = DownloadMuted, fontSize = 11.sp)
                }
                Text(if (showQueue) "Ocultar" else "Ver", color = DownloadAccent, fontWeight = FontWeight.Bold)
            }
            if (showQueue) {
                Text(
                    "Os downloads iniciados aparecem nas notificações do Android e na pasta Downloads/Gaplin Music. O histórico detalhado será conectado na próxima etapa.",
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 16.dp),
                    color = DownloadMuted,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "A pesquisa integrada de YouTube, Instagram, TikTok e outras fontes será ativada por adaptadores compatíveis com as regras e APIs de cada plataforma.",
            color = DownloadMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun SourceTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = DownloadPanel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = DownloadAccent, modifier = Modifier.size(23.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = DownloadMuted, fontSize = 10.sp)
        }
    }
}
