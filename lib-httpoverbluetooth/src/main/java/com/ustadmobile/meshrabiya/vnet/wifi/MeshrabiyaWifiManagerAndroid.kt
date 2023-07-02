package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.ustadmobile.meshrabiya.ext.addOrLookupNetwork
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.toPrettyString
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.WifiRole
import com.ustadmobile.meshrabiya.vnet.wifi.state.LocalOnlyHotspotState
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiDirectGroupState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 *
 */
class MeshrabiyaWifiManagerAndroid(
    private val appContext: Context,
    private val logger: com.ustadmobile.meshrabiya.MNetLogger,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val ioExecutor: ExecutorService,
    private val onNewWifiConnectionListener: OnNewWifiConnectionListener = OnNewWifiConnectionListener { },
) : Closeable, MeshrabiyaWifiManager, WifiP2pManager.ChannelListener {

    private val logPrefix = "[LocalHotspotManagerAndroid: ${localNodeAddr.addressToDotNotation()}] "


    data class NetworkAndDhcpServer(
        val network: Network,
        val dhcpServer: InetAddress
    )


    fun interface OnNewWifiConnectionListener {
        fun onNewWifiConnection(connectEvent: WifiConnectEvent)
    }


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
                            wifiDirectGroupState = WifiDirectGroupState(
                                hotspotStatus = HotspotStatus.STOPPED
                            ),
                        )
                    }

                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val extraGroup: WifiP2pGroup? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    logger(Log.DEBUG, "wifi p2p connection changed action: group=${extraGroup?.toPrettyString()}", null)
                    if(extraGroup != null)
                        onNewWifiP2pGroupInfoReceived(extraGroup)
                }
            }
        }
    }

    /**
     * This function can be called by the WIFI_P2P_CONNECTION_CHANGED_ACTION or groupInfoListener.
     * Sometimes the info only comes with the callback, sometimes it only comes in the broadcast.
     */
    private fun onNewWifiP2pGroupInfoReceived(group: WifiP2pGroup) {
        val ssid = group.networkName
        val passphrase = group.passphrase

        _state.update { prev ->
            val hotspotConfig = if(ssid != null && passphrase != null) {
                HotspotConfig(
                    ssid = ssid,
                    passphrase = passphrase,
                    port = router.localDatagramPort,
                    hotspotType = HotspotType.WIFIDIRECT_GROUP
                )
            }else {
                null
            }

            prev.copy(
                wifiRole = if(hotspotConfig != null) {
                    WifiRole.WIFI_DIRECT_GROUP_OWNER
                } else {
                    prev.wifiRole
                },
                wifiDirectGroupState = WifiDirectGroupState(
                    hotspotStatus = HotspotStatus.STARTED,
                    config = hotspotConfig,
                ),
                errorCode = 0,
            )
        }
    }

    private val wifiP2pGroupInfoListener = WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
        logger(Log.DEBUG, "P2P Group Info Available: ${group?.toPrettyString()} ", null)

        /*
         * Group = null does not necessarily mean that the group is gone.
         */
        if(group == null)
            return@GroupInfoListener

        onNewWifiP2pGroupInfoReceived(group)
    }

    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val localOnlyHotspotCallback = object: WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStarted", null)
            localOnlyHotspotReservation = reservation
            _state.takeIf { reservation != null }?.update {prev ->
                prev.copy(
                    wifiRole = WifiRole.LOCAL_ONLY_HOTSPOT,
                    localOnlyHotspotState = LocalOnlyHotspotState(
                        status = HotspotStatus.STARTED,
                        config = reservation?.toLocalHotspotConfig(router.localDatagramPort),
                    ),
                )
            }
        }

        override fun onStopped() {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStopped", null)
            localOnlyHotspotReservation = null
            _state.update { prev ->
                val wasLocalOnlyHotspot = prev.wifiRole == WifiRole.LOCAL_ONLY_HOTSPOT
                prev.copy(
                    wifiRole = if(wasLocalOnlyHotspot) {
                        WifiRole.NONE
                    } else {
                        prev.wifiRole
                    },
                    localOnlyHotspotState = LocalOnlyHotspotState(
                        status = HotspotStatus.STOPPED,
                    ),
                )
            }
        }

        override fun onFailed(reason: Int) {
            logger(Log.ERROR, "$logPrefix localOnlyhotspotcallback : onFailed: " +
                LocalOnlyHotspotState.errorCodeToString(reason), null
            )

            _state.update { prev ->
                prev.copy(
                    localOnlyHotspotState = LocalOnlyHotspotState(
                        status = HotspotStatus.STOPPED,
                        errorCode = reason,
                    )
                )
            }
        }
    }


    private val connectivityManager: ConnectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)

    val wifiP2pManager: WifiP2pManager? by lazy {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    }

    var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(MeshrabiyaWifiState())

    override val state: Flow<MeshrabiyaWifiState> = _state.asStateFlow()

    private val requestMutex = Mutex()

    /**
     * When this device is connected as a station, we will create a new datagramsocket that is
     * bound to the Android Network object. This helps prevent older versions of Android from
     * disconnecting when it realizes the connection has no Internet (e.g. Android will see
     * activity on the network).
     */
    private val stationNetworkBoundDatagramSocket = AtomicReference<VirtualNodeDatagramSocket?>()

    private val closed = AtomicBoolean(false)

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
                wifiRole = if(prev.wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER){
                    WifiRole.NONE
                }else {
                    prev.wifiRole
                },
                wifiDirectGroupState = WifiDirectGroupState(
                    hotspotStatus = HotspotStatus.STOPPED,
                )
            )
        }
    }

    override val is5GhzSupported: Boolean
        get() = wifiManager.is5GHzBandSupported


    private suspend fun startWifiDirectGroup(): Boolean {
        logger(Log.DEBUG, "$logPrefix startWifiDirectGroup", null)
        stopLocalOnlyHotspot()

        if(channel == null) {
            channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), this)
        }

        logger(Log.DEBUG, "$logPrefix startWifiDirectGroup: Requesting WifiP2PGroup", null)
        wifiP2pManager?.createGroup(channel, object: WifiP2pManager.ActionListener {
            override fun onSuccess() {
                logger(Log.DEBUG, "$logPrefix startWifiDirectGroup: WifiP2PGroup:onSuccess", null)
                wifiP2pManager?.requestGroupInfo(channel, wifiP2pGroupInfoListener)
            }

            override fun onFailure(reason: Int) {
                logger(
                    Log.ERROR,
                    "$logPrefix: startWifiDirectGroup: ONFailure ${(WifiDirectError(reason)) }",
                    null
                )

                _state.update { prev ->
                    prev.copy(
                        wifiDirectGroupState = WifiDirectGroupState(
                            hotspotStatus = HotspotStatus.STOPPED,
                            error = reason,
                        ),
                    )
                }
            }
        })

        return withTimeoutOrNull(HOTSPOT_TIMEOUT) {
            state.filter { it.wifiDirectGroupState.hotspotStatus.isSettled() }.first()
        }?.wifiDirectGroupState?.hotspotStatus == HotspotStatus.STARTED
    }

    suspend fun stopWifiDirectGroup(): Boolean {
        logger(Log.DEBUG, "$logPrefix stopWifiDirectGroup", null)
        if(
            //Use atomic update on state flow. If group was started, then stop it now.
            _state.getAndUpdate { prev ->
                if(prev.wifiDirectGroupState.hotspotStatus == HotspotStatus.STARTED) {
                    prev.copy(
                        wifiDirectGroupState = prev.wifiDirectGroupState.copy(
                            hotspotStatus = HotspotStatus.STOPPING
                        )
                    )
                }else {
                    prev
                }
            }.wifiDirectGroupState.hotspotStatus == HotspotStatus.STARTED
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
                                    wifiRole = if(prev.wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER) {
                                        WifiRole.NONE
                                    }else {
                                        prev.wifiRole
                                    },
                                    wifiDirectGroupState = WifiDirectGroupState(
                                        hotspotStatus = HotspotStatus.STOPPED
                                    )
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

        return withTimeoutOrNull(HOTSPOT_TIMEOUT) {
            _state.filter { it.wifiDirectGroupState.hotspotStatus.isSettled() }.first()
        }?.wifiDirectGroupState?.hotspotStatus == HotspotStatus.STOPPED
    }

    private suspend fun startLocalOnlyHotspot() {
        logger(Log.DEBUG, "$logPrefix startLocalOnlyHotspot", null)
        stopWifiDirectGroup()
        logger(Log.DEBUG, "$logPrefix startLocalOnlyHotspot: requesting WifiManager to " +
                "start local only hotspot", null)
        wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
    }

    suspend fun stopLocalOnlyHotspot(): Boolean {
        logger(Log.DEBUG, "$logPrefix stopLocalOnlyHotspot", null)
        if(
            _state.getAndUpdate { prev ->
                if(prev.localOnlyHotspotState.status == HotspotStatus.STARTED) {
                    prev.copy(
                        localOnlyHotspotState = prev.localOnlyHotspotState.copy(
                            status = HotspotStatus.STOPPING
                        )
                    )
                }else {
                    prev
                }
            }.localOnlyHotspotState.status == HotspotStatus.STARTED
        ) {
            logger(Log.DEBUG, "$logPrefix stopLocalOnlyHotspot: closing reservation", null)
            localOnlyHotspotReservation?.close()
        }

        return withTimeoutOrNull(HOTSPOT_TIMEOUT) {
            state.filter { it.localOnlyHotspotState.status.isSettled() }.first()
        }?.localOnlyHotspotState?.status == HotspotStatus.STOPPED
    }

    override suspend fun requestHotspot(
        requestMessageId: Int,
        request: LocalHotspotRequest
    ): LocalHotspotResponse {
        if(closed.get())
            throw IllegalStateException("$logPrefix is closed!")

        logger(Log.DEBUG, "$logPrefix requestHotspot requestId=$requestMessageId", null)
        withContext(Dispatchers.Main) {
            val prevState = _state.getAndUpdate { prev ->
                when(prev.hotspotTypeToCreate) {
                    HotspotType.LOCALONLY_HOTSPOT ->  prev.copy(
                        localOnlyHotspotState = prev.localOnlyHotspotState.copy(
                            status = HotspotStatus.STARTING
                        )
                    )

                    HotspotType.WIFIDIRECT_GROUP -> prev.copy(
                        wifiDirectGroupState = prev.wifiDirectGroupState.copy(
                            hotspotStatus = HotspotStatus.STARTING
                        )
                    )

                    else -> prev
                }
            }

            when(prevState.hotspotTypeToCreate) {
                HotspotType.LOCALONLY_HOTSPOT -> {
                    startLocalOnlyHotspot()
                }
                HotspotType.WIFIDIRECT_GROUP -> {
                    startWifiDirectGroup()
                }
                else -> {
                    //Do nothing
                }
            }
        }

        val configResult = _state.filter {
            (it.wifiDirectGroupState.hotspotStatus == HotspotStatus.STARTED ||
                    it.localOnlyHotspotState.status == HotspotStatus.STARTED) ||
                    it.errorCode != 0
        }.first()

        return LocalHotspotResponse(
            responseToMessageId = requestMessageId,
            errorCode = configResult.errorCode,
            config = configResult.config,
            redirectAddr = 0
        )
    }


    /**
     * Connect to the given hotspot as a station.
     */
    @Suppress("DEPRECATION") //Must use deprecated classes to support pre-SDK29
    private suspend fun connectToHotspot(ssid: String, passphrase: String): NetworkAndDhcpServer? {
        logger(Log.INFO, "$logPrefix Connecting to hotspot: ssid=$ssid passphrase=$passphrase", null)

        //Local Hotspot (on most devices) cannot run at the same time as being connected as a station
        stopLocalOnlyHotspot()

        val completable = CompletableDeferred<NetworkAndDhcpServer?>()
        val networkCallback = object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val linkProperties = connectivityManager.getLinkProperties(network)
                val dhcpServer = if(Build.VERSION.SDK_INT >= 30) {
                    linkProperties?.dhcpServerAddress
                }else {
                    wifiManager.dhcpInfo?.serverAddress?.let {
                        //Strangely - seems like these are Little Endian
                        InetAddress.getByAddress(
                            ByteBuffer.wrap(ByteArray(4))
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(it)
                            .array()
                        )
                    }
                }

                logger(Log.DEBUG, "$logPrefix connectToHotspot: connection available. DHCP server=$dhcpServer", null)

                if(dhcpServer != null) {
                    completable.complete(
                        NetworkAndDhcpServer(
                            network,
                            dhcpServer
                        )
                    )
                }else {
                    logger(Log.DEBUG, "$logPrefix connectToHotspot: ERROR: could not find DHCP server", null)
                    completable.complete(null)
                }
            }


            override fun onUnavailable() {
                logger(Log.DEBUG, "$logPrefix connectToHotspot: connection unavailable", null)
                completable.complete(null)
                super.onUnavailable()
            }
        }

        if(Build.VERSION.SDK_INT >= 29) {
            //Use the suggestion API as per https://developer.android.com/guide/topics/connectivity/wifi-bootstrap
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .setIsHiddenSsid(true) //not really hidden, but may have just been created, so will not be in scan results yet
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            logger(Log.DEBUG, "$logPrefix connectToHotspot: Requesting network for $ssid / $passphrase", null)
            connectivityManager.requestNetwork(request, networkCallback)
        }else {
            //use pre-Android 10 WifiManager API
            val wifiConfig = WifiConfiguration().apply {
                SSID =  "\"$ssid\""
                preSharedKey = "\"$passphrase\""

                /* Setting hiddenSSID = true is necessary, even though the network we are connecting
                 * to is not hidden...
                 * Android won't connect to an SSID if it thinks the SSID is not there. The SSID
                 * might have created only a few ms ago by the other peer, and therefor won't be
                 * in the scan list. Setting hiddenSSID to true will ensure that Android attempts to
                 * connect whether or not the network is in currently known scan results.
                 */
                hiddenSSID = true
            }
            val configNetworkId = wifiManager.addOrLookupNetwork(wifiConfig, logger)
            val currentlyConnectedNetworkId = wifiManager.connectionInfo.networkId
            logger(Log.DEBUG, "$logPrefix connectToHotspot: Currently connected to networkId: $currentlyConnectedNetworkId", null)

            if(currentlyConnectedNetworkId == configNetworkId) {
                logger(Log.DEBUG, "$logPrefix connectToHotspot: Already connected to target networkid", null)
            }else {
                //If currently connected to another network, we need to disconnect.
                wifiManager.takeIf { currentlyConnectedNetworkId != -1 }?.disconnect()
                wifiManager.enableNetwork(configNetworkId, true)
            }

            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()


            logger(Log.DEBUG, "$logPrefix connectToHotspot: requesting network for $ssid", null)
            connectivityManager.requestNetwork(networkRequest, networkCallback)
        }

        return completable.await()
    }

    override suspend fun connectToHotspot(config: HotspotConfig) {
        val networkAndServerAddr = connectToHotspot(
            config.ssid, config.passphrase
        )

        if(networkAndServerAddr != null) {
            withContext(Dispatchers.IO) {
                val networkBoundDatagramSocket = VirtualNodeDatagramSocket(
                    port = 0,
                    localNodeVirtualAddress = localNodeAddr,
                    ioExecutorService = ioExecutor,
                    router = router,
                    onMmcpHelloReceivedListener = {
                        //Do nothing - this will never receive an incoming hello.
                    },
                    logger = logger,
                    name = "network bound to ${config.ssid}"
                )

                val previousSocket = stationNetworkBoundDatagramSocket.getAndUpdate {
                    networkBoundDatagramSocket
                }

                previousSocket?.close()

                //Binding something to the network helps to avoid older versions of Android
                //deciding to disconnect from this network.
                networkAndServerAddr.network.bindSocket(networkBoundDatagramSocket)
                logger(Log.INFO, "$logPrefix : addWifiConnection:Created network bound port on ${networkBoundDatagramSocket.localPort}", null)
                _state.update { prev ->
                    prev.copy(
                        wifiRole = if(config.hotspotType == HotspotType.LOCALONLY_HOTSPOT) {
                            WifiRole.WIFI_DIRECT_GROUP_OWNER
                        }else {
                            WifiRole.CLIENT
                        }
                    )
                }

                logger(
                    Log.INFO, "$logPrefix : addWifiConnectionConnect:Sending " +
                            "hello to ${networkAndServerAddr.dhcpServer}:${config.port} " +
                            "from local:${networkBoundDatagramSocket.localPort}", null
                )

                //Once connected,
                onNewWifiConnectionListener.onNewWifiConnection(WifiConnectEvent(
                    neighborPort = config.port,
                    neighborInetAddress = networkAndServerAddr.dhcpServer,
                    socket = networkBoundDatagramSocket,
                ))

            }
        }else {
            logger(Log.ERROR, "$logPrefix : addWifiConnectionConnect: to hotspot: returned null network", null)
        }
    }

    override fun close() {
        if(!closed.getAndSet(true)) {
            //Channel close is only allowed on SDK27+
            if(Build.VERSION.SDK_INT >= 27) {
                channel?.close()
            }
            channel = null

            appContext.unregisterReceiver(wifiDirectBroadcastReceiver)
            localOnlyHotspotReservation?.close()
        }
    }

    companion object {

        const val HOTSPOT_TIMEOUT = 10000L

    }

}