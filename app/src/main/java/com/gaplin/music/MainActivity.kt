import androidx.compose.animation.core.animateFloat
package com.gaplin.music

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.ui.PlayerView
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val Bg = Color(0xFF050D22)
private val SurfaceDark = Color(0xFF0D1B3A)
private val SurfaceRaised = Color(0xFF17284D)
private val Violet = Color(0xFFEA168F)
private val VioletSoft = Color(0xFFB67CFF)
private val Cyan = Color(0xFF4AA8FF)
private val TextMuted = Color(0xFF8798BD)

private data class LocalPlaylist(val id: String, val name: String, val trackUris: List<String>)

private enum class AppTab(val label: String) {
    HOME("Início"), MUSIC("Música"), FILES("Pastas"), FAVORITES("Favoritos"), VIDEOS("Vídeo"), MORE("Mais")
}

class MainActivity : ComponentActivity() {
    private lateinit var player: MediaController
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playerReady by mutableStateOf(false)
    private var controllerFailed by mutableStateOf(false)
    private var sleepTimerJob: Job? = null
    private var audioPermission by mutableStateOf(false)
    private var videoPermission by mutableStateOf(false)
    private var mediaRefreshToken by mutableStateOf(0)
    private var selectedTreeUri by mutableStateOf<Uri?>(null)

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
        connectPlaybackController()
        selectedTreeUri = getSharedPreferences("gaplin_music", MODE_PRIVATE)
            .getString("media_tree_uri", null)?.let(Uri::parse)
        refreshPermissions()
        requestMissingPermissions()

        setContent {
            var catalog by remember { mutableStateOf(MediaSnapshot(emptyList(), emptyList())) }
            var activeTab by remember { mutableStateOf(AppTab.MUSIC) }
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
            var showMusicPlayer by remember { mutableStateOf(false) }
            var musicSection by remember { mutableStateOf("Músicas") }
            var selectedMusicGroup by remember { mutableStateOf<String?>(null) }
            var playlists by remember { mutableStateOf(loadPlaylists()) }
            var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
            var showCreatePlaylist by remember { mutableStateOf(false) }
            var newPlaylistName by remember { mutableStateOf("") }
            var showPlaylistPicker by remember { mutableStateOf(false) }
            var trackToAdd by remember { mutableStateOf<AudioTrack?>(null) }
            var playbackQueue by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
            var previousVideoOrientation by remember { mutableStateOf(requestedOrientation) }
            var fullscreenVideoLandscape by remember { mutableStateOf(false) }
            var playbackError by remember { mutableStateOf<String?>(null) }
            var scanError by remember { mutableStateOf<String?>(null) }
            var scanInProgress by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }

            fun scanLibrary() {
                if (scanInProgress) return
                scope.launch {
                    scanInProgress = true
                    val treeUri = selectedTreeUri
                    try {
                        val snapshot = withContext(Dispatchers.IO) {
                            val indexed = MediaCatalog.scan(
                                this@MainActivity,
                                includeAudio = audioPermission,
                                includeVideo = videoPermission
                            )
                            val selected = treeUri?.let {
                                MediaCatalog.scanSelectedTree(this@MainActivity, it)
                            }
                            if (selected == null) indexed else MediaSnapshot(
                                audio = (indexed.audio + selected.audio).distinctBy { it.uri.toString() },
                                videos = (indexed.videos + selected.videos).distinctBy { it.uri.toString() }
                            )
                        }
                        catalog = snapshot
                        favoriteUris = loadFavorites()
                    } catch (_: SecurityException) {
                        scanError = "O acesso a uma pasta foi revogado. Selecione a pasta novamente."
                    } catch (_: Exception) {
                        scanError = "Não foi possível atualizar a biblioteca. Tente novamente."
                    } finally {
                        scanInProgress = false
                    }
                }
            }

            LaunchedEffect(audioPermission, videoPermission, selectedTreeUri, mediaRefreshToken) {
                if (audioPermission || videoPermission || selectedTreeUri != null) scanLibrary()
            }

            DisposableEffect(playerReady) {
                if (!playerReady) return@DisposableEffect onDispose { }
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) playing = false
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val mediaId = mediaItem?.mediaId ?: return
                        if (videoMode) {
                            catalog.videos.firstOrNull { it.uri.toString() == mediaId }?.let { currentVideo = it }
                        } else {
                            catalog.audio.firstOrNull { it.uri.toString() == mediaId }?.let { currentAudio = it }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playing = false
                        playbackError = "Não foi possível reproduzir este arquivo. Verifique o formato ou o acesso ao arquivo."
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            fun persistPlaylists(updated: List<LocalPlaylist>) {
                playlists = updated
                savePlaylists(updated)
            }

            fun createPlaylist(name: String, initialTrack: AudioTrack? = null) {
                val cleanName = name.trim()
                if (cleanName.isEmpty()) return
                val newPlaylist = LocalPlaylist(
                    id = java.util.UUID.randomUUID().toString(),
                    name = cleanName,
                    trackUris = initialTrack?.let { listOf(it.uri.toString()) } ?: emptyList()
                )
                persistPlaylists(playlists + newPlaylist)
                selectedPlaylistId = newPlaylist.id
                musicSection = "Playlists"
                selectedMusicGroup = null
            }

            fun addTrackToPlaylist(playlistId: String, track: AudioTrack) {
                val updated = playlists.map { playlist ->
                    if (playlist.id == playlistId && track.uri.toString() !in playlist.trackUris) {
                        playlist.copy(trackUris = playlist.trackUris + track.uri.toString())
                    } else playlist
                }
                persistPlaylists(updated)
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

            fun playAudio(track: AudioTrack, sourceQueue: List<AudioTrack> = catalog.audio) {
                currentAudio = track
                currentVideo = null
                videoMode = false
                showPlayer = false
                val queue = sourceQueue.ifEmpty { listOf(track) }
                playbackQueue = queue
                val startIndex = queue.indexOfFirst { it.uri == track.uri }
                if (!playerReady) {
                    playbackError = "O mecanismo de reprodução ainda está iniciando."
                    return
                }
                fun mediaItem(item: AudioTrack) = MediaItem.Builder()
                    .setMediaId(item.uri.toString())
                    .setUri(item.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).setAlbumTitle(item.album).setArtworkUri(item.artworkUri).build()
                    )
                    .build()
                if (queue.isNotEmpty() && startIndex >= 0) {
                    player.setMediaItems(queue.map(::mediaItem), startIndex, 0L)
                } else {
                    player.setMediaItem(mediaItem(track))
                }
                player.prepare()
                player.play()
            }

            fun playVideo(video: VideoEntry) {
                if (!playerReady) {
                    playbackError = "O mecanismo de reprodução ainda está iniciando."
                    return
                }

                currentVideo = video
                currentAudio = null
                videoMode = true
                showPlayer = true
                previousVideoOrientation = requestedOrientation
                fullscreenVideoLandscape = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                showFullscreenVideo = true

                val item = MediaItem.Builder()
                    .setMediaId(video.uri.toString())
                    .setUri(video.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(video.title).build())
                    .build()
                player.setMediaItem(item)
                player.prepare()
                player.play()
            }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Violet, secondary = Cyan, background = Bg, surface = SurfaceDark
            )) {
                LaunchedEffect(playbackError, scanError, controllerFailed) {
                    val message = playbackError ?: scanError ?: if (controllerFailed) {
                        "O serviço de reprodução não conseguiu iniciar. Feche e abra o aplicativo novamente."
                    } else null
                    if (message != null) snackbarHostState.showSnackbar(message)
                    playbackError = null
                    scanError = null
                }
                Scaffold(
                    containerColor = Bg,
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            snackbar = { data ->
                                Snackbar(snackbarData = data, containerColor = SurfaceRaised, contentColor = Color.White)
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF08142F), tonalElevation = 0.dp) {
                            listOf(AppTab.MUSIC, AppTab.VIDEOS, AppTab.MORE).forEach { tab ->
                                val moreSelected = activeTab in setOf(AppTab.HOME, AppTab.FILES, AppTab.FAVORITES, AppTab.MORE)
                                NavigationBarItem(
                                    selected = if (tab == AppTab.MORE) moreSelected else activeTab == tab,
                                    onClick = {
                                        activeTab = tab
                                        selectedFolder = null
                                        selectedSeries = null
                                        selectedMusicGroup = null
                                        selectedPlaylistId = null
                                        search = ""
                                    },
                                    icon = {
                                        Icon(
                                            when (tab) {
                                                AppTab.MUSIC -> Icons.Default.MusicNote
                                                AppTab.VIDEOS -> Icons.Default.VideoLibrary
                                                else -> Icons.Default.MoreHoriz
                                            }, contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label, fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = Violet,
                                        indicatorColor = Violet.copy(alpha = .19f),
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
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            when (activeTab) {
                            AppTab.HOME -> HomeScreen(
                                catalog = catalog,
                                currentAudio = currentAudio,
                                currentVideo = currentVideo,
                                playing = playing,
                                onMusic = { activeTab = AppTab.MUSIC },
                                onVideos = { activeTab = AppTab.VIDEOS },
                                onFiles = { activeTab = AppTab.FILES },
                                onPlayAudio = { playAudio(it, catalog.audio) },
                                onPlayVideo = ::playVideo,
                                onRefresh = ::scanLibrary
                            )
                            AppTab.MUSIC -> {
                                ScreenHeading("Músicas", "${catalog.audio.size} faixas na sua biblioteca")
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    listOf("Músicas", "Playlists", "Pastas", "Artistas", "Álbuns").forEach { section ->
                                        Text(
                                            section,
                                            modifier = Modifier.clip(RoundedCornerShape(18.dp))
                                                .background(if (musicSection == section) Violet.copy(alpha = .20f) else SurfaceDark)
                                                .clickable {
                                                    musicSection = section
                                                    selectedMusicGroup = null
                                                    selectedPlaylistId = null
                                                }
                                                .padding(horizontal = 14.dp, vertical = 9.dp),
                                            color = if (musicSection == section) Color.White else TextMuted,
                                            fontSize = 12.sp,
                                            fontWeight = if (musicSection == section) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                                SearchBox(search) { search = it }
                                val filtered = catalog.audio.filter {
                                    it.title.contains(search, true) || it.artist.contains(search, true) ||
                                        it.album.contains(search, true) || it.folderPath.contains(search, true)
                                }
                                val groupedTracks = when (musicSection) {
                                    "Pastas" -> filtered.groupBy { it.folderPath }
                                    "Artistas" -> filtered.groupBy { it.artist }
                                    "Álbuns" -> filtered.groupBy { it.album }
                                    else -> emptyMap()
                                }
                                when (musicSection) {
                                    "Músicas" -> AudioList(
                                        filtered, currentAudio, favoriteUris,
                                        { playAudio(it, filtered) }, ::toggleFavorite
                                    )
                                    "Playlists" -> {
                                        val selectedPlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
                                        if (selectedPlaylist != null) {
                                            val tracks = selectedPlaylist.trackUris.mapNotNull { uri ->
                                                catalog.audio.firstOrNull { it.uri.toString() == uri }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(onClick = { selectedPlaylistId = null }) {
                                                    Icon(Icons.Default.ArrowBack, null, tint = VioletSoft)
                                                }
                                                Column(Modifier.weight(1f)) {
                                                    Text(selectedPlaylist.name, color = Color.White, fontWeight = FontWeight.Bold)
                                                    Text("${tracks.size} faixas", color = TextMuted, fontSize = 11.sp)
                                                }
                                            }
                                            AudioList(tracks, currentAudio, favoriteUris, { playAudio(it, tracks) }, ::toggleFavorite)
                                        } else {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("Suas playlists", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                                FilledTonalButton(onClick = { newPlaylistName = ""; trackToAdd = null; showCreatePlaylist = true }) {
                                                    Icon(Icons.Default.Add, null)
                                                    Text("Criar")
                                                }
                                            }
                                            if (playlists.isEmpty()) {
                                                EmptyPanel(Icons.Default.QueueMusic, "Monte suas playlists", "Crie coleções locais com suas músicas favoritas.")
                                            } else {
                                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                                    items(playlists, key = { it.id }) { playlist ->
                                                        val count = playlist.trackUris.count { uri -> catalog.audio.any { it.uri.toString() == uri } }
                                                        PlaylistCard(playlist.name, count) { selectedPlaylistId = playlist.id }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        if (selectedMusicGroup != null) {
                                            val tracks = groupedTracks[selectedMusicGroup].orEmpty()
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(onClick = { selectedMusicGroup = null }) {
                                                    Icon(Icons.Default.ArrowBack, null, tint = VioletSoft)
                                                }
                                                Text(selectedMusicGroup!!, color = Color.White, fontWeight = FontWeight.Bold)
                                            }
                                            AudioList(tracks, currentAudio, favoriteUris, { playAudio(it, tracks) }, ::toggleFavorite)
                                        } else if (groupedTracks.isEmpty()) {
                                            EmptyPanel(Icons.Default.LibraryMusic, "Nada encontrado", "Adicione músicas à biblioteca e atualize a varredura.")
                                        } else {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                                items(groupedTracks.keys.sortedBy { it.lowercase() }, key = { it }) { group ->
                                                    MusicGroupCard(
                                                        title = group.substringAfterLast('/'),
                                                        subtitle = group.takeIf { musicSection == "Pastas" } ?: musicSection,
                                                        count = groupedTracks[group].orEmpty().size,
                                                        icon = when (musicSection) {
                                                            "Pastas" -> Icons.Default.Folder
                                                            "Artistas" -> Icons.Default.Person
                                                            else -> Icons.Default.Album
                                                        },
                                                        onClick = { selectedMusicGroup = group }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            AppTab.FAVORITES -> {
                                ScreenHeading("Sua coleção", "${favoriteUris.size} músicas salvas")
                                val favorites = catalog.audio.filter { it.uri.toString() in favoriteUris }
                                if (favorites.isEmpty()) EmptyPanel(
                                    Icons.Default.FavoriteBorder, "Seus favoritos começam aqui",
                                    "Use o coração ao lado de uma música para guardá-la nesta coleção."
                                ) else AudioList(favorites, currentAudio, favoriteUris, { playAudio(it, favorites) }, ::toggleFavorite)
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
                                        try {
                                            contentResolver.releasePersistableUriPermission(
                                                selectedTreeUri!!,
                                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            )
                                        } catch (_: SecurityException) { }
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
                                                { playAudio(track, folderAudio) }, { toggleFavorite(track) })
                                        }
                                        if (folderVideos.isNotEmpty()) item { Text("VÍDEOS", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                        items(folderVideos, key = { it.uri.toString() }) { video ->
                                            VideoRow(video, { playVideo(video) })
                                        }
                                    }
                                }
                            }
                            AppTab.MORE -> MoreScreen(
                                onHome = { activeTab = AppTab.HOME },
                                onFolders = { activeTab = AppTab.FILES },
                                onFavorites = { activeTab = AppTab.FAVORITES },
                                onManager = {
                                    startActivity(Intent(this@MainActivity, FileManagerActivity::class.java))
                                }
                            )
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

                        if (currentVideo != null && showPlayer && !showFullscreenVideo && playerReady) {
                            VideoPlaybackPanel(
                                player = player,
                                title = currentVideo!!.title,
                                onClose = { player.pause(); showPlayer = false; currentVideo = null; videoMode = false },
                                onFullscreen = {
                                    previousVideoOrientation = requestedOrientation
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    showFullscreenVideo = true
                                }
                            )
                        } else if (currentAudio != null && playerReady) {
                            MiniPlayer(
                                track = currentAudio!!,
                                playing = playing,
                                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                                onNext = { if (player.hasNextMediaItem()) player.seekToNextMediaItem() },
                                onOpen = { showMusicPlayer = true }
                            )
                            if (showMusicPlayer && currentAudio != null) {
                                NowPlayingDialog(
                                    player = player,
                                    track = currentAudio!!,
                                    queue = playbackQueue.ifEmpty { catalog.audio },
                                    favorite = currentAudio!!.uri.toString() in favoriteUris,
                                    onFavorite = { toggleFavorite(currentAudio!!) },
                                    onAddToPlaylist = { trackToAdd = currentAudio; showPlaylistPicker = true },
                                    onStartTimer = ::startSleepTimer,
                                    onCancelTimer = ::cancelSleepTimer,
                                    onDismiss = { showMusicPlayer = false }
                                )
                            }
                        }
                        if (showPlaylistPicker && trackToAdd != null) {
                            AlertDialog(
                                onDismissRequest = { showPlaylistPicker = false; trackToAdd = null },
                                title = { Text("Adicionar à playlist") },
                                text = {
                                    if (playlists.isEmpty()) {
                                        Text("Você ainda não criou playlists.")
                                    } else {
                                        Column {
                                            playlists.forEach { playlist ->
                                                TextButton(
                                                    onClick = {
                                                        addTrackToPlaylist(playlist.id, trackToAdd!!)
                                                        showPlaylistPicker = false
                                                        trackToAdd = null
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(playlist.name, modifier = Modifier.weight(1f))
                                                    Text("${playlist.trackUris.size} faixas", color = TextMuted, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showPlaylistPicker = false
                                        newPlaylistName = ""
                                        showCreatePlaylist = true
                                    }) { Text("Nova playlist") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showPlaylistPicker = false; trackToAdd = null }) { Text("Fechar") }
                                }
                            )
                        }

                        if (showCreatePlaylist) {
                            AlertDialog(
                                onDismissRequest = { showCreatePlaylist = false; trackToAdd = null },
                                title = { Text("Criar playlist") },
                                text = {
                                    OutlinedTextField(
                                        value = newPlaylistName,
                                        onValueChange = { newPlaylistName = it.take(40) },
                                        label = { Text("Nome da playlist") },
                                        singleLine = true
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = newPlaylistName.isNotBlank(),
                                        onClick = {
                                            createPlaylist(newPlaylistName, trackToAdd)
                                            newPlaylistName = ""
                                            trackToAdd = null
                                            showCreatePlaylist = false
                                        }
                                    ) { Text("Criar") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showCreatePlaylist = false; trackToAdd = null }) { Text("Cancelar") }
                                }
                            )
                        }

                        if (showFullscreenVideo && currentVideo != null && playerReady) {
                            Dialog(
                                onDismissRequest = {
                                    showFullscreenVideo = false
                                    fullscreenVideoLandscape = false
                                    requestedOrientation = previousVideoOrientation
                                },
                                properties = DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false
                                )
                            ) {
                                val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                                SideEffect {
                                    dialogWindow?.let { window ->
                                        WindowCompat.setDecorFitsSystemWindows(window, false)
                                        WindowInsetsControllerCompat(window, window.decorView)
                                            .hide(WindowInsetsCompat.Type.systemBars())
                                    }
                                }
                                DisposableEffect(dialogWindow) {
                                    onDispose {
                                        dialogWindow?.let { window ->
                                            WindowInsetsControllerCompat(window, window.decorView)
                                                .show(WindowInsetsCompat.Type.systemBars())
                                        }
                                    }
                                }
                                Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                                    Row(
                                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color.Black.copy(alpha = .48f)),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                fullscreenVideoLandscape = !fullscreenVideoLandscape
                                                requestedOrientation = if (fullscreenVideoLandscape) {
                                                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                                } else {
                                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.ScreenRotation,
                                                if (fullscreenVideoLandscape) "Voltar para vertical" else "Girar para horizontal",
                                                tint = Color.White
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                showFullscreenVideo = false
                                                fullscreenVideoLandscape = false
                                                requestedOrientation = previousVideoOrientation
                                            }
                                        ) {
                                            Icon(Icons.Default.Close, "Sair da tela cheia", tint = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = lifecycleScope.launch {
            delay(minutes.coerceIn(1, 240) * 60_000L)
            if (playerReady) player.pause()
            sleepTimerJob = null
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private fun connectPlaybackController() {
        val token = SessionToken(this, ComponentName(this, com.gaplin.music.playback.PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            try {
                player = future.get()
                playerReady = true
            } catch (_: Exception) {
                playerReady = false
                controllerFailed = true
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshPermissions() {
        audioPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        videoPermission = if (Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 34) {
            buildList {
                if (!audioPermission) {
                    add(Manifest.permission.READ_MEDIA_AUDIO)
                }
                if (!videoPermission) {
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }.toTypedArray()
        } else if (Build.VERSION.SDK_INT >= 33) {
            buildList {
                if (!audioPermission) add(Manifest.permission.READ_MEDIA_AUDIO)
                if (!videoPermission) add(Manifest.permission.READ_MEDIA_VIDEO)
            }.toTypedArray()
        } else if (!audioPermission || !videoPermission) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else emptyArray()
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions)
    }

    private fun loadPlaylists(): List<LocalPlaylist> {
        val raw = getSharedPreferences("gaplin_music", MODE_PRIVATE).getString("playlists", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val tracks = item.optJSONArray("tracks") ?: JSONArray()
                    val uris = (0 until tracks.length())
                        .map { tracks.optString(it) }
                        .filter { it.isNotBlank() }
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) add(LocalPlaylist(id, name, uris))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun savePlaylists(playlists: List<LocalPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("tracks", JSONArray(playlist.trackUris))
            )
        }
        getSharedPreferences("gaplin_music", MODE_PRIVATE).edit()
            .putString("playlists", array.toString())
            .apply()
    }

    private fun loadFavorites(): Set<String> =
        getSharedPreferences("gaplin_music", MODE_PRIVATE)
            .getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        mediaRefreshToken += 1
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}

@Composable
private fun TopBrand(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_gaplin_logo),
            contentDescription = "Símbolo Gaplin Music",
            modifier = Modifier.size(54.dp).padding(end = 7.dp)
        )
        Column(Modifier.weight(1f)) {
            Text("GAPLIN", color = VioletSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("MUSIC", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text("Seu universo de mídia", color = TextMuted, fontSize = 11.sp)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, "Atualizar biblioteca", tint = VioletSoft)
        }
    }
}

@Composable
private fun MoreScreen(
    onHome: () -> Unit,
    onFolders: () -> Unit,
    onFavorites: () -> Unit,
    onManager: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(vertical = 10.dp)) {
        ScreenHeading("Mais do Gaplin", "Acesse suas ferramentas e coleções")
        MoreOptionCard("Painel inicial", "Resumo da sua biblioteca", Icons.Default.Home, onHome)
        MoreOptionCard("Explorador de pastas", "Áudio e vídeo organizados por diretório", Icons.Default.FolderOpen, onFolders)
        MoreOptionCard("Favoritos", "Suas músicas salvas", Icons.Default.Favorite, onFavorites)
        MoreOptionCard(
            "Gerenciador inteligente",
            "Selecionar, mover, excluir e encontrar arquivos duplicados",
            Icons.Default.FolderOpen,
            onManager
        )
        Spacer(Modifier.height(20.dp))
        Text("GAPLIN MUSIC", color = VioletSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Sua mídia. Seu espaço. Sua experiência.", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun MoreOptionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark).clickable(onClick = onClick).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF34236F), Color(0xFF123E69)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = VioletSoft, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
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
                    .background(Brush.linearGradient(listOf(Color(0xFF0B2857), Color(0xFF25144E), Color(0xFF080F25))))
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
    val shape = RoundedCornerShape(15.dp)
    Box(
        Modifier.fillMaxWidth()
            .padding(bottom = 11.dp)
            .clip(shape)
            .background(SurfaceDark)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar nesta biblioteca", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = VioletSoft) },
            singleLine = true,
            shape = shape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Violet,
                unfocusedBorderColor = Color(0xFF50618A),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = VioletSoft
            )
        )
    }
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
        TrackArtwork(track, Modifier.size(43.dp).clip(RoundedCornerShape(12.dp)))
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
        VideoArtwork(video, Modifier.size(116.dp, 72.dp).clip(RoundedCornerShape(11.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(video.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(video.folderName, color = TextMuted, fontSize = 11.sp, maxLines = 1)
            Text(formatDuration(video.durationMs), color = VioletSoft, fontSize = 10.sp)
        }
        Icon(Icons.Default.PlayArrow, null, tint = VioletSoft)
    }
}

@Composable
private fun VideoArtwork(video: VideoEntry, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var artwork by remember(video.uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(video.uri) {
        artwork = withContext(Dispatchers.IO) {
            val thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        video.uri,
                        Size(480, 270),
                        CancellationSignal()
                    )
                }.getOrNull()
            } else null

            val frame = thumbnail ?: runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, video.uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            1_000_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            480,
                            270
                        ) ?: retriever.frameAtTime
                    } else {
                        retriever.frameAtTime
                    }
                } finally {
                    retriever.release()
                }
            }.getOrNull()

            frame?.asImageBitmap()
        }
    }

    Box(
        modifier.background(
            Brush.linearGradient(listOf(Color(0xFF1B3565), Color(0xFF35205F)))
        ),
        contentAlignment = Alignment.Center
    ) {
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = "Capa do vídeo: ${video.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: Icon(
            Icons.Default.VideoLibrary,
            contentDescription = null,
            tint = VioletSoft,
            modifier = Modifier.size(30.dp)
        )

        Box(
            Modifier.align(Alignment.Center)
                .size(30.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.48f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Reproduzir vídeo",
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun SeriesCard(name: String, episodes: List<VideoEntry>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(SurfaceDark).clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        episodes.firstOrNull()?.let { firstEpisode ->
            VideoArtwork(firstEpisode, Modifier.size(76.dp, 58.dp).clip(RoundedCornerShape(11.dp)))
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("${episodes.size} episódio(s) detectado(s)", color = TextMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.CollectionsBookmark, null, tint = VioletSoft)
    }
}

@Composable
private fun MusicGroupCard(
    title: String,
    subtitle: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(SurfaceDark)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF38236C), Color(0xFF12345E)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = VioletSoft, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count faixas · $subtitle", color = TextMuted, fontSize = 11.sp, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
    }
}

@Composable
private fun PlaylistCard(name: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(SurfaceDark)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6C247D), Color(0xFF1B3572)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.QueueMusic, null, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("$count músicas", color = TextMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
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
private fun MiniPlayer(track: AudioTrack, playing: Boolean, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF35217A), Color(0xFF8F247B), Color(0xFF172B56))))
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork(track, Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = TextMuted, fontSize = 10.sp, maxLines = 1)
        }
        IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = VioletSoft) }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
    }
}


@Composable
private fun TrackArtwork(track: AudioTrack, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var artwork by remember(track.artworkUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(track.artworkUri) {
        artwork = withContext(Dispatchers.IO) {
            track.artworkUri?.let { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }

    if (artwork != null) {
        Image(
            bitmap = artwork!!,
            contentDescription = "Capa de ${track.album}",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier.background(Brush.linearGradient(listOf(Color(0xFF48248A), Color(0xFF102A58)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFD9C4FF), modifier = Modifier.size(25.dp))
        }
    }
}


@Composable
private fun ParticleAudioVisualizer(
    isPlaying: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "audio-particles")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = if (isPlaying) 4200 else 9000,
                easing = androidx.compose.animation.core.LinearEasing
            )
        ),
        label = "particle-phase"
    )
    val energy = if (isPlaying) 1f else 0.16f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h * 0.52f
        val rows = 27
        val columns = 64
        val phaseShift = phase + progress * 5.5f
        val colors = listOf(
            Color(0xFF176BFF), Color(0xFF19C8FF),
            Color(0xFFB52CFF), Color(0xFFFF36C8),
            Color(0xFFFF8A35)
        )
        for (row in 0 until rows) {
            val rowFactor = row.toFloat() / (rows - 1)
            for (column in 0 until columns) {
                val xFactor = column.toFloat() / (columns - 1)
                val x = w * (0.035f + xFactor * 0.93f)
                val edgeFade = kotlin.math.sin(Math.PI * xFactor).toFloat().coerceAtLeast(0f)
                val carrier = kotlin.math.sin(xFactor * 17.0 + phaseShift * 1.35 + rowFactor * 2.4).toFloat()
                val ripple = kotlin.math.cos(xFactor * 29.0 - phaseShift * 1.8 + rowFactor * 4.0).toFloat()
                val envelope = (0.24f + 0.76f * edgeFade) * energy
                val y = centerY +
                    (rowFactor - 0.5f) * h * 0.64f +
                    carrier * h * 0.105f * envelope +
                    ripple * h * 0.035f * envelope
                val perspective = 0.55f + 0.45f * (1f - kotlin.math.abs(rowFactor - 0.5f) * 2f)
                val pulse = (0.72f + 0.28f * kotlin.math.sin(phaseShift * 2.0 + column * 0.19 + row * 0.13).toFloat())
                val colorIndex = ((xFactor * (colors.size - 1)).toInt()).coerceIn(0, colors.lastIndex)
                val colorMix = (xFactor * (colors.size - 1) - colorIndex).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(colors[colorIndex], colors[(colorIndex + 1).coerceAtMost(colors.lastIndex)], colorMix)
                val alpha = (0.24f + 0.76f * pulse) * edgeFade.coerceAtLeast(0.12f)
                val radius = (0.65f + 1.35f * pulse) * perspective
                drawCircle(
                    color = color.copy(alpha = alpha * 0.22f),
                    radius = radius * 3.1f,
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            }
        }
        // Bright central energy ribbon creates the depth and luminous ring effect.
        for (column in 0 until 110) {
            val xFactor = column / 109f
            val x = w * (0.02f + xFactor * 0.96f)
            val wave = kotlin.math.sin(xFactor * 20.0 + phaseShift * 1.7).toFloat()
            val y = centerY + wave * h * 0.13f * energy
            val color = if (xFactor < 0.5f) Color(0xFF16BFFF) else Color(0xFFFF35D0)
            drawCircle(color.copy(alpha = 0.75f), radius = 1.35f, center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

@Composable
private fun NowPlayingDialog(
    player: Player,
    track: AudioTrack,
    queue: List<AudioTrack>,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onStartTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    var position by remember(track.uri) { mutableStateOf(0L) }
    var duration by remember(track.uri) { mutableStateOf(track.durationMs.coerceAtLeast(0L)) }
    var sliderValue by remember(track.uri) { mutableStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var timerMinutes by remember { mutableStateOf("30") }
    var speed by remember { mutableStateOf(1f) }
    var volume by remember { mutableStateOf(player.volume) }
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var repeatMode by remember { mutableStateOf(player.repeatMode) }
    LaunchedEffect(player, track.uri) {
        while (true) {
            if (!scrubbing) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.takeIf { it > 0L } ?: track.durationMs.coerceAtLeast(0L)
                sliderValue = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }
            delay(400)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xFF111A38), Color(0xFF1A163A), Color(0xFF090D1C), Color(0xFF050812))
                )
            )
        ) {
            Column(
                Modifier.fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, "Fechar player", tint = Color.White) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("TOCANDO AGORA", color = VioletSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("GAPLIN MUSIC", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    }
                    IconButton(onClick = { showQueue = !showQueue }) {
                        Icon(Icons.Default.QueueMusic, "Fila de reprodução", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(8.dp))
                ParticleAudioVisualizer(
                    isPlaying = player.isPlaying,
                    progress = sliderValue,
                    modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 180.dp, max = 330.dp)
                )
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TrackArtwork(track, Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
                        Text(track.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.album, color = VioletSoft, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onFavorite) {
                        Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Violet else Color.White, modifier = Modifier.size(26.dp))
                    }
                }

                Spacer(Modifier.height(7.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        scrubbing = true
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        if (duration > 0L) player.seekTo((duration * sliderValue).toLong())
                        scrubbing = false
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Violet,
                        inactiveTrackColor = Color(0xFF48516D)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(position), color = TextMuted, fontSize = 11.sp)
                    Text(formatDuration(duration), color = TextMuted, fontSize = 11.sp)
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        shuffle = !shuffle
                        player.shuffleModeEnabled = shuffle
                    }) { Icon(Icons.Default.Shuffle, "Aleatório", tint = if (shuffle) Violet else TextMuted) }
                    IconButton(onClick = {
                        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                        else player.seekTo(0L)
                    }) { Icon(Icons.Default.SkipPrevious, "Anterior", tint = Color.White, modifier = Modifier.size(34.dp)) }
                    FilledIconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(70.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color(0xFF15102B))
                    ) {
                        Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproduzir", modifier = Modifier.size(38.dp))
                    }
                    IconButton(onClick = {
                        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                    }) { Icon(Icons.Default.SkipNext, "Próxima", tint = Color.White, modifier = Modifier.size(34.dp)) }
                    IconButton(onClick = {
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        player.repeatMode = repeatMode
                    }) {
                        Icon(Icons.Default.Repeat, "Repetir", tint = if (repeatMode == Player.REPEAT_MODE_OFF) TextMuted else Violet)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeDown, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            player.volume = it
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Cyan, inactiveTrackColor = Color(0xFF48516D))
                    )
                    Text("${speed}×", color = TextMuted, fontSize = 11.sp, modifier = Modifier.clickable {
                        speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.75f; else -> 1f }
                        player.setPlaybackSpeed(speed)
                    })
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onAddToPlaylist) {
                        Icon(Icons.Default.PlaylistAdd, null, tint = VioletSoft)
                        Spacer(Modifier.width(5.dp))
                        Text("Playlist", color = Color.White, fontSize = 11.sp)
                    }
                    TextButton(onClick = { showTimer = true }) {
                        Icon(Icons.Default.Timer, null, tint = VioletSoft)
                        Spacer(Modifier.width(5.dp))
                        Text("Temporizador", color = Color.White, fontSize = 11.sp)
                    }
                    TextButton(onClick = { showQueue = !showQueue }) {
                        Icon(Icons.Default.QueueMusic, null, tint = VioletSoft)
                        Spacer(Modifier.width(5.dp))
                        Text("Fila", color = Color.White, fontSize = 11.sp)
                    }
                }

                if (showQueue) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 190.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceDark).padding(10.dp)) {
                        Text("FILA DE REPRODUÇÃO", color = VioletSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        LazyColumn {
                            items(queue, key = { it.uri.toString() }) { item ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        val index = queue.indexOfFirst { it.uri == item.uri }
                                        if (index >= 0 && index < player.mediaItemCount) {
                                            player.seekTo(index, 0L)
                                            player.play()
                                        }
                                    }.padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.title, color = if (item.uri == track.uri) VioletSoft else Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                    Text(item.artist, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Temporizador de sono") },
            text = {
                OutlinedTextField(
                    value = timerMinutes,
                    onValueChange = { timerMinutes = it.filter(Char::isDigit).take(3) },
                    label = { Text("Minutos") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = timerMinutes.toIntOrNull()?.coerceIn(1, 240) ?: 30
                    onStartTimer(minutes)
                    showTimer = false
                }) { Text("Iniciar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onCancelTimer()
                    showTimer = false
                }) { Text("Cancelar timer") }
            }
        )
    }
}

@Composable
private fun VideoPlaybackPanel(player: Player, title: String, onClose: () -> Unit, onFullscreen: () -> Unit) {
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
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
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
