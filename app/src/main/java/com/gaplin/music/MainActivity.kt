package com.gaplin.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import android.provider.MediaStore

private val Ink = Color(0xFF0C0B12)
private val Violet = Color(0xFF9B6DFF)

data class Track(val title:String, val artist:String, val uri:android.net.Uri)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(permissions)
        setContent {
            var tracks by remember { mutableStateOf(loadTracks()) }
            var selected by remember { mutableStateOf<Track?>(null) }
            MaterialTheme(colorScheme = darkColorScheme(primary=Violet, background=Ink, surface=Color(0xFF191622))) {
                Scaffold(containerColor=Ink, bottomBar={
                    NavigationBar(containerColor=Color(0xFF171420)) {
                        NavigationBarItem(selected=true,onClick={},icon={Icon(Icons.Default.LibraryMusic,null)},label={Text("Biblioteca")})
                        NavigationBarItem(selected=false,onClick={},icon={Icon(Icons.Default.FavoriteBorder,null)},label={Text("Favoritos")})
                        NavigationBarItem(selected=false,onClick={},icon={Icon(Icons.Default.VideoLibrary,null)},label={Text("Vídeos")})
                    }
                }) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
                        Text("GAPLIN", style=MaterialTheme.typography.labelLarge, color=Violet)
                        Text("Music", style=MaterialTheme.typography.headlineLarge, color=Color.White)
                        Text("Sua música. Do seu jeito.", color=Color.LightGray)
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Sua biblioteca", style=MaterialTheme.typography.titleLarge, color=Color.White, modifier=Modifier.weight(1f))
                            IconButton(onClick={ tracks=loadTracks() }) { Icon(Icons.Default.Refresh, "Atualizar", tint=Violet) }
                        }
                        if (tracks.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment=Alignment.Center) {
                                Text("Nenhuma música encontrada. Adicione arquivos ao aparelho e atualize.", color=Color.LightGray)
                            }
                        } else LazyColumn(Modifier.weight(1f)) {
                            items(tracks) { track ->
                                ListItem(headlineContent={Text(track.title,color=Color.White)}, supportingContent={Text(track.artist,color=Color.LightGray)}, leadingContent={Icon(Icons.Default.MusicNote,null,tint=Violet)}, trailingContent={IconButton(onClick={selected=track; player.setMediaItem(MediaItem.fromUri(track.uri)); player.prepare(); player.play()}){Icon(Icons.Default.PlayCircle,null,tint=Violet)}}, colors=ListItemDefaults.colors(containerColor=Ink))
                            }
                        }
                        selected?.let { current ->
                            Surface(color=Color(0xFF211B30), shape=RoundedCornerShape(18.dp), modifier=Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("TOCANDO AGORA",color=Violet,style=MaterialTheme.typography.labelSmall); Text(current.title,color=Color.White) }
                                    IconButton(onClick={if(player.isPlaying) player.pause() else player.play()}) { Icon(if(player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,null,tint=Violet) }
                                    IconButton(onClick={player.seekToNext()}) { Icon(Icons.Default.SkipNext,null,tint=Violet) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun loadTracks(): List<Track> {
        val result=mutableListOf<Track>()
        val collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST)
        contentResolver.query(collection,projection,null,null,MediaStore.Audio.Media.TITLE+" ASC")?.use { c ->
            val id=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while(c.moveToNext()) result.add(Track(c.getString(title),c.getString(artist),android.content.ContentUris.withAppendedId(collection,c.getLong(id))))
        }
        return result
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}
