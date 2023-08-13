package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CompletableDeferred

class WifiP2pActionListenerAdapter(
    val onFailLogMessage: String,
    val logger: MNetLogger? = null,
    val onSuccessLogMessage: String? = null,
): WifiP2pManager.ActionListener {

    private val completable = CompletableDeferred<Boolean>()

    override fun onSuccess() {
        if(onSuccessLogMessage != null) {
            logger?.invoke(Log.DEBUG, onSuccessLogMessage, null)
        }
        completable.complete(true)
    }

    override fun onFailure(reason: Int) {
        logger?.invoke(Log.WARN, "WifiP2pActionListener: $onFailLogMessage : " +
                "reason=${WifiDirectError.errorString(reason)}")
        completable.completeExceptionally(WifiDirectException(onFailLogMessage, reason))
    }

    suspend fun await() = completable.await()
}
