package com.gaplin.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Keeps queued downloads alive while the user navigates between Compose tabs.
 * The scope is process-local; it does not promise survival after the app process is killed.
 */
internal object DownloadExecutionRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val mutex = Mutex()
    val active = MutableStateFlow(false)
    val completionVersion = MutableStateFlow(0)

    private val knownRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun reserveRequest(key: String): Boolean = knownRequests.add(key)

    fun releaseRequest(key: String) {
        knownRequests.remove(key)
    }
}
