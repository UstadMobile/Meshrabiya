package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ustadmobile.meshrabiya.ext.addOrLookupNetwork
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.bssidDataStore
import com.ustadmobile.meshrabiya.ext.firstOrNull
import com.ustadmobile.meshrabiya.ext.requireHostAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.util.findFreePort
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.WifiRole
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactory
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketServer
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
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
    private val logger: MNetLogger,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val chainSocketFactory: ChainSocketFactory,
    private val ioExecutor: ExecutorService,
    private val onNewWifiConnectionListener: OnNewWifiConnectionListener = OnNewWifiConnectionListener { },
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val wifiDirectManager: WifiDirectManager = WifiDirectManager(
        appContext = appContext,
        logger = logger,
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
        json = json,
        ioExecutorService = ioExecutor,
    ),

    private val localOnlyHotspotManager: LocalOnlyHotspotManager = LocalOnlyHotspotManager(
        appContext = appContext,
        logger = logger,
        name = localNodeAddr.addressToDotNotation(),
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
    )
) : Closeable, MeshrabiyaWifiManager {

    private val logPrefix = "[MeshrabiyaWifiManagerAndroid: ${localNodeAddr.addressToDotNotation()}] "

    private val nodeScope = CoroutineScope(Dispatchers.Main + Job())

    private inner class ConnectNetworkCallback(
        private val config: WifiConnectConfig
    ): NetworkCallback() {
        override fun onAvailable(network: Network) {
            logger(Log.DEBUG, "$logPrefix connectToHotspot: connection available. Network=$network")
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.AVAILABLE,
                        network = network,
                    )
                )
            }

            nodeScope.launch {
                try {
                    createStationNetworkBoundSockets(network, config)
                }catch(e: Exception) {
                    logger(Log.ERROR, "$logPrefix ConnectNetworkCallback: Exception creating station sockets", e)
                }
            }
        }

        override fun onUnavailable() {
            logger(Log.DEBUG, "$logPrefix connectToHotspot: connection unavailable", null)
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.UNAVAILABLE,
                    )
                )
            }
        }

        override fun onLost(network: Network) {
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.LOST,
                    )
                )
            }
        }
    }

    fun interface OnNewWifiConnectionListener {
        fun onNewWifiConnection(connectEvent: WifiConnectEvent)
    }


    private val connectivityManager: ConnectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)

    private val _state = MutableStateFlow(MeshrabiyaWifiState(
        concurrentApStationSupported = if(Build.VERSION.SDK_INT >= 30) {
            wifiManager.isStaApConcurrencySupported
        }else {
            false
        }
    ))

    override val state: Flow<MeshrabiyaWifiState> = _state.asStateFlow()

    /**
     * When this device is connected as a station, we will create a new DatagramSocket and
     * ChainSocketServer that is bound to the Android Network object. This helps prevent older
     * versions of Android from disconnecting when it realizes the connection has no Internet
     * (e.g. Android will see activity on the network).
     */
    private val stationBoundSockets = AtomicReference<Pair<VirtualNodeDatagramSocket, ChainSocketServer>?>()

    private val closed = AtomicBoolean(false)

    private var wifiLock: WifiManager.WifiLock? = null

    private val connectRequest = AtomicReference<Pair<WifiConnectConfig, NetworkCallback>?>(null)

    init {
        wifiDirectManager.onBeforeGroupStart = WifiDirectManager.OnBeforeGroupStart {
            // Do nothing - in future may need to stop other WiFi stuff
        }

        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "meshrabiya").also {
            it.acquire()
        }

        nodeScope.launch {
            wifiDirectManager.state.collect {
                _state.update { prev ->
                    prev.copy(
                        wifiDirectState = it,
                        wifiRole = if(it.config != null) {
                            WifiRole.WIFI_DIRECT_GROUP_OWNER
                        }else if(prev.wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER) {
                            WifiRole.NONE
                        }else {
                            prev.wifiRole
                        }
                    )
                }
            }
        }

        nodeScope.launch {
            localOnlyHotspotManager.state.collect {
                _state.update { prev ->
                    prev.copy(
                        localOnlyHotspotState = it
                    )
                }
            }
        }

    }

    private fun assertNotClosed() {
        if(closed.get())
            throw IllegalStateException("$logPrefix is closed!")
    }

    override val is5GhzSupported: Boolean
        get() = wifiManager.is5GHzBandSupported


    override suspend fun requestHotspot(
        requestMessageId: Int,
        request: LocalHotspotRequest
    ): LocalHotspotResponse {
        assertNotClosed()

        logger(Log.DEBUG, "$logPrefix requestHotspot requestId=$requestMessageId", null)
        val spotTypeCreated = withContext(Dispatchers.Main) {
            val prevState = _state.getAndUpdate { prev ->
                when(prev.hotspotTypeToCreate) {
                    HotspotType.WIFIDIRECT_GROUP -> prev.copy(
                        wifiDirectState = prev.wifiDirectState.copy(
                            hotspotStatus = HotspotStatus.STARTING
                        )
                    )

                    else -> prev
                }
            }

            when(prevState.hotspotTypeToCreate) {
                HotspotType.WIFIDIRECT_GROUP -> {
                    wifiDirectManager.startWifiDirectGroup(request.preferredBand)
                }
                HotspotType.LOCALONLY_HOTSPOT -> {
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)
                }
                else -> {
                    //Do nothing
                }
            }

            prevState.hotspotTypeToCreate
        }

        val configResult = _state.filter {
            it.hotspotIsStarted || spotTypeCreated != null && it.hotspotError(spotTypeCreated) != 0
        }.first()

        return LocalHotspotResponse(
            responseToMessageId = requestMessageId,
            errorCode = configResult.wifiDirectState.error,
            config = configResult.connectConfig,
            redirectAddr = 0
        )
    }

    override suspend fun deactivateHotspot() {
        assertNotClosed()

        wifiDirectManager.stopWifiDirectGroup()
    }

    /**
     * Connect to the given hotspot as a station.
     */
    @Suppress("DEPRECATION") //Must use deprecated classes to support pre-SDK29
    private suspend fun connectToHotspotInternal(
        config: WifiConnectConfig,
    ): Network {
        logger(Log.INFO,
            "$logPrefix Connecting to hotspot: ssid=${config.ssid} passphrase=${config.passphrase} bssid=${config.bssid}"
        )

        val networkCallback = ConnectNetworkCallback(config)

        val networkRequest = if(Build.VERSION.SDK_INT >= 29) {
            //Use the suggestion API as per https://developer.android.com/guide/topics/connectivity/wifi-bootstrap
            /*
             * Dialog behavior notes
             *
             * On Android 11+ if the network is in the CompanionDeviceManager approved list (which
             * works on the basis of BSSID only), then no approval dialog will be shown:
             * See:
             * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r1:frameworks/opt/net/wifi/service/java/com/android/server/wifi/WifiNetworkFactory.java;l=1321
             *
             * On Android 10:
             * No WifiNetworkFactory uses a list of approved access points. The BSSID, SSID, and
             * network type must match.
             * See:
             * https://cs.android.com/android/platform/superproject/+/android-10.0.0_r47:frameworks/opt/net/wifi/service/java/com/android/server/wifi/WifiNetworkFactory.java;l=1224
             */
            logger(Log.DEBUG, "$logPrefix connectToHotspot: building network specifier", null)
            val bssid = config.bssid ?: config.linkLocalAsMacAddress?.toString()
            val specifier = WifiNetworkSpecifier.Builder()
                .apply {
                    setSsid(config.ssid)
                    if(bssid != null)
                        setBssid(MacAddress.fromString(bssid))

                    //Normally it would be nice to set the band here to speed up connection (avoid
                    //the need to scan other bands).
                    //
                    //Testing on Android 13 / Samsung Tab: specifying the band caused connection to fail
                    //Will receive callback that network is available followed immediately by unavailable callback
                    //Thanks, Google.
                }
                .setWpa2Passphrase(config.passphrase)
                .build()

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

                .setNetworkSpecifier(specifier)
                .build()
        }else {
            //use pre-Android 10 WifiManager API
            val wifiConfig = WifiConfiguration().apply {
                SSID =  "\"${config.ssid}\""
                preSharedKey = "\"${config.passphrase}\""

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

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        }

        logger(Log.DEBUG, "$logPrefix connectToHotspot: requesting network for ${config.ssid}", null)
        val prevRequest = connectRequest.getAndUpdate {
            config to networkCallback
        }

        prevRequest?.second?.also {
            logger(Log.DEBUG, "$logPrefix connectToHotspot: unregister previous callback: $it")
            connectivityManager.unregisterNetworkCallback(it)
        }

        connectivityManager.requestNetwork(networkRequest, networkCallback)

        _state.update { prev ->
            prev.copy(
                wifiStationState = prev.wifiStationState.copy(
                    status = WifiStationState.Status.CONNECTING,
                    config = config,
                    network = null,
                    stationBoundSocketsPort = -1,
                )
            )
        }

        val resultState = _state.map { it.wifiStationState }.filter {
            it.status != WifiStationState.Status.CONNECTING
        }.first()

        if (resultState.network != null) {
            logger(Log.INFO, "$logPrefix connectToHotspot: ${config.ssid} - success status=${resultState.status}")
            return resultState.network
        }else {
            logger(Log.ERROR, "$logPrefix connectToHotspot: ${config.ssid} - fail status=${resultState.status}")
            throw WifiConnectException("ConnectToHotspot: ${config.ssid} status=${resultState.status} network=null")
        }
    }

    override suspend fun connectToHotspot(
        config: WifiConnectConfig,
        timeout: Long,
    ) {
        if(config.band == ConnectBand.BAND_5GHZ && !wifiManager.is5GHzBandSupported) {
            throw WifiConnectException("ERROR: 5Ghz not supported by device: ${config.ssid} uses 5Ghz band")
        }

        withTimeout(timeout) {
            connectToHotspotInternal(config)

            val resultState = _state.filter {
                it.wifiStationState.stationBoundSocketsPort != -1 || it.wifiStationState.status in WifiStationState.Status.FAIL_STATES
            }.first()
            val stationStatus = resultState.wifiStationState.status

            if(stationStatus in WifiStationState.Status.FAIL_STATES) {
                throw WifiConnectException("Attempted to connect to ${config.ssid}, status=$stationStatus")
            }
        }
    }

    /**
     * Disconnect the client station connection - remove the network request, close sockets. If
     * the station mode is already inactive, this will have no effect.
     */
    suspend fun disconnectStation() {
        val prevState = _state.getAndUpdate { prev ->
            if(prev.wifiStationState.status != WifiStationState.Status.INACTIVE) {
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.INACTIVE,
                    )
                )
            }else {
                prev
            }
        }

        if(prevState.wifiStationState.status != WifiStationState.Status.INACTIVE) {
            val prevNetworkCallback = connectRequest.getAndUpdate {
                null
            }

            val previousSockets = stationBoundSockets.getAndUpdate {
                null
            }

            try {
                previousSockets?.also {
                    withContext(Dispatchers.IO) {
                        it.first.close()
                        it.second.close()
                        logger(Log.DEBUG, "$logPrefix : disconnectStation: closed sockets")
                    }
                }
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix : disconnectionStation: exception closing sockets", e)
            }

            try {
                prevNetworkCallback?.second?.also {
                    connectivityManager.unregisterNetworkCallback(it)
                    logger(Log.DEBUG, "$logPrefix unregistered network request callback")
                }
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix disconnectStation: exception unregistering network callback")
            }

            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        config = null,
                        network = null,
                        stationBoundSocketsPort = -1,
                        stationBoundDatagramSocket = null,
                    )
                )
            }
        }
    }

    private suspend fun createBoundSocket(
        port: Int, bindAddress:
        InetAddress?,
        maxAttempts: Int,
        interval: Long = 200,
    ): DatagramSocket {
        for(i in 0 until maxAttempts) {
            try {
                return DatagramSocket(port, bindAddress).also {
                    logger(Log.DEBUG, "$logPrefix : createBoundSocket: success after ${i+1} attempts")
                }
            }catch(e: Exception) {
                delay(interval)
            }
        }

        logger(Log.WARN, "$logPrefix : createBoundSocket: failed after $maxAttempts")
        throw IllegalStateException("createBoundSocket: failed after $maxAttempts")
    }

    /**
     * Create a datagramsocket that is bound to the the network object for the wifi station network.
     *
     * Binding to the network object (network.bindSocket etc) helps to avoid Android deciding to
     * disconnect from the network because it doesn't have Internet access. This is especially true
     * on older versions (pre-Android 10) where we use WifiManager itself to connect to the network
     * (without user intervention). On Android 10+ because the connection required user approval,
     * this behavior does not seem to be as prevalent.
     */
    private suspend fun createStationNetworkBoundSockets(network: Network, config: WifiConnectConfig) {
        withContext(Dispatchers.IO) {
            val linkProperties = connectivityManager
                .getLinkProperties(network)
            val networkInterface = NetworkInterface.getByName(linkProperties?.interfaceName)

            val interfaceInet6Addrs = networkInterface.inetAddresses.toList()
            logger(Log.INFO, "$logPrefix : connectToHotspot - addrs = ${interfaceInet6Addrs.joinToString()}")

            val netAddress = networkInterface.inetAddresses.firstOrNull {
                it is Inet6Address && it.isLinkLocalAddress
            }

            logger(Log.INFO, "$logPrefix : connectToHotspot: Got link local address = " +
                    "$netAddress on interface ${linkProperties?.interfaceName}", null)

            val socketPort = findFreePort(0)

            val socket = if(config.hotspotType == HotspotType.WIFIDIRECT_GROUP) {
                /**
                 * When using a Wifi Direct group we MUST use the LinkLocal IPv6 address to the
                 * IPv4 conflict issue - where all WiFi Direct group owners are assigned 192.168.49.1
                 *  See README
                 *
                 * Strange issue: Android 13 (perhaps not exclusively) will not bind (immediately)
                 * to link local ipv6 addr for station network. This can take longer if the WiFi
                 * direct group has been created. It will bind eventually, so we can retry at short
                 * intervals until its ready.
                 *
                 * If the socket is created before this is ready, it wont even send traffic via the
                 * link local address.
                 */
                try {
                    createBoundSocket(socketPort, netAddress, 10).also {
                        logger(Log.DEBUG, "$logPrefix : createStationNetworkBoundSockets : succeeded on retry")
                    }
                }catch(e: IOException) {
                    logger(Log.ERROR, "$logPrefix : createStationNetworkBoundSockets : " +
                            "Exception trying to create bound sockets. Cannot continue", e
                    )
                    throw e
                }
            }else {
                /**
                 * LocalOnlyHotspot IP address ranges are randomized and do not appear to suffer from
                 * this issue.
                 */
                DatagramSocket(socketPort)
            }

            network.bindSocket(socket)

            val networkBoundDatagramSocket = VirtualNodeDatagramSocket(
                socket = socket,
                localNodeVirtualAddress = localNodeAddr,
                ioExecutorService = ioExecutor,
                router = router,
                logger = logger,
                name = "network bound to ${config.ssid}"
            )

            val chainSocketServer = ChainSocketServer(
                serverSocket = ServerSocket(socketPort),
                executorService = ioExecutor,
                chainSocketFactory = chainSocketFactory,
                name = "network bound to ${config.ssid}",
                logger = logger,
            )

            val previousSockets = stationBoundSockets.getAndUpdate {
                networkBoundDatagramSocket to chainSocketServer
            }

            previousSockets?.first?.close()
            previousSockets?.second?.close(true)

            logger(Log.INFO, "$logPrefix : addWifiConnection:Created network bound port on ${networkBoundDatagramSocket.localPort}", null)
            _state.update { prev ->
                prev.copy(
                    wifiRole = if(config.hotspotType == HotspotType.LOCALONLY_HOTSPOT) {
                        WifiRole.WIFI_DIRECT_GROUP_OWNER
                    }else {
                        WifiRole.CLIENT
                    },
                    wifiStationState = prev.wifiStationState.copy(
                        stationBoundDatagramSocket = networkBoundDatagramSocket,
                        stationBoundSocketsPort = socketPort,
                    )
                )
            }

            val peerAddr = config.linkLocalAddr?.let {
                logger(Log.DEBUG,
                    "$logPrefix : createStationBoundSockets: determining peer address using " +
                            "linkLocalAddr supplied in config")
                Inet6Address.getByAddress(it.requireHostAddress(), it.address, networkInterface)
            } ?: if(Build.VERSION.SDK_INT >= 30) {
                logger(Log.DEBUG, "$logPrefix - createStationBoundSockets : determining peer " +
                        "address using linkProperties.dhcpServerAddress")
                linkProperties?.dhcpServerAddress
            }else {
                logger(Log.DEBUG, "$logPrefix - createStationBoundSockets : determining peer " +
                        "address using wifimanager.dhcpInfo")
                @Suppress("DEPRECATION") //Must use deprecated property to support PRE-SDK30
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

            logger(Log.DEBUG, "$logPrefix : addWifiConnectionConnect: Peer address is: $peerAddr", null)

            if(peerAddr != null) {
                //Once connected,
                onNewWifiConnectionListener.onNewWifiConnection(WifiConnectEvent(
                    neighborPort = config.port,
                    neighborInetAddress = peerAddr,
                    socket = networkBoundDatagramSocket,
                    neighborVirtualAddress = config.nodeVirtualAddr,
                ))
            }
        }
    }

    override fun close() {
        if(!closed.getAndSet(true)) {
            nodeScope.cancel()
            wifiDirectManager.close()
            wifiLock?.also {
                it.release()
            }
        }
    }

    suspend fun lookupStoredBssid(ssid: String) : String? {
        val prefKey = stringPreferencesKey("${PREFIX_SSID}$ssid")

        return appContext.bssidDataStore.data.map {
            it[prefKey]
        }.first().also {
            logger(Log.DEBUG, "MeshrabiyaWifiManagerAndroid: lookupStoredBssid ssid=$ssid bssid=$it")
        }

    }

    suspend fun storeBssidForAddress(ssid: String, bssid: String) {
        logger(Log.DEBUG, "MeshrabiyaWifiManagerAndroid: storeBssidForAddress ssid=$ssid bssid=$bssid")
        val prefKey = stringPreferencesKey("${PREFIX_SSID}$ssid")
        appContext.bssidDataStore.edit {
            it[prefKey] = bssid
        }
        logger(Log.DEBUG, "MeshrabiyaWifiManagerAndroid: storeBssidForAddress ssid=$ssid bssid=$bssid : Done")
    }

    companion object {

        const val PREFIX_SSID = "ssid_"

        const val HOTSPOT_TIMEOUT = 10000L

        const val WIFI_DIRECT_SERVICE_TYPE = "_meshr._tcp"

    }

}