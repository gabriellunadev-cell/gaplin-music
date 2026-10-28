package com.gaplin.music

import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

private val Background = Color(0xFF090811)
private val Panel = Color(0xFF171422)
private val PanelRaised = Color(0xFF211B31)
private val Purple = Color(0xFF9B6DFF)
private val PurpleBright = Color(0xFFB69AFF)
private val Blue = Color(0xFF54C8FF)
private val Muted = Color(0xFFAAA5B8)

data class Track(val title: String, val artist: String, val uri: Uri, val folder: String)
private enum class MusicTab(val label: String) { HOME("Início"), LIBRARY("Músicas"), FOLDERS("Pastas"), FAVORITES("Favoritos"), VIDEO("Vídeos") }

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var permissionGranted by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result[Manifest.permission.READ_MEDIA_AUDIO] == true || result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(permissions)

        setContent {
            var tracks by remember { mutableStateOf(emptyList<Track>()) }
            var selected by remember { mutableStateOf<Track?>(null) }
            var tab by remember { mutableStateOf(MusicTab.HOME) }
            var query by remember { mutableStateOf("") }
            var isPlaying by remember { mutableStateOf(false) }
            var favorites by remember { mutableStateOf(loadFavorites()) }
            var selectedFolder by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(permissionGranted) { if (permissionGranted) tracks = loadTracks() }
            DisposableEffect(player) {
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }
            fun toggleFavorite(track: Track) {
                favorites = if (track.uri.toString() in favorites) favorites - track.uri.toString() else favorites + track.uri.toString()
                getSharedPreferences("gaplin_music", MODE_PRIVATE).edit().putStringSet("favorites", favorites).apply()
            }
            val folders = tracks.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)

            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Blue, background = Background, surface = Panel)) {
                Scaffold(containerColor = Background, bottomBar = {
                    NavigationBar(containerColor = Color(0xFF100E18), tonalElevation = 0.dp) {
                        MusicTab.entries.forEach { item ->
                            NavigationBarItem(selected = tab == item, onClick = { tab = item; if (item != MusicTab.FOLDERS) selectedFolder = null },
                                icon = { Icon(when (item) {
                                    MusicTab.HOME -> Icons.Default.Home
                                    MusicTab.LIBRARY -> Icons.Default.LibraryMusic
                                    MusicTab.FOLDERS -> Icons.Default.Folder
                                    MusicTab.FAVORITES -> Icons.Default.Favorite
                                    MusicTab.VIDEO -> Icons.Default.VideoLibrary
                                }, item.label) },
                                label = { Text(item.label, fontSize = 9.sp) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = PurpleBright, selectedTextColor = PurpleBright,
                                    indicatorColor = Purple.copy(alpha = 0.18f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                        }
                    }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("GAPLIN", color = PurpleBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                                Text("MUSIC", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                            }
                            Surface(shape = CircleShape, color = PanelRaised, modifier = Modifier.size(44.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, "Gaplin Music", tint = PurpleBright) }
                            }
                        }
                        when (tab) {
                            MusicTab.HOME -> {
                                HeroPanel(selected, isPlaying) {
                                    if (selected != null) { if (player.isPlaying) player.pause() else player.play() }
                                    else tracks.firstOrNull()?.let { playTrack(it) { selected = it } }
                                }
                                Spacer(Modifier.height(18.dp))
                                SectionHeader("Sua biblioteca", "${tracks.size} faixas") { tracks = loadTracks() }
                                SearchField(query) { query = it }
                                TrackList(tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, selected, favorites,
                                    { playTrack(it) { selected = it } }, ::toggleFavorite)
                            }
                            MusicTab.LIBRARY -> {
                                SectionHeader("Todas as músicas", "${tracks.size} faixas") { tracks = loadTracks() }
                                SearchField(query) { query = it }
                                TrackList(tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, selected, favorites,
                                    { playTrack(it) { selected = it } }, ::toggleFavorite)
                            }
                            MusicTab.FOLDERS -> {
                                SectionHeader(if (selectedFolder == null) "Pastas do dispositivo" else selectedFolder!!,
                                    if (selectedFolder == null) "${folders.size} pastas" else "${folders[selectedFolder]?.size ?: 0} faixas") {
                                    tracks = loadTracks()
                                }
                                if (selectedFolder != null) {
                                    TextButton(onClick = { selectedFolder = null }) { Icon(Icons.Default.ArrowBack, null); Text(" Todas as pastas") }
                                    TrackList(folders[selectedFolder].orEmpty(), selected, favorites,
                                        { playTrack(it) { selected = it } }, ::toggleFavorite)
                                } else if (folders.isEmpty()) EmptyState(Icons.Default.FolderOff, "Nenhuma pasta encontrada", "Não encontramos músicas no armazenamento.")
                                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                    items(folders.keys.toList(), key = { it }) { folder ->
                                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable { selectedFolder = folder }
                                            .padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Folder, null, tint = PurpleBright, modifier = Modifier.size(28.dp))
                                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(folder, color = Color.White, fontWeight = FontWeight.SemiBold)
                                                Text("${folders[folder]?.size ?: 0} músicas", color = Muted, fontSize = 12.sp)
                                            }
                                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                                        }
                                    }
                                }
                            }
                            MusicTab.FAVORITES -> {
                                SectionHeader("Seus favoritos", "${favorites.size} salvas") { tracks = loadTracks(); favorites = loadFavorites() }
                                val favoriteTracks = tracks.filter { it.uri.toString() in favorites }
                                if (favoriteTracks.isEmpty()) EmptyState(Icons.Default.FavoriteBorder, "Sua seleção pessoal", "Toque no coração ao lado de uma música para salvá-la aqui.")
                                else TrackList(favoriteTracks, selected, favorites, { playTrack(it) { selected = it } }, ::toggleFavorite)
                            }
                            MusicTab.VIDEO -> EmptyState(Icons.Default.VideoLibrary, "Seus vídeos", "A biblioteca de vídeos será conectada ao player em uma próxima etapa.")
                        }
                        selected?.let { current ->
                            MiniPlayer(current, isPlaying,
                                { if (player.isPlaying) player.pause() else player.play() },
                                { if (player.hasNextMediaItem()) player.seekToNextMediaItem() })
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    private fun loadFavorites(): Set<String> = getSharedPreferences("gaplin_music", MODE_PRIVATE).getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    private fun playTrack(track: Track, onSelected: (Track) -> Unit) {
        onSelected(track)
        player.setMediaItem(MediaItem.fromUri(track.uri))
        player.prepare()
        player.play()
    }

    @Suppress("DEPRECATION")
    private fun loadTracks(): List<Track> {
        val result = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (hasRelativePath) projection += MediaStore.Audio.Media.RELATIVE_PATH else projection += MediaStore.Audio.Media.DATA
        contentResolver.query(collection, projection.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val path = cursor.getColumnIndexOrThrow(if (hasRelativePath) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val rawPath = cursor.getString(path).orEmpty().trimEnd('/')
                val folder = rawPath.substringAfterLast('/').ifBlank { "Armazenamento interno" }
                result += Track(cursor.getString(title) ?: "Faixa sem título",
                    cursor.getString(artist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artista desconhecido",
                    ContentUris.withAppendedId(collection, cursor.getLong(id)), folder)
            }
        }
        return result
    }

    override fun onDestroy() { player.release(); super.onDestroy() }
}

@Composable
private fun HeroPanel(track: Track?, playing: Boolean, onPlay: () -> Unit) {
    Surface(shape = RoundedCornerShape(27.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF30204D), Color(0xFF1A1830), Color(0xFF11101A)))).padding(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Purple.copy(alpha = 0.18f), shape = RoundedCornerShape(50)) {
                        Text("EXPERIÊNCIA GAPLIN", color = PurpleBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                    }
                    Spacer(Modifier.weight(1f)); Icon(Icons.Default.GraphicEq, null, tint = Blue, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(22.dp))
                Text(if (track == null) "Sua música.\nSua atmosfera." else "Tocando agora", color = Color.White, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text(track?.let { "${it.title} • ${it.artist}" } ?: "Toda a sua biblioteca em um só lugar.", color = Color(0xFFC7C1D3), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onPlay, shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text(if (playing) "Pausar" else "Reproduzir")
                    }
                    Spacer(Modifier.width(12.dp)); Text("REPRODUÇÃO LOCAL", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Atualizar biblioteca", tint = PurpleBright) }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = query, onValueChange = onChange, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        placeholder = { Text("Buscar músicas ou artistas", color = Muted) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = PurpleBright) },
        singleLine = true, shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = Color(0xFF393344),
            focusedContainerColor = Panel, unfocusedContainerColor = Panel, focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = PurpleBright))
}

@Composable
private fun TrackList(tracks: List<Track>, selected: Track?, favorites: Set<String>, onClick: (Track) -> Unit, onFavorite: (Track) -> Unit) {
    if (tracks.isEmpty()) EmptyState(Icons.Default.MusicNote, "Nenhuma faixa encontrada", "Adicione músicas ao aparelho e atualize sua biblioteca.")
    else LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
        items(tracks, key = { it.uri.toString() }) { track ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected?.uri == track.uri) PanelRaised else Color.Transparent)
                .clickable { onClick(track) }.padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Purple.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = PurpleBright) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(track.artist, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
                IconButton(onClick = { onFavorite(track) }) {
                    Icon(if (track.uri.toString() in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Alternar favorito", tint = if (track.uri.toString() in favorites) PurpleBright else Muted)
                }
                Icon(Icons.Default.PlayCircle, "Reproduzir", tint = if (selected?.uri == track.uri) Blue else PurpleBright, modifier = Modifier.padding(end = 8.dp).size(24.dp))
            }
        }
    }
}

@Composable
private fun MiniPlayer(track: Track, playing: Boolean, onToggle: () -> Unit, onNext: () -> Unit) {
    Surface(color = Color(0xFF211B30), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(39.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Purple, Blue))), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = PurpleBright) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Purple.copy(alpha = 0.8f), modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(12.dp)); Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp)); Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
