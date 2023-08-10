package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
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
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.ServerSocket
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
    )
) : Closeable, MeshrabiyaWifiManager {

    private val logPrefix = "[LocalHotspotManagerAndroid: ${localNodeAddr.addressToDotNotation()}] "

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

    private val _state = MutableStateFlow(MeshrabiyaWifiState())

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
    }


    override val is5GhzSupported: Boolean
        get() = wifiManager.is5GHzBandSupported


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
                    wifiDirectManager.startWifiDirectGroup()
                }
                else -> {
                    //Do nothing
                }
            }
        }

        val configResult = _state.filter {
            it.wifiDirectState.hotspotStatus == HotspotStatus.STARTED || it.errorCode != 0
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
            val specifier = WifiNetworkSpecifier.Builder()
                .apply {
                    setSsid(config.ssid)
                    //TODO: set the bssid when we are confident it did not change / we know it.
//                    if(bssid != null) {
//                        setBssid(MacAddress.fromString(bssid))
//                    }
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

        /*
        if(networkAndServerAddr != null) {
            val bssid = config.bssid
            if(bssid != null) {
                storeBssidForAddress(config.nodeVirtualAddr, bssid)
                logger(Log.INFO, "$logPrefix connectToHotspot: saved bssid = $bssid for " +
                        config.nodeVirtualAddr.addressToDotNotation(), null)
            }

            withContext(Dispatchers.IO) {


            }
        }else {
            logger(Log.ERROR, "$logPrefix : addWifiConnectionConnect: to hotspot: returned null network", null)
        }
        */

    }

    /**
     * Create a datagramsocket that is bound to the the network object for the wifi station network.
     */
    private suspend fun createStationNetworkBoundSockets(network: Network, config: WifiConnectConfig) {
        withContext(Dispatchers.IO) {
            val linkProperties = connectivityManager
                .getLinkProperties(network)

            logger(Log.INFO, "$logPrefix : connectToHotspot: Got link local address:", null)
            val socketPort = findFreePort(0)
            val socket = DatagramSocket(socketPort)

            network.bindSocket(socket)
            val networkBoundDatagramSocket = VirtualNodeDatagramSocket(
                socket = socket,
                localNodeVirtualAddress = localNodeAddr,
                ioExecutorService = ioExecutor,
                router = router,
                onMmcpHelloReceivedListener = {
                    //Do nothing - this will never receive an incoming hello.
                },
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

            //Binding something to the network helps to avoid older versions of Android
            //deciding to disconnect from this network.
            logger(Log.INFO, "$logPrefix : addWifiConnection:Created network bound port on ${networkBoundDatagramSocket.localPort}", null)
            _state.update { prev ->
                prev.copy(
                    wifiRole = if(config.hotspotType == HotspotType.LOCALONLY_HOTSPOT) {
                        WifiRole.WIFI_DIRECT_GROUP_OWNER
                    }else {
                        WifiRole.CLIENT
                    },
                    wifiStationState = prev.wifiStationState.copy(
                        stationBoundSocketsPort = socketPort,
                    )
                )
            }

            val networkInterface = NetworkInterface.getByName(linkProperties?.interfaceName)

            //Create a scoped Inet6 address using the given local link
            val peerAddr = Inet6Address.getByAddress(
                config.linkLocalAddr.requireHostAddress(), config.linkLocalAddr.address, networkInterface
            )

            logger(Log.DEBUG, "$logPrefix : addWifiConnectionConnect: Peer address is: $peerAddr", null)

            //Once connected,
            onNewWifiConnectionListener.onNewWifiConnection(WifiConnectEvent(
                neighborPort = config.port,
                neighborInetAddress = peerAddr,
                socket = networkBoundDatagramSocket,
                neighborVirtualAddress = config.nodeVirtualAddr,
            ))
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

    suspend fun lookupStoredBssid(addr: Int) : String? {
        val prefKey = stringPreferencesKey(addr.addressToDotNotation())
        return appContext.bssidDataStore.data.map {
            it[prefKey]
        }.first()
    }

    suspend fun storeBssidForAddress(addr: Int, bssid: String) {
        val prefKey = stringPreferencesKey(addr.addressToDotNotation())
        appContext.bssidDataStore.edit {
            it[prefKey] = bssid
        }
    }

    companion object {

        const val HOTSPOT_TIMEOUT = 10000L

        const val WIFI_DIRECT_SERVICE_TYPE = "_meshr._tcp"

    }

}