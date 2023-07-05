package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.CompletableDeferred

class WifiP2pActionListenerAdapter(val failMessage: String): WifiP2pManager.ActionListener {

    private val completable = CompletableDeferred<Boolean>()

    override fun onSuccess() {
        completable.complete(true)
    }

    override fun onFailure(reason: Int) {
        completable.completeExceptionally(WifiDirectException(failMessage, reason))
    }

    suspend fun await() = completable.await()
}
