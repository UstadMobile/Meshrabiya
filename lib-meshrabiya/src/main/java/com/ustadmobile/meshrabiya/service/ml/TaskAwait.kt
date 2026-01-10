package com.ustadmobile.meshrabiya.service.ml

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lightweight, dependency-free suspend extension to await a Google Task<T> inside coroutines.
 * We provide this locally so the project doesn't need the separate kotlinx-coroutines-play-services
 * dependency.
 */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (!cont.isCompleted) cont.resume(result)
    }
    addOnFailureListener { exc ->
        if (!cont.isCompleted) cont.resumeWithException(exc)
    }
    addOnCanceledListener {
        if (!cont.isCompleted) cont.resumeWithException(CancellationException("Task was cancelled"))
    }

    cont.invokeOnCancellation {
        // Best-effort: cancel listeners if supported; nothing to do here in GMS Task API
    }
}
