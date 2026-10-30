package com.gaplin.music

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class FileManagerActivity : ComponentActivity() {
    private var selectedTreeUri by mutableStateOf<Uri?>(null)
    private var audioPermission by mutableStateOf(false)
    private var videoPermission by mutableStateOf(false)
    private var imagePermission by mutableStateOf(false)
    private var managerFiles by mutableStateOf<List<ManagedMediaFile>>(emptyList())
    private var duplicateGroups by mutableStateOf<List<DuplicateMediaGroup>>(emptyList())
    private var loading by mutableStateOf(false)
    private var duplicateScanRunning by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)

    private var legacyDeleteQueue: List<Uri> = emptyList()
    private var legacyDeleteIndex = 0
    private var legacyDeleteCallback: (() -> Unit)? = null
    private var mediaDeleteCallback: (() -> Unit)? = null
    private var legacyDeleteIsMove = false
    private var pendingMediaDeleteIsMove = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        scanFiles()
    }

    private val sourceFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                selectedTreeUri = uri
                getSharedPreferences("gaplin_music", MODE_PRIVATE).edit()
                    .putString("media_tree_uri", uri.toString()).apply()
                scanFiles()
            } catch (_: SecurityException) {
                statusMessage = "O Android não concedeu permissão de edição para esta pasta. Selecione-a novamente."
            }
        }
    }

    private var moveSources: List<ManagedMediaFile> = emptyList()
    private val destinationFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                val sources = moveSources
                moveSources = emptyList()
                moveFilesTo(uri, sources)
            } catch (_: SecurityException) {
                statusMessage = "Não foi possível obter permissão de escrita na pasta de destino."
            }
        } else {
            moveSources = emptyList()
        }
    }

    private val systemDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val callback = mediaDeleteCallback
        mediaDeleteCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && legacyDeleteQueue.isNotEmpty()) {
            if (result.resultCode == RESULT_OK) {
                deleteNextLegacyMediaStoreItem()
            } else {
                val alreadyDeleted = legacyDeleteQueue.take(legacyDeleteIndex)
                cleanupLocalReferences(alreadyDeleted)
                val count = legacyDeleteIndex
                legacyDeleteQueue = emptyList()
                legacyDeleteIndex = 0
                legacyDeleteCallback = null
                statusMessage = if (count > 0) {
                    "$count arquivo(s) foram excluídos antes do cancelamento; os demais foram mantidos."
                } else if (legacyDeleteIsMove) {
                    "A cópia no destino foi mantida, mas a exclusão dos originais foi cancelada."
                } else {
                    "A exclusão foi cancelada. Os arquivos originais foram mantidos."
                }
                legacyDeleteIsMove = false
                scanFiles()
            }
        } else if (result.resultCode == RESULT_OK) {
            pendingMediaDeleteIsMove = false
            callback?.invoke()
            scanFiles()
        } else {
            statusMessage = if (pendingMediaDeleteIsMove) {
                "A cópia no destino foi concluída, mas você cancelou a exclusão dos originais. Os originais e as cópias foram mantidos."
            } else {
                "A exclusão foi cancelada. Nenhum arquivo do MediaStore foi removido."
            }
            pendingMediaDeleteIsMove = false
            scanFiles()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTreeUri = getSharedPreferences("gaplin_music", MODE_PRIVATE)
            .getString("media_tree_uri", null)?.let(Uri::parse)
        refreshPermissions()
        setContent {
            val initialFilter = remember {
                intent.getStringExtra("initial_filter")
                    ?.let { runCatching { ManagerFilter.valueOf(it) }.getOrDefault(ManagerFilter.ALL) }
                    ?: ManagerFilter.ALL
            }
            var filter by remember { mutableStateOf(initialFilter) }
            var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
            var showDeleteConfirmation by remember { mutableStateOf(false) }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB67CFF),
                secondary = Color(0xFF4AA8FF),
                background = Color(0xFF050D22),
                surface = Color(0xFF0D1B3A)
            )) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .background(Color(0xFF050D22))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    FileManagerScreen(
                        files = managerFiles,
                        duplicates = duplicateGroups,
                        filter = filter,
                        selectedUris = selectedUris,
                        loading = loading,
                        duplicateScanRunning = duplicateScanRunning,
                        onFilterChange = {
                            filter = it
                            selectedUris = emptySet()
                        },
                        onToggleSelection = { file ->
                            selectedUris = if (file.stableId in selectedUris) {
                                selectedUris - file.stableId
                            } else {
                                selectedUris + file.stableId
                            }
                        },
                        onSelectAll = { files ->
                            val ids = files.map { it.stableId }.toSet()
                            selectedUris = if (ids.isNotEmpty() && ids.all { it in selectedUris }) {
                                selectedUris - ids
                            } else {
                                selectedUris + ids
                            }
                        },
                        onDelete = { showDeleteConfirmation = true },
                        onMove = {
                            moveSources = managerFiles.filter { it.stableId in selectedUris }
                            if (moveSources.isNotEmpty()) destinationFolderPicker.launch(null)
                        },
                        onScanDuplicates = { scanDuplicates() },
                        onRefresh = { scanFiles() },
                        onConnectFolder = { sourceFolderPicker.launch(selectedTreeUri) }
                    )
                }

                if (showDeleteConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmation = false },
                        title = { Text("Excluir arquivos selecionados?") },
                        text = {
                            Text(
                                "Você selecionou ${selectedUris.size} arquivo(s). O Android poderá pedir autorização para apagar itens da biblioteca. Esta ação não pode ser desfeita."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val files = managerFiles.filter { it.stableId in selectedUris }
                                showDeleteConfirmation = false
                                selectedUris = emptySet()
                                deleteFiles(files)
                            }) { Text("Excluir") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancelar") }
                        }
                    )
                }

                statusMessage?.let { message ->
                    AlertDialog(
                        onDismissRequest = { statusMessage = null },
                        title = { Text("Gerenciador de arquivos") },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = { statusMessage = null }) { Text("Entendi") }
                        }
                    )
                }

                LaunchedEffect(audioPermission, videoPermission, imagePermission, selectedTreeUri) {
                    scanFiles()
                }
            }
        }
        requestMissingPermissions()
    }

    private fun refreshPermissions() {
        audioPermission = hasPermission(Manifest.permission.READ_MEDIA_AUDIO, Build.VERSION_CODES.TIRAMISU)
        videoPermission = hasPermission(Manifest.permission.READ_MEDIA_VIDEO, Build.VERSION_CODES.TIRAMISU) ||
            (Build.VERSION.SDK_INT >= 34 && hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        imagePermission = hasPermission(Manifest.permission.READ_MEDIA_IMAGES, Build.VERSION_CODES.TIRAMISU) ||
            (Build.VERSION.SDK_INT >= 34 && hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        if (Build.VERSION.SDK_INT < 33) {
            val legacy = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            audioPermission = legacy
            videoPermission = legacy
            imagePermission = legacy
        }
    }

    private fun hasPermission(permission: String, minimumApi: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= minimumApi) {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (!audioPermission) missing += Manifest.permission.READ_MEDIA_AUDIO
            if (!videoPermission) missing += Manifest.permission.READ_MEDIA_VIDEO
            if (!imagePermission) missing += Manifest.permission.READ_MEDIA_IMAGES
            if (Build.VERSION.SDK_INT >= 34 && (!videoPermission || !imagePermission)) {
                missing += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }
        } else if (!audioPermission || !videoPermission || !imagePermission) {
            missing += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.distinct().toTypedArray())
    }

    private fun scanFiles() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            try {
                managerFiles = withContext(Dispatchers.IO) {
                    FileManagerRepository.scan(
                        context = this@FileManagerActivity,
                        treeUri = selectedTreeUri,
                        includeAudio = audioPermission,
                        includeVideo = videoPermission,
                        includeImages = imagePermission
                    )
                }
                duplicateGroups = emptyList()
            } catch (_: Exception) {
                statusMessage = "Não foi possível ler todos os arquivos. Confira as permissões e a pasta vinculada."
            } finally {
                loading = false
            }
        }
    }

    private fun scanDuplicates() {
        if (duplicateScanRunning) return
        duplicateScanRunning = true
        lifecycleScope.launch {
            try {
                duplicateGroups = withContext(Dispatchers.IO) {
                    FileManagerRepository.findExactDuplicates(this@FileManagerActivity, managerFiles)
                }
                statusMessage = if (duplicateGroups.isEmpty()) {
                    "A análise terminou. Nenhum arquivo idêntico foi encontrado."
                } else {
                    "A análise encontrou ${duplicateGroups.size} grupo(s) de arquivos com conteúdo exatamente idêntico. Revise cada grupo antes de excluir."
                }
            } catch (_: Exception) {
                statusMessage = "Não foi possível concluir a análise de duplicados."
            } finally {
                duplicateScanRunning = false
            }
        }
    }

    private fun hasWriteAccessToSelectedTree(): Boolean {
        val tree = selectedTreeUri ?: return false
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == tree && permission.isWritePermission
        }
    }

    private fun deleteFiles(files: List<ManagedMediaFile>) {
        if (files.isEmpty()) return
        val safFiles = files.filter { it.isSaf }
        val privateFiles = files.filter { it.isPrivateDownload }
        val mediaStoreUris = files.filterNot { it.isSaf || it.isPrivateDownload }.map { it.uri }

        if (safFiles.isNotEmpty() && !hasWriteAccessToSelectedTree()) {
            statusMessage = "Para excluir arquivos da pasta escolhida, é necessário vinculá-la novamente com permissão de edição. Use “Conectar pasta” e escolha a mesma pasta."
            return
        }

        if (mediaStoreUris.isEmpty()) {
            lifecycleScope.launch {
                val deletedPrivate = withContext(Dispatchers.IO) {
                    privateFiles.filter { FileManagerRepository.deletePrivateDownload(it.uri) }.map { it.uri }
                }
                val deletedSaf = withContext(Dispatchers.IO) {
                    safFiles.filter { FileManagerRepository.deleteSafDocument(this@FileManagerActivity, it.uri) }
                        .map { it.uri }
                }
                cleanupLocalReferences(deletedPrivate + deletedSaf)
                statusMessage = "${deletedPrivate.size} download(s) privado(s) e ${deletedSaf.size} arquivo(s) da pasta selecionada removidos."
                scanFiles()
            }
        } else {
            // Ask Android to authorize MediaStore deletion before removing any SAF originals.
            requestMediaStoreDelete(mediaStoreUris) {
                lifecycleScope.launch {
                    val deletedPrivate = withContext(Dispatchers.IO) {
                        privateFiles.filter { FileManagerRepository.deletePrivateDownload(it.uri) }.map { it.uri }
                    }
                    val deletedSaf = withContext(Dispatchers.IO) {
                        safFiles.filter { FileManagerRepository.deleteSafDocument(this@FileManagerActivity, it.uri) }
                            .map { it.uri }
                    }
                    cleanupLocalReferences(deletedPrivate + deletedSaf + mediaStoreUris)
                    statusMessage = "Exclusão concluída. ${deletedPrivate.size} download(s) privado(s) e ${deletedSaf.size} arquivo(s) SAF removidos."
                    scanFiles()
                }
            }
        }
    }

    private fun moveFilesTo(destination: Uri, files: List<ManagedMediaFile>) {
        if (files.isEmpty()) return
        if (files.any { it.isSaf } && !hasWriteAccessToSelectedTree()) {
            statusMessage = "A pasta de origem foi vinculada somente para leitura. Use “Conectar pasta” e selecione-a novamente para conceder edição antes de mover."
            return
        }
        lifecycleScope.launch {
            val copyResult = withContext(Dispatchers.IO) {
                val copied = mutableListOf<ManagedMediaFile>()
                val failed = mutableListOf<ManagedMediaFile>()
                files.forEach { file ->
                    if (FileManagerRepository.copyToTree(this@FileManagerActivity, file, destination)) {
                        copied += file
                    } else {
                        failed += file
                    }
                }
                MoveCopyResult(copied, failed)
            }

            val safSources = copyResult.copied.filter { it.isSaf }
            val privateSources = copyResult.copied.filter { it.isPrivateDownload }
            val mediaSources = copyResult.copied.filterNot { it.isSaf || it.isPrivateDownload }.map { it.uri }

            suspend fun removeSafSources(): List<Uri> = withContext(Dispatchers.IO) {
                safSources.filter { FileManagerRepository.deleteSafDocument(this@FileManagerActivity, it.uri) }
                    .map { it.uri }
            }
            suspend fun removePrivateSources(): List<Uri> = withContext(Dispatchers.IO) {
                privateSources.filter { FileManagerRepository.deletePrivateDownload(it.uri) }
                    .map { it.uri }
            }

            val summary = "Copiados: ${copyResult.copied.size}; falharam: ${copyResult.failed.size}."
            if (mediaSources.isEmpty()) {
                val deletedPrivate = removePrivateSources()
                val deletedSaf = removeSafSources()
                cleanupLocalReferences(deletedPrivate + deletedSaf)
                statusMessage = "$summary Downloads privados removidos: ${deletedPrivate.size}; origens SAF removidas: ${deletedSaf.size}."
                scanFiles()
            } else {
                requestMediaStoreDelete(mediaSources, isMove = true) {
                    lifecycleScope.launch {
                        val deletedPrivate = removePrivateSources()
                        val deletedSaf = removeSafSources()
                        cleanupLocalReferences(deletedPrivate + deletedSaf + mediaSources)
                        statusMessage = "$summary Downloads privados removidos: ${deletedPrivate.size}; origens SAF removidas: ${deletedSaf.size}. Os originais da biblioteca Android foram excluídos após sua autorização."
                        scanFiles()
                    }
                }
            }
        }
    }

    private fun requestMediaStoreDelete(uris: List<Uri>, isMove: Boolean = false, onSuccess: () -> Unit) {
        if (uris.isEmpty()) {
            onSuccess()
            return
        }
        pendingMediaDeleteIsMove = isMove
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createDeleteRequest(contentResolver, uris)
                mediaDeleteCallback = onSuccess
                systemDeleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                statusMessage = if (isMove) {
                    "A cópia foi criada, mas o Android não abriu a autorização para apagar a origem. Os dois arquivos foram mantidos."
                } else {
                    "O Android não conseguiu abrir a autorização de exclusão. Os arquivos originais foram mantidos."
                }
                pendingMediaDeleteIsMove = false
                scanFiles()
            }
        } else {
            legacyDeleteQueue = uris
            legacyDeleteIndex = 0
            legacyDeleteCallback = onSuccess
            legacyDeleteIsMove = isMove
            deleteNextLegacyMediaStoreItem()
        }
    }

    private fun cleanupLocalReferences(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val removed = uris.map { it.toString() }.toSet()
        val preferences = getSharedPreferences("gaplin_music", MODE_PRIVATE)
        val favorites = preferences.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        favorites.removeAll(removed)

        val rawPlaylists = preferences.getString("playlists", "[]") ?: "[]"
        val updatedPlaylists = runCatching {
            val current = JSONArray(rawPlaylists)
            val updated = JSONArray()
            for (index in 0 until current.length()) {
                val playlist = current.optJSONObject(index) ?: continue
                val tracks = playlist.optJSONArray("tracks") ?: JSONArray()
                val keptTracks = JSONArray()
                for (trackIndex in 0 until tracks.length()) {
                    val uri = tracks.optString(trackIndex)
                    if (uri.isNotBlank() && uri !in removed) keptTracks.put(uri)
                }
                playlist.put("tracks", keptTracks)
                updated.put(playlist)
            }
            updated.toString()
        }.getOrDefault(rawPlaylists)

        preferences.edit()
            .putStringSet("favorites", favorites)
            .putString("playlists", updatedPlaylists)
            .apply()
    }

    private fun deleteNextLegacyMediaStoreItem() {
        if (legacyDeleteIndex >= legacyDeleteQueue.size) {
            legacyDeleteQueue = emptyList()
            legacyDeleteIndex = 0
            val callback = legacyDeleteCallback
            legacyDeleteCallback = null
            legacyDeleteIsMove = false
            callback?.invoke()
            return
        }
        val uri = legacyDeleteQueue[legacyDeleteIndex]
        try {
            contentResolver.delete(uri, null, null)
            legacyDeleteIndex++
            deleteNextLegacyMediaStoreItem()
        } catch (error: RecoverableSecurityException) {
            systemDeleteLauncher.launch(
                IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build()
            )
        } catch (_: SecurityException) {
            val alreadyDeleted = legacyDeleteQueue.take(legacyDeleteIndex)
            val deletedCount = legacyDeleteIndex
            cleanupLocalReferences(alreadyDeleted)
            legacyDeleteQueue = emptyList()
            legacyDeleteIndex = 0
            legacyDeleteCallback = null
            pendingMediaDeleteIsMove = false
            statusMessage = if (deletedCount > 0) {
                "$deletedCount arquivo(s) foram excluídos antes da falha. Os demais foram mantidos."
            } else if (legacyDeleteIsMove) {
                "A cópia no destino foi mantida, mas o Android não autorizou excluir os originais."
            } else {
                "O Android não autorizou apagar os arquivos selecionados."
            }
            legacyDeleteIsMove = false
            scanFiles()
        } catch (_: Exception) {
            legacyDeleteIndex++
            deleteNextLegacyMediaStoreItem()
        }
    }
}
