package com.gaplin.music

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale

private val Bg = Color(0xFF08070D)
private val SurfaceDark = Color(0xFF15121E)
private val SurfaceRaised = Color(0xFF211A30)
private val Violet = Color(0xFF9B5CFF)
private val VioletSoft = Color(0xFFBE9AFF)
private val Cyan = Color(0xFF59D5FF)
private val TextMuted = Color(0xFFAAA3B8)

private enum class AppTab(val label: String) {
    HOME("Início"), MUSIC("Músicas"), FILES("Arquivos"), FAVORITES("Favoritos"), VIDEOS("Vídeos")
}

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var audioPermission by mutableStateOf(false)
    private var videoPermission by mutableStateOf(false)
    private var selectedTreeUri by mutableStateOf<Uri?>(null)

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                selectedTreeUri = uri
                getSharedPreferences("gaplin_music", MODE_PRIVATE).edit()
                    .putString("media_tree_uri", uri.toString()).apply()
            } catch (_: SecurityException) { }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        selectedTreeUri = getSharedPreferences("gaplin_music", MODE_PRIVATE)
            .getString("media_tree_uri", null)?.let(Uri::parse)
        refreshPermissions()
        requestMissingPermissions()

        setContent {
            var catalog by remember { mutableStateOf(MediaSnapshot(emptyList(), emptyList())) }
            var activeTab by remember { mutableStateOf(AppTab.HOME) }
            var search by remember { mutableStateOf("") }
            var favoriteUris by remember { mutableStateOf(loadFavorites()) }
            var currentAudio by remember { mutableStateOf<AudioTrack?>(null) }
            var currentVideo by remember { mutableStateOf<VideoEntry?>(null) }
            var playing by remember { mutableStateOf(false) }
            var selectedFolder by remember { mutableStateOf<String?>(null) }
            var selectedSeries by remember { mutableStateOf<String?>(null) }
            var videoMode by remember { mutableStateOf(false) }
            var showPlayer by remember { mutableStateOf(false) }
            var showFullscreenVideo by remember { mutableStateOf(false) }

            fun scanLibrary() {
                val indexed = MediaCatalog.scan(this)
                val selected = selectedTreeUri?.let { MediaCatalog.scanSelectedTree(this, it) }
                catalog = if (selected == null) indexed else MediaSnapshot(
                    audio = (indexed.audio + selected.audio).distinctBy { it.uri.toString() },
                    videos = (indexed.videos + selected.videos).distinctBy { it.uri.toString() }
                )
                favoriteUris = loadFavorites()
            }

            LaunchedEffect(audioPermission, videoPermission) {
                if (audioPermission || videoPermission) scanLibrary()
            }

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) playing = false
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            fun toggleFavorite(track: AudioTrack) {
                favoriteUris = if (track.uri.toString() in favoriteUris) {
                    favoriteUris - track.uri.toString()
                } else {
                    favoriteUris + track.uri.toString()
                }
                getSharedPreferences("gaplin_music", MODE_PRIVATE).edit()
                    .putStringSet("favorites", favoriteUris).apply()
            }

            fun playAudio(track: AudioTrack) {
                currentAudio = track
                currentVideo = null
                videoMode = false
                showPlayer = false
                player.setMediaItem(MediaItem.fromUri(track.uri))
                player.prepare()
                player.play()
            }

            fun playVideo(video: VideoEntry) {
                currentVideo = video
                currentAudio = null
                videoMode = true
                showPlayer = true
                player.setMediaItem(MediaItem.fromUri(video.uri))
                player.prepare()
                player.play()
            }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Violet, secondary = Cyan, background = Bg, surface = SurfaceDark
            )) {
                Scaffold(
                    containerColor = Bg,
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF100D17), tonalElevation = 0.dp) {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = activeTab == tab,
                                    onClick = {
                                        activeTab = tab
                                        selectedFolder = null
                                        selectedSeries = null
                                        search = ""
                                    },
                                    icon = {
                                        Icon(
                                            when (tab) {
                                                AppTab.HOME -> Icons.Default.Home
                                                AppTab.MUSIC -> Icons.Default.LibraryMusic
                                                AppTab.FILES -> Icons.Default.FolderOpen
                                                AppTab.FAVORITES -> Icons.Default.Favorite
                                                AppTab.VIDEOS -> Icons.Default.VideoLibrary
                                            }, contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label, fontSize = 10.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = VioletSoft,
                                        selectedTextColor = VioletSoft,
                                        indicatorColor = Violet.copy(alpha = .18f),
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                ) { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets)
                            .padding(horizontal = 18.dp)
                    ) {
                        TopBrand(onRefresh = ::scanLibrary)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (activeTab) {
                            AppTab.HOME -> HomeScreen(
                                catalog = catalog,
                                currentAudio = currentAudio,
                                currentVideo = currentVideo,
                                playing = playing,
                                onMusic = { activeTab = AppTab.MUSIC },
                                onVideos = { activeTab = AppTab.VIDEOS },
                                onFiles = { activeTab = AppTab.FILES },
                                onPlayAudio = ::playAudio,
                                onPlayVideo = ::playVideo,
                                onRefresh = ::scanLibrary
                            )
                            AppTab.MUSIC -> {
                                ScreenHeading("Biblioteca musical", "${catalog.audio.size} faixas no dispositivo")
                                SearchBox(search) { search = it }
                                val filtered = catalog.audio.filter {
                                    it.title.contains(search, true) || it.artist.contains(search, true) ||
                                        it.folderPath.contains(search, true)
                                }
                                AudioList(filtered, currentAudio, favoriteUris, ::playAudio, ::toggleFavorite)
                            }
                            AppTab.FAVORITES -> {
                                ScreenHeading("Sua coleção", "${favoriteUris.size} músicas salvas")
                                val favorites = catalog.audio.filter { it.uri.toString() in favoriteUris }
                                if (favorites.isEmpty()) EmptyPanel(
                                    Icons.Default.FavoriteBorder, "Seus favoritos começam aqui",
                                    "Use o coração ao lado de uma música para guardá-la nesta coleção."
                                ) else AudioList(favorites, currentAudio, favoriteUris, ::playAudio, ::toggleFavorite)
                            }
                            AppTab.FILES -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Explorador de mídia", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                        Text("${catalog.folders.size} pastas indexadas", color = TextMuted, fontSize = 12.sp)
                                    }
                                    OutlinedButton(onClick = { folderPicker.launch(selectedTreeUri) }) {
                                        Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Adicionar pasta", fontSize = 11.sp)
                                    }
                                }
                                if (selectedTreeUri != null) {
                                    TextButton(onClick = {
                                        try { contentResolver.releasePersistableUriPermission(selectedTreeUri!!, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                                        selectedTreeUri = null
                                        getSharedPreferences("gaplin_music", MODE_PRIVATE).edit().remove("media_tree_uri").apply()
                                        scanLibrary()
                                    }) { Text("Remover pasta selecionada", color = VioletSoft, fontSize = 11.sp) }
                                }
                                SearchBox(search) { search = it }
                                val folders = catalog.folders.filter { it.contains(search, true) }
                                if (selectedFolder == null) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                                        items(folders, key = { it }) { folder ->
                                            FolderCard(
                                                path = folder,
                                                count = catalog.audio.count { it.folderPath == folder } +
                                                    catalog.videos.count { it.folderPath == folder },
                                                onClick = { selectedFolder = folder }
                                            )
                                        }
                                        if (folders.isEmpty()) item {
                                            EmptyPanel(Icons.Default.FolderOff, "Nenhuma pasta de mídia", "Adicione arquivos de áudio ou vídeo e atualize a biblioteca.")
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { selectedFolder = null }) { Icon(Icons.Default.ArrowBack, null, tint = VioletSoft) }
                                        Text(selectedFolder!!.substringAfterLast('/'), color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    val folderAudio = catalog.audio.filter { it.folderPath == selectedFolder }
                                    val folderVideos = catalog.videos.filter { it.folderPath == selectedFolder }
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                                        if (folderAudio.isNotEmpty()) item { Text("MÚSICAS", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                        items(folderAudio, key = { it.uri.toString() }) { track ->
                                            AudioRow(track, currentAudio, track.uri.toString() in favoriteUris,
                                                { playAudio(track) }, { toggleFavorite(track) })
                                        }
                                        if (folderVideos.isNotEmpty()) item { Text("VÍDEOS", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                        items(folderVideos, key = { it.uri.toString() }) { video ->
                                            VideoRow(video, { playVideo(video) })
                                        }
                                    }
                                }
                            }
                            AppTab.VIDEOS -> {
                                ScreenHeading("Filmes e séries", "${catalog.videos.size} vídeos encontrados")
                                SearchBox(search) { search = it }
                                val filtered = catalog.videos.filter {
                                    it.title.contains(search, true) || it.seriesName.contains(search, true) ||
                                        it.folderName.contains(search, true)
                                }
                                val episodeItems = filtered.filter { it.season != null && it.episode != null }
                                val movieItems = filtered.filter { it.season == null || it.episode == null }
                                val grouped = episodeItems.groupBy { it.seriesName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                                if (selectedSeries != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { selectedSeries = null }) {
                                            Icon(Icons.Default.ArrowBack, null, tint = VioletSoft)
                                        }
                                        Text(selectedSeries!!, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    val episodes = catalog.videos.filter { it.seriesName == selectedSeries }
                                        .sortedWith(compareBy<VideoEntry> { it.season ?: 0 }.thenBy { it.episode ?: 0 }.thenBy { it.title })
                                    val seasons = episodes.groupBy { it.season ?: 1 }.toSortedMap()
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        seasons.forEach { (season, seasonEpisodes) ->
                                            item(key = "season:$season") {
                                                Text("TEMPORADA $season", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            items(seasonEpisodes, key = { it.uri.toString() }) { video ->
                                                VideoRow(video) { playVideo(video) }
                                            }
                                        }
                                    }
                                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                                    if (filtered.isNotEmpty()) {
                                        if (movieItems.isNotEmpty()) {
                                            item { Text("FILMES E VÍDEOS", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            items(movieItems, key = { "movie:${it.uri}" }) { video ->
                                                VideoRow(video, { playVideo(video) })
                                            }
                                        }
                                        if (grouped.isNotEmpty()) {
                                            item { Text("SÉRIES E TEMPORADAS", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            items(grouped.keys.toList(), key = { "series:$it" }) { series ->
                                                SeriesCard(series, grouped[series].orEmpty(), onClick = { selectedSeries = series })
                                            }
                                        }
                                        if (movieItems.isEmpty() && grouped.isEmpty()) item {
                                            EmptyPanel(Icons.Default.VideoLibrary, "Nenhum vídeo compatível", "Verifique o formato e as permissões de acesso.")
                                        }
                                    } else item {
                                        EmptyPanel(
                                            Icons.Default.VideoLibrary, "Sua biblioteca de vídeos",
                                            "Vídeos locais compatíveis aparecerão aqui. Séries com nomes S01E01 serão agrupadas automaticamente."
                                        )
                                    }
                                }
                            }
                        }

                        }

                        if (currentVideo != null && showPlayer) {
                            VideoPlaybackPanel(
                                player = player,
                                title = currentVideo!!.title,
                                onClose = { player.pause(); showPlayer = false },
                                onFullscreen = {
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    showFullscreenVideo = true
                                }
                            )
                        } else if (currentAudio != null) {
                            MiniPlayer(
                                title = currentAudio!!.title,
                                subtitle = currentAudio!!.artist,
                                playing = playing,
                                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                                onNext = { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                            )
                        }
                        if (showFullscreenVideo && currentVideo != null) {
                            Dialog(
                                onDismissRequest = {
                                    showFullscreenVideo = false
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                },
                                properties = DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false
                                )
                            ) {
                                AndroidView(
                                    factory = { context ->
                                        PlayerView(context).apply {
                                            player = this@MainActivity.player
                                            useController = true
                                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                            setKeepContentOnPlayerReset(true)
                                            layoutParams = android.view.ViewGroup.LayoutParams(
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    update = { it.player = this@MainActivity.player }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    private fun refreshPermissions() {
        audioPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        videoPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            buildList {
                if (!audioPermission) add(Manifest.permission.READ_MEDIA_AUDIO)
                if (!videoPermission) add(Manifest.permission.READ_MEDIA_VIDEO)
            }.toTypedArray()
        } else if (!audioPermission || !videoPermission) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else emptyArray()
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions)
    }

    private fun loadFavorites(): Set<String> =
        getSharedPreferences("gaplin_music", MODE_PRIVATE)
            .getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized) refreshPermissions()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}

@Composable
private fun TopBrand(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("GAPLIN", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("MUSIC", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text("Sua central de entretenimento", color = TextMuted, fontSize = 12.sp)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, "Atualizar biblioteca", tint = VioletSoft)
        }
    }
}

@Composable
private fun HomeScreen(
    catalog: MediaSnapshot,
    currentAudio: AudioTrack?,
    currentVideo: VideoEntry?,
    playing: Boolean,
    onMusic: () -> Unit,
    onVideos: () -> Unit,
    onFiles: () -> Unit,
    onPlayAudio: (AudioTrack) -> Unit,
    onPlayVideo: (VideoEntry) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(13.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF472078), Color(0xFF211632), Color(0xFF11101A))))
                    .padding(19.dp)
            ) {
                Column {
                    Text("SEU UNIVERSO DE MÍDIA", color = VioletSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Música, filmes e séries.\nTudo em um só lugar.", color = Color.White, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Organize sua biblioteca local com a experiência Gaplin.", color = Color(0xFFD0C5E0), fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onMusic) { Icon(Icons.Default.MusicNote, null); Text("Músicas") }
                        FilledTonalButton(onClick = onVideos) { Icon(Icons.Default.Movie, null); Text("Vídeos") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StatCard("MÚSICAS", catalog.audio.size.toString(), Icons.Default.MusicNote, Modifier.weight(1f))
                StatCard("VÍDEOS", catalog.videos.size.toString(), Icons.Default.Movie, Modifier.weight(1f))
                StatCard("PASTAS", catalog.folders.size.toString(), Icons.Default.Folder, Modifier.weight(1f))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(SurfaceDark)
                    .clickable(onClick = onFiles).padding(15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = VioletSoft, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Explorador de arquivos", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Navegue por pastas de áudio e vídeo", color = TextMuted, fontSize = 11.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Adicionados recentemente", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Atualizar", color = VioletSoft) }
            }
        }
        items(catalog.audio.take(5), key = { "home:${it.uri}" }) { track ->
            AudioRow(track, currentAudio, false, { onPlayAudio(track) }, null)
        }
        items(catalog.videos.take(3), key = { "home-video:${it.uri}" }) { video ->
            VideoRow(video) { onPlayVideo(video) }
        }
        if (catalog.audio.isEmpty() && catalog.videos.isEmpty()) item {
            EmptyPanel(Icons.Default.LibraryMusic, "Sua biblioteca está vazia", "Permita o acesso a músicas e vídeos e toque em atualizar para fazer a varredura.")
        }
        if (currentAudio != null || currentVideo != null) item {
            Text(
                if (playing) "REPRODUZINDO AGORA" else "ÚLTIMA MÍDIA SELECIONADA",
                color = VioletSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(SurfaceDark).padding(12.dp)) {
        Icon(icon, null, tint = VioletSoft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 9.sp, letterSpacing = .7.sp)
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().padding(bottom = 11.dp),
        placeholder = { Text("Buscar nesta biblioteca", color = TextMuted) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = VioletSoft) },
        singleLine = true, shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Violet, unfocusedBorderColor = Color(0xFF393344),
            focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = VioletSoft
        )
    )
}

@Composable
private fun AudioList(
    tracks: List<AudioTrack>,
    current: AudioTrack?,
    favorites: Set<String>,
    onPlay: (AudioTrack) -> Unit,
    onFavorite: (AudioTrack) -> Unit
) {
    if (tracks.isEmpty()) {
        EmptyPanel(Icons.Default.MusicOff, "Nenhuma música encontrada", "Confira as permissões e atualize a biblioteca.")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(tracks, key = { it.uri.toString() }) { track ->
                AudioRow(track, current, track.uri.toString() in favorites, { onPlay(track) }, { onFavorite(track) })
            }
        }
    }
}

@Composable
private fun AudioRow(
    track: AudioTrack,
    current: AudioTrack?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(if (current?.uri == track.uri) SurfaceRaised else Color.Transparent)
            .clickable(onClick = onPlay).padding(horizontal = 9.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(43.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = VioletSoft)
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onFavorite != null) {
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) VioletSoft else TextMuted)
            }
        }
        Icon(Icons.Default.PlayCircleFilled, null, tint = VioletSoft, modifier = Modifier.size(25.dp).padding(end = 3.dp))
    }
}

@Composable
private fun VideoRow(video: VideoEntry, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceDark)
            .clickable(onClick = onPlay).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(76.dp, 58.dp).clip(RoundedCornerShape(11.dp)).background(SurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayCircleFilled, null, tint = VioletSoft, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(video.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(video.folderName, color = TextMuted, fontSize = 11.sp, maxLines = 1)
            Text(formatDuration(video.durationMs), color = VioletSoft, fontSize = 10.sp)
        }
        Icon(Icons.Default.PlayArrow, null, tint = VioletSoft)
    }
}

@Composable
private fun SeriesCard(name: String, episodes: List<VideoEntry>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(SurfaceDark).clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Tv, null, tint = VioletSoft)
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("${episodes.size} episódio(s) detectado(s)", color = TextMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.CollectionsBookmark, null, tint = VioletSoft)
    }
}

@Composable
private fun FolderCard(path: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(SurfaceDark)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, null, tint = VioletSoft, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(path.substringAfterLast('/'), color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(path, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count itens", color = VioletSoft, fontSize = 11.sp)
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
    }
}

@Composable
private fun MiniPlayer(title: String, subtitle: String, playing: Boolean, onToggle: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
            .background(SurfaceRaised).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.GraphicEq, null, tint = Cyan, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextMuted, fontSize = 10.sp, maxLines = 1)
        }
        IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = VioletSoft) }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
    }
}

@Composable
private fun VideoPlaybackPanel(player: ExoPlayer, title: String, onClose: () -> Unit, onFullscreen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceDark)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            IconButton(onClick = onFullscreen) { Icon(Icons.Default.Fullscreen, "Tela cheia", tint = VioletSoft) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = VioletSoft) }
        }
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (context.resources.displayMetrics.widthPixels * 9f / 16f).toInt()
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(190.dp),
            update = { it.player = player }
        )
    }
}

@Composable
private fun EmptyPanel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = VioletSoft, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(11.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(message, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
