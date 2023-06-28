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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Close the hotspot when there are no connections depending on it
 */
class MeshrabiyaWifiManagerAndroid(
    private val appContext: Context,
    private val logger: com.ustadmobile.meshrabiya.MNetLogger,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val ioExecutor: ExecutorService,
) : Closeable, MeshrabiyaWifiManager, WifiP2pManager.ChannelListener {

    private val logPrefix = "[LocalHotspotManagerAndroid: ${localNodeAddr.addressToDotNotation()}] "


    data class NetworkAndDhcpServer(
        val network: Network,
        val dhcpServer: InetAddress
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
                            wifiDirectGroupStatus = LocalHotspotStatus.STOPPED,
                            config = null,
                        )
                    }

                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val extraGroup: WifiP2pGroup? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    logger(Log.DEBUG, "wifi p2p connection changed action: group=${extraGroup?.toPrettyString()}", null)
                }
            }
        }
    }

    private val wifiP2pGroupInfoListener = WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
        logger(Log.DEBUG, "P2P Group Info Available: ${group?.toPrettyString()} ", null)

        /*
         * Group = null does not necessarily mean that the group is gone.
         */
        if(group == null)
            return@GroupInfoListener

        val ssid = group.networkName
        val passphrase = group.passphrase

        _state.update { prev ->
            prev.copy(
                wifiRole = WifiRole.WIFI_DIRECT_GROUP_OWNER,
                wifiDirectGroupStatus = LocalHotspotStatus.STARTED,
                errorCode = 0,
                config = if(ssid != null && passphrase != null) {
                    HotspotConfig(
                        ssid = ssid,
                        passphrase = passphrase,
                        port = router.localDatagramPort,
                        hotspotType = HotspotType.WIFIDIRECT_GROUP
                    )
                }else {
                    null
                }
            )
        }
    }

    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val localOnlyHotspotCallback = object: WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
            localOnlyHotspotReservation = reservation
            _state.takeIf{ reservation != null }?.update {prev ->
                prev.copy(
                    wifiRole = WifiRole.LOCAL_ONLY_HOTSPOT,
                    localOnlyHotspotStatus = LocalHotspotStatus.STARTED,
                    config = reservation?.toLocalHotspotConfig(router.localDatagramPort),
                )
            }
        }

        override fun onStopped() {
            localOnlyHotspotReservation = null
            _state.update { prev ->
                prev.copy(
                    wifiRole = if(prev.wifiRole == WifiRole.LOCAL_ONLY_HOTSPOT) {
                        WifiRole.NONE
                    } else {
                        prev.wifiRole
                    },
                    localOnlyHotspotStatus = LocalHotspotStatus.STOPPED,
                )
            }
        }

        override fun onFailed(reason: Int) {
            _state.update { prev ->
                prev.copy(
                    localOnlyHotspotStatus = LocalHotspotStatus.STOPPED,
                    errorCode = reason,
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

    override fun onChannelDisconnected() {
        channel = null
    }

    override val is5GhzSupported: Boolean
        get() = wifiManager.is5GHzBandSupported


    suspend fun startWifiDirectGroup() {
        val completable = CompletableDeferred<Boolean>()
        if(_state.value.wifiDirectGroupStatus == LocalHotspotStatus.STOPPED) {
            _state.update { prev ->
                prev.copy(wifiDirectGroupStatus = LocalHotspotStatus.STARTING)
            }

            //TODO: stop local only hotspot if that is running

            if(channel == null) {
                channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), this)
            }

            logger(Log.DEBUG, "$logPrefix Requesting WifiP2PGroup", null)
            wifiP2pManager?.createGroup(channel, object: WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    logger(Log.DEBUG, "$logPrefix WifiP2PGroup:onSuccess", null)
                    wifiP2pManager?.requestGroupInfo(channel, wifiP2pGroupInfoListener)
                    completable.complete(true)
                }

                override fun onFailure(reason: Int) {
                    logger(
                        Log.ERROR,
                        "WifiP2pGroup: ONFailure ${WifiP2pFailure.reasonToString(reason)}",
                        null
                    )

                    _state.update { prev ->
                        prev.copy(
                            wifiDirectGroupStatus = LocalHotspotStatus.STOPPED,
                            errorCode = reason,
                            config = null
                        )
                    }
                    completable.complete(false)
                }
            })
        }
        completable.await()
    }

    fun startLocalOnlyHotspot() {
        if(_state.value.localOnlyHotspotStatus == LocalHotspotStatus.STOPPED){
            wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
        }

    }

    override suspend fun requestHotspot(
        requestMessageId: Int,
        request: LocalHotspotRequest
    ): LocalHotspotResponse {
        if(closed.get())
            throw IllegalStateException("$logPrefix is closed!")

        requestMutex.withLock {
            withContext(Dispatchers.Main) {
                if(
                    _state.value.config != null ||
                    _state.value.wifiDirectGroupStatus == LocalHotspotStatus.STARTING ||
                    _state.value.localOnlyHotspotStatus == LocalHotspotStatus.STARTING
                ) {
                    //config is ready or hotspot of some kind is already being started
                    return@withContext
                }else if(_state.value.wifiRole == WifiRole.NONE) {
                    //nothing has been started yet, so this device will become the local only hotspot
                    startLocalOnlyHotspot()
                }else if(_state.value.wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER) {
                    startWifiDirectGroup()
                }
            }
        }

        val configResult = _state.filter {
            (it.wifiDirectGroupStatus == LocalHotspotStatus.STARTED ||
                    it.localOnlyHotspotStatus == LocalHotspotStatus.STARTED) ||
                    it.errorCode != 0
        }.first()

        //now observe a flow of the datagramsocket that is bound to this network

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
                            .array())
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
                    onMmcpHelloReceivedListener = { },
                    logger = logger,
                )

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

                for(i in 0 until 50) {
                    logger(
                        Log.INFO, "$logPrefix : addWifiConnectionConnect:Sending " +
                                "hello to ${networkAndServerAddr.dhcpServer}:${config.port} " +
                                "from local:${networkBoundDatagramSocket.localPort}", null
                    )

                    val helloMessageId = Random.nextInt()
                    networkBoundDatagramSocket.sendHello(
                        messageId = helloMessageId,
                        nextHopAddress = networkAndServerAddr.dhcpServer,
                        nextHopPort = config.port
                    )
                    delay(1000)
                }
            }
        }else {
            logger(Log.ERROR, "$logPrefix : addWifiConnectionConnect: to hotspot: returned null network", null)
        }

        // addNewDatagramNeighborConnection(remoteAddress, remotePort, socket
    }

    override fun close() {
        if(!closed.getAndSet(true)) {
            if(Build.VERSION.SDK_INT >= 27) {
                channel?.close()
            }
            appContext.unregisterReceiver(wifiDirectBroadcastReceiver)

            channel = null
        }
    }


}