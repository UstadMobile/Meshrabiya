package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.Context
import android.net.ConnectivityManager
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
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.WifiRole
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
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


    data class NetworkAndDhcpServer(
        val network: Network,
        val dhcpServer: InetAddress
    )

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
     * When this device is connected as a station, we will create a new datagramsocket that is
     * bound to the Android Network object. This helps prevent older versions of Android from
     * disconnecting when it realizes the connection has no Internet (e.g. Android will see
     * activity on the network).
     */
    private val stationNetworkBoundDatagramSocket = AtomicReference<VirtualNodeDatagramSocket?>()

    private val closed = AtomicBoolean(false)

    init {
        wifiDirectManager.onBeforeGroupStart = WifiDirectManager.OnBeforeGroupStart {
            // Do nothing - in future may need to stop other WiFi stuff
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
    private suspend fun connectToHotspot(
        ssid: String,
        passphrase: String,
        bssid: String? = null,
    ): NetworkAndDhcpServer? {
        logger(Log.INFO, "$logPrefix Connecting to hotspot: ssid=$ssid passphrase=$passphrase bssid=$bssid", null)


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
            val specifier = WifiNetworkSpecifier.Builder()
                .apply {
                    setSsid(ssid)
                    //TODO: set the bssid when we are confident it did not change / we know it.
//                    if(bssid != null) {
//                        setBssid(MacAddress.fromString(bssid))
//                    }
                }
                .setWpa2Passphrase(passphrase)
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

    override suspend fun connectToHotspot(
        config: WifiConnectConfig,
    ) {
        val networkAndServerAddr = connectToHotspot(
            ssid = config.ssid,
            passphrase = config.passphrase,
            bssid = config.bssid
        )

        if(networkAndServerAddr != null) {
            val bssid = config.bssid
            if(bssid != null) {
                storeBssidForAddress(config.nodeVirtualAddr, bssid)
                logger(Log.INFO, "$logPrefix connectToHotspot: saved bssid = $bssid for " +
                        config.nodeVirtualAddr.addressToDotNotation(), null)
            }

            withContext(Dispatchers.IO) {
                val linkProperties = connectivityManager
                    .getLinkProperties(networkAndServerAddr.network)

                logger(Log.INFO, "$logPrefix : connectToHotspot: Got link local address:", null)
                val socket = DatagramSocket(0)

                networkAndServerAddr.network.bindSocket(socket)
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

                val previousSocket = stationNetworkBoundDatagramSocket.getAndUpdate {
                    networkBoundDatagramSocket
                }

                previousSocket?.close()

                //Binding something to the network helps to avoid older versions of Android
                //deciding to disconnect from this network.
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
                ))

                //If we are connected and now expected to act as a Wifi Direct group, setup the
                // group and add service for discovery.

                wifiDirectManager.startWifiDirectGroup()

            }
        }else {
            logger(Log.ERROR, "$logPrefix : addWifiConnectionConnect: to hotspot: returned null network", null)
        }
    }


    override fun close() {
        if(!closed.getAndSet(true)) {
            nodeScope.cancel()
            wifiDirectManager.close()
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