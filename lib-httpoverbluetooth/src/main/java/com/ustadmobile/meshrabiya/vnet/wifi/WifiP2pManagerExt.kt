package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest
import androidx.annotation.RequiresApi
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CompletableDeferred


suspend fun WifiP2pManager.addServiceRequestAsync(
    channel: Channel?,
    request: WifiP2pServiceRequest,
    logPrefix: String? = null
) {
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage = "${logPrefix ?: ""} failed to add service request"
    )

    addServiceRequest(channel, request, actionListener)
    actionListener.await()
}

suspend fun WifiP2pManager.discoverServicesAsync(
    channel: Channel?,
    logPrefix: String?,
) {
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage = "${logPrefix ?: ""} failed to start service discovery"
    )

    discoverServices(channel, actionListener)
    actionListener.await()
}

suspend fun WifiP2pManager.connectAsync(
    channel: Channel?,
    p2pConfig: WifiP2pConfig,
    logPrefix: String? = null
){
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage = "${logPrefix ?: ""} : failed to request connect"
    )

    connect(channel, p2pConfig, actionListener)
    actionListener.await()
}

suspend fun WifiP2pManager.removeServiceRequestAsync(
    channel: Channel?,
    request: WifiP2pServiceRequest,
    logPrefix: String?
) {
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage ="${logPrefix ?: ""} failed to remove service request"
    )
    removeServiceRequest(channel, request, actionListener)
    actionListener.await()
}

suspend fun WifiP2pManager.addLocalServiceAsync(
    channel: Channel?,
    serviceInfo: WifiP2pServiceInfo,
    logPrefix: String?
) {
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage = "${logPrefix ?: ""} failed to add local service"
    )
    addLocalService(channel, serviceInfo, actionListener)
    actionListener.await()
}

suspend fun WifiP2pManager.removeLocalServiceAsync(
    channel: Channel?,
    serviceInfo: WifiP2pServiceInfo,
    logPrefix: String?
) {
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage = "${logPrefix ?: ""} failed to remove local service"
    )

    removeLocalService(channel, serviceInfo, actionListener)

    actionListener.await()
}

@RequiresApi(29)
suspend fun WifiP2pManager.createGroupAsync(
    channel: Channel,
    config: WifiP2pConfig,
    logPrefix: String?,
    logger: MNetLogger?
) {
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage = "${logPrefix ?: ""} failed to request group creation w/config",
        logger = logger,
        onSuccessLog = "${logPrefix ?: ""} createGroup: onSuccess w/config",
    )

    createGroup(channel, config, actionListener)
    actionListener.await()
}

suspend fun WifiP2pManager.createGroupAsync(
    channel: Channel?,
    logPrefix: String?,
    logger: MNetLogger? = null,
) {
    val actionListener = WifiP2pActionListenerAdapter(
        failMessage = "${logPrefix ?: ""} failed to request group creation",
        logger = logger,
        onSuccessLog = "${logPrefix ?: ""} createGroup: onSuccess",
    )
    createGroup(channel, actionListener)
    actionListener.await()
}

suspend fun WifiP2pManager.requestGroupInfoAsync(
    channel: Channel?
): WifiP2pGroup? {
    val completable = CompletableDeferred<WifiP2pGroup?>()
    requestGroupInfo(channel) { group: WifiP2pGroup? ->
        completable.complete(group)
    }

    return completable.await()
}

