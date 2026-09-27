package com.moting.linkgo.util

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class ShareOpenResult(
    val requestId: String,
    val success: Boolean,
    val data: Any? = null
)

object ShareOpenResultManager {
    private val continuations = ConcurrentHashMap<String, CancellableContinuation<ShareOpenResult>>()

    suspend fun awaitResult(requestId: String): ShareOpenResult = suspendCancellableCoroutine { cont ->
        continuations[requestId] = cont
        cont.invokeOnCancellation {
            continuations.remove(requestId)
        }
    }

    fun completeResult(requestId: String, success: Boolean, data: Any? = null) {
        val cont = continuations.remove(requestId)
        if (cont != null && cont.isActive) {
            cont.resume(ShareOpenResult(requestId, success, data))
        }
    }
}
