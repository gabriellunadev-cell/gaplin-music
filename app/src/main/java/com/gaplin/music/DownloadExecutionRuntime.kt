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

    private enum class RequestState { ACTIVE, COMPLETED }
    private val requests = java.util.concurrent.ConcurrentHashMap<String, RequestState>()

    fun reserveRequest(key: String): Boolean =
        requests.putIfAbsent(key, RequestState.ACTIVE) == null

    fun isCompletedRequest(key: String): Boolean =
        requests[key] == RequestState.COMPLETED

    fun completeRequest(key: String) {
        requests[key] = RequestState.COMPLETED
    }

    fun releaseRequest(key: String) {
        requests.remove(key, RequestState.ACTIVE)
    }
}
