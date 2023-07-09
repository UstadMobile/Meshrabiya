package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.ustadmobile.meshrabiya.MNetLogger
import kotlinx.coroutines.CompletableDeferred

class WifiP2pActionListenerAdapter(
    val failMessage: String,
    val logger: MNetLogger? = null,
    val onSuccessLog: String? = null,
): WifiP2pManager.ActionListener {

    private val completable = CompletableDeferred<Boolean>()

    override fun onSuccess() {
        if(onSuccessLog != null) {
            logger?.invoke(Log.DEBUG, onSuccessLog, null)
        }
        completable.complete(true)
    }

    override fun onFailure(reason: Int) {
        completable.completeExceptionally(WifiDirectException(failMessage, reason))
    }

    suspend fun await() = completable.await()
}
