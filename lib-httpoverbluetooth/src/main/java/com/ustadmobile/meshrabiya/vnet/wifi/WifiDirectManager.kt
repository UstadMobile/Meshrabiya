package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Looper
import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.encodeAsHex
import com.ustadmobile.meshrabiya.ext.toPrettyString
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiDirectState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 * Note: To discover the WifiDirect group service, the service request must use a blank .newInstance() !
 *
 * @param onBeforeGroupStart function that will be called before trying to create a wifi p2p group.
 *        This needs to be used to stop any local only hotspot.
 */
class WifiDirectManager(
    private val appContext: Context,
    private val logger: com.ustadmobile.meshrabiya.MNetLogger,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
): WifiP2pManager.ChannelListener, Closeable  {

    fun interface OnBeforeGroupStart {
        suspend fun onBeforeGroupStart()
    }

    var onBeforeGroupStart: OnBeforeGroupStart? = null

    private val closed = AtomicBoolean(false)

    private val _state = MutableStateFlow(WifiDirectState())

    val state: Flow<WifiDirectState> = _state.asStateFlow()

    private val logPrefix = "[WifiDirectManager: ${localNodeAddr.addressToDotNotation()}] "

    private val nodeScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val dnsSdTxtRecordListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomainName, txtRecordMap, wifiP2pDevice ->
        //Do nothing
    }

    private val dnsSdResponseFlow = MutableSharedFlow<DnsSdResponse>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )


    private val wifiDirectBroadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    logger(
                        Log.DEBUG, "$logPrefix p2p state changed: " +
                                "enabled=${state == WifiP2pManager.WIFI_P2P_STATE_ENABLED} ", null
                    )

                    //If Wifi has been disabled, make sure to
                    _state.takeIf { state == WifiP2pManager.WIFI_P2P_STATE_DISABLED }?.update { prev ->
                        prev.copy(
                            hotspotStatus = HotspotStatus.STOPPED,
                            error = 0,
                            config = null,
                        )
                    }

                }

                /**
                 * This will (consistently) be called if/when the group changes, but won't be called
                 * if the group already exists when the app starts.
                 */
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val extraGroup: WifiP2pGroup? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    logger(Log.DEBUG, "wifi p2p connection changed action: group=${extraGroup?.toPrettyString()}", null)
                    onNewWifiP2pGroupInfoReceived(extraGroup)
                }
            }
        }
    }


    /**
     * This function can be called by the WIFI_P2P_CONNECTION_CHANGED_ACTION or the
     * startWifiDirectGroup function if it requests group information and finds a group already exists
     */
    private fun onNewWifiP2pGroupInfoReceived(group: WifiP2pGroup?) {
        val ssid = group?.networkName
        val passphrase = group?.passphrase

        _state.update { prev ->
            val hotspotConfig = if(ssid != null && passphrase != null) {
                WifiConnectConfig(
                    nodeVirtualAddr = localNodeAddr,
                    ssid = ssid,
                    passphrase = passphrase,
                    port = router.localDatagramPort,
                    hotspotType = HotspotType.WIFIDIRECT_GROUP
                )
            }else {
                null
            }

            prev.copy(
                hotspotStatus = if(hotspotConfig != null) {
                    HotspotStatus.STARTED
                } else {
                    HotspotStatus.STOPPED
                },
                config = hotspotConfig,
            )
        }
    }


    private val dnsSdResponseListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, device ->
        logger(Log.DEBUG, "DNS SD Service Response: instance=$instanceName device=${device.deviceAddress}", null)
        dnsSdResponseFlow.tryEmit(DnsSdResponse(instanceName, registrationType, device))
    }

    private val wifiP2pGroupInfoListener = WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
        logger(Log.DEBUG, "P2P Group Info Available: ${group?.toPrettyString()} ", null)
    }


    var channel: WifiP2pManager.Channel? = null

    val wifiP2pManager: WifiP2pManager? by lazy {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    }

    init {
        logger(Log.DEBUG, "$logPrefix init", null)
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        appContext.registerReceiver(wifiDirectBroadcastReceiver, intentFilter)
    }


    //WifiDirect Channel Listener
    override fun onChannelDisconnected() {
        logger(Log.DEBUG, "$logPrefix onChannelDisconnected", null)
        channel = null

        _state.update { prev ->
            prev.copy(
                hotspotStatus = HotspotStatus.STOPPED,
                error = 0,
            )
        }
    }


    private fun makeWifiP2pServiceInfo(addr: Int): WifiP2pDnsSdServiceInfo {
        return WifiP2pDnsSdServiceInfo.newInstance(
            addr.encodeAsHex(), MeshrabiyaWifiManagerAndroid.WIFI_DIRECT_SERVICE_TYPE, emptyMap()
        )
    }


    private fun initWifiDirectChannel(){
        if(channel == null) {
            channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), this)
            wifiP2pManager?.setDnsSdResponseListeners(channel, dnsSdResponseListener, dnsSdTxtRecordListener)
            logger(Log.DEBUG, "$logPrefix WifiP2pChannel initialized", null)
        }
    }


    private suspend fun addWifiDirectService() {
        val servInfo = makeWifiP2pServiceInfo(localNodeAddr)

        logger(Log.DEBUG, "$logPrefix addWifiDirectService instance=${localNodeAddr.encodeAsHex()}", null)
        val completable = CompletableDeferred<Boolean>()

        wifiP2pManager?.addLocalService(channel, servInfo, object: WifiP2pManager.ActionListener {
            override fun onSuccess() {
                logger(Log.DEBUG, "$logPrefix addWifiDirectService: success", null)
                completable.complete(true)
            }

            override fun onFailure(reason: Int) {
                logger(Log.ERROR, "$logPrefix addWifiDirectService: failed ${WifiDirectError(reason)}", null)
                completable.completeExceptionally(WifiDirectException("Failed to add service", reason))
            }
        })

        completable.await()
    }


    internal suspend fun startWifiDirectGroup(): Boolean {
        logger(Log.DEBUG, "$logPrefix startWifiDirectGroup", null)
        onBeforeGroupStart?.onBeforeGroupStart()

        initWifiDirectChannel()

        //check if group already exists - e.g. the group might have started before the app
        val existingGroupInfo = wifiP2pManager?.requestGroupInfoAsync(channel)
        if(existingGroupInfo != null){
            logger(
                Log.DEBUG,
                "$logPrefix: startWifiDirectGroup: Group already exists: ${existingGroupInfo.toPrettyString()}",
            null
            )
            onNewWifiP2pGroupInfoReceived(existingGroupInfo)
        }else {
            logger(Log.DEBUG, "$logPrefix startWifiDirectGroup: Requesting WifiP2PGroup", null)
            try {
                _state.update { prev ->
                    prev.copy(hotspotStatus = HotspotStatus.STARTING)
                }

                wifiP2pManager?.createGroupAsync(
                    channel, "$logPrefix startWifiDirectGroup ", logger
                )

                // Can wait for the BroadcastReceiver WIFI_P2P_STATE_CHANGED_ACTION to pickup
                // the new group info via state flow
            }catch(e: Exception) {
                logger(Log.ERROR, "Exception creating group", e)
                _state.update { prev ->
                    prev.copy(
                        hotspotStatus = HotspotStatus.STOPPED,
                        error = (e as? WifiDirectException)?.wifiDirectFailReason ?: 0,
                        config = null,
                    )
                }
            }
        }

        val groupStartedOk = withTimeoutOrNull(MeshrabiyaWifiManagerAndroid.HOTSPOT_TIMEOUT) {
            state.filter { it.hotspotStatus == HotspotStatus.STARTED || it.error != 0 }.first()
        }?.hotspotStatus == HotspotStatus.STARTED

        if(groupStartedOk)
            addWifiDirectService()

        return groupStartedOk

    }


    suspend fun stopWifiDirectGroup(): Boolean {
        logger(Log.DEBUG, "$logPrefix stopWifiDirectGroup", null)
        if(
        //Use atomic update on state flow. If group was started, then stop it now.
            _state.getAndUpdate { prev ->
                if(prev.hotspotStatus == HotspotStatus.STARTED) {
                    prev.copy(
                        hotspotStatus = HotspotStatus.STOPPING
                    )
                }else {
                    prev
                }
            }.hotspotStatus == HotspotStatus.STARTED
        ) {
            withContext(Dispatchers.Main) {
                val channelVal = channel
                if(channelVal != null) {
                    logger(Log.DEBUG, "$logPrefix stopWifiDirectGroup - requesting group removal",null)
                    wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            logger(Log.DEBUG, "$logPrefix stopWifiDirectGroup: successful", null)
                            _state.update { prev ->
                                prev.copy(
                                    hotspotStatus = HotspotStatus.STOPPED,
                                    config = null,
                                    error = 0,
                                )
                            }

                            if(Build.VERSION.SDK_INT >= 27) {
                                logger(Log.DEBUG, "$logPrefix stopWifiDirectGroup: closing wifi p2p channel", null)
                                channelVal.close()
                            }
                            channel = null
                        }

                        override fun onFailure(reason: Int) {
                            logger(Log.ERROR, "$logPrefix stopWifiDirectGroup: onFailure: " +
                                    "${WifiDirectError(reason)}", null)
                        }
                    })
                }else {
                    logger(Log.ERROR, "INVALID STATE: wifidirect group status = STARTED but channel is null", null)
                    false
                }
            }
        }

        return withTimeoutOrNull(MeshrabiyaWifiManagerAndroid.HOTSPOT_TIMEOUT) {
            _state.filter { it.hotspotStatus.isSettled() }.first()
        }?.hotspotStatus == HotspotStatus.STOPPED
    }

    override fun close() {
        if(!closed.getAndSet(true)) {
            if(Build.VERSION.SDK_INT >= 27) {
                //Channel close is only allowed on SDK27+
                channel?.close()
            }
            channel = null

            appContext.unregisterReceiver(wifiDirectBroadcastReceiver)
        }
    }
}