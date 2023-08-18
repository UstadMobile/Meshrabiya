package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest
import android.util.Log
import androidx.annotation.RequiresApi
import com.ustadmobile.meshrabiya.ext.prettyPrint
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CompletableDeferred


@Suppress("unused") //Reserved for future usage
suspend fun WifiP2pManager.addServiceRequestAsync(
    channel: Channel?,
    request: WifiP2pServiceRequest,
    logPrefix: String? = null
) {
    val actionListener = WifiP2pActionListenerAdapter(
        onFailLogMessage = "${logPrefix ?: ""} failed to add service request"
    )

    addServiceRequest(channel, request, actionListener)
    actionListener.await()
}

@Suppress("unused") //Reserved for future usage
suspend fun WifiP2pManager.discoverServicesAsync(
    channel: Channel?,
    logPrefix: String?,
) {
    val actionListener = WifiP2pActionListenerAdapter(
        onFailLogMessage = "${logPrefix ?: ""} failed to start service discovery"
    )

    discoverServices(channel, actionListener)
    actionListener.await()
}

@Suppress("unused") //Reserved for future usage
suspend fun WifiP2pManager.connectAsync(
    channel: Channel?,
    p2pConfig: WifiP2pConfig,
    logPrefix: String? = null
){
    val actionListener = WifiP2pActionListenerAdapter(
        onFailLogMessage = "${logPrefix ?: ""} : failed to request connect"
    )

    connect(channel, p2pConfig, actionListener)
    actionListener.await()
}

@Suppress("unused") //Reserved for future usage
suspend fun WifiP2pManager.removeServiceRequestAsync(
    channel: Channel?,
    request: WifiP2pServiceRequest,
    logPrefix: String?
) {
    val actionListener = WifiP2pActionListenerAdapter(
        onFailLogMessage ="${logPrefix ?: ""} failed to remove service request"
    )
    removeServiceRequest(channel, request, actionListener)
    actionListener.await()
}

@Suppress("unused") //Reserved for future usage
suspend fun WifiP2pManager.addLocalServiceAsync(
    channel: Channel?,
    serviceInfo: WifiP2pServiceInfo,
    logPrefix: String?
) {
    val actionListener = WifiP2pActionListenerAdapter(
        onFailLogMessage = "${logPrefix ?: ""} failed to add local service"
    )
    addLocalService(channel, serviceInfo, actionListener)
    actionListener.await()
}

@Suppress("unused") //Reserved for future usage
suspend fun WifiP2pManager.removeLocalServiceAsync(
    channel: Channel?,
    serviceInfo: WifiP2pServiceInfo,
    logPrefix: String?
) {
    val actionListener = WifiP2pActionListenerAdapter(
        onFailLogMessage = "${logPrefix ?: ""} failed to remove local service"
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
        onFailLogMessage = "${logPrefix ?: ""} failed to request group creation w/config ${config.prettyPrint()}",
        logger = logger,
        onSuccessLogMessage = "${logPrefix ?: ""} createGroup: onSuccess w/config ${config.prettyPrint()}",
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
        onFailLogMessage = "${logPrefix ?: ""} failed to request group creation",
        logger = logger,
        onSuccessLogMessage = "${logPrefix ?: ""} createGroup: onSuccess",
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


/**
 * Suspend function wrapper for removeGroup
 */
suspend fun WifiP2pManager.removeGroupAsync(
    channel: Channel?,
    logger: MNetLogger? = null,
    logPrefix: String? = null,
) {
    val actionListener = WifiP2pActionListenerAdapter(
        onSuccessLogMessage = "${logPrefix ?: ""} WifiP2pManager: removeGroup: succeeded",
        onFailLogMessage = "${logPrefix ?: ""} WifiP2pManager: removeGroupAsync failed",
        logger = logger,
    )
    removeGroup(channel, actionListener)
    actionListener.await()
}


fun WifiP2pManager.setWifiP2pChannelsUnhidden(
    channel: Channel,
    listeningChannel: Int,
    operatingChannel: Int,
    actionListener: ActionListener
) {
    val method = this::class.java.getMethod("setWifiP2pChannels",
        Channel::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ActionListener::class.java)
    method.invoke(this, channel, listeningChannel, operatingChannel, actionListener)
}

@Suppress("unused") //Reserved for future use: can be used to try and use 5Ghz on pre-SDK30 devices
suspend fun WifiP2pManager.setWifiP2pChannelsAsync(
    channel: Channel,
    listeningChannel: Int,
    operatingChannel: Int,
    logger: MNetLogger?,
) {
    val actionListener = WifiP2pActionListenerAdapter(onFailLogMessage = "Failed to set Wifip2p channels")
    logger?.invoke(Log.DEBUG, "WifiP2pManager.setWifip2pchannels " +
            "listening=$listeningChannel, operating=$operatingChannel : start attempt")
    try {
        setWifiP2pChannelsUnhidden(channel, listeningChannel, operatingChannel, actionListener)
        actionListener.await()
        logger?.invoke(Log.DEBUG, "WifiP2pManager.setWifip2pchannels " +
                "listening=$listeningChannel, operating=$operatingChannel :Success")
    }catch(e: Exception) {
        logger?.invoke(Log.ERROR, "WifiP2pManager.setWifip2pchannels " +
                "listening=$listeningChannel, operating=$operatingChannel : FAILED", e)
        throw e
    }
}

