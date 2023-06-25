package com.ustadmobile.meshrabiya.vnet

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
import com.ustadmobile.meshrabiya.client.UuidAllocationClient
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.server.AbstractHttpOverBluetoothServer
import com.ustadmobile.meshrabiya.server.OnUuidAllocatedListener
import com.ustadmobile.meshrabiya.server.UuidAllocationServer
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotManager
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotManagerAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import kotlin.random.Random

class AndroidVirtualNode(
    val appContext: Context,
    uuidMask: UUID,
    port: Int = 0,
    logger: com.ustadmobile.meshrabiya.MNetLogger = com.ustadmobile.meshrabiya.MNetLogger { _, _, _ -> },
    localMNodeAddress: Int = randomApipaAddr(),
): VirtualNode(
    uuidMask = uuidMask,
    port = port,
    logger = logger,
    localNodeAddress = localMNodeAddress,
) {


    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val connectivityManager: ConnectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    override val hotspotManager: LocalHotspotManager = LocalHotspotManagerAndroid(
        appContext, logger, localMNodeAddress, this
    )

    /**
     * Listener that opens a bluetooth server socket
     */
    private val onUuidAllocatedListener = OnUuidAllocatedListener { uuid ->
        val serverSocket: BluetoothServerSocket? = try {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("mnet", uuid)
        } catch (e: SecurityException) {
            null
        }

        val clientSocket: BluetoothSocket? = try {
            logger(Log.DEBUG, "Waiting for client to connect on bluetooth classic UUID $uuid", null)
            serverSocket?.accept(AbstractHttpOverBluetoothServer.SOCKET_ACCEPT_TIMEOUT) //Can add timeout here
        } catch (e: IOException) {
            logger(Log.ERROR,"Exception accepting socket", e)
            null
        }

        clientSocket?.also { socket ->
            try {
                val iSocket = socket.asISocket()
                handleNewSocketConnection(iSocket)
            }catch(e: SecurityException) {
                logger(Log.ERROR, "Accept new node via Bluetooth: security exception exception", e)
            }catch(e: Exception) {
                logger(Log.ERROR, "Accept new node via Bluetooth: connect exception", e)
            }
        }
    }

    private val uuidAllocationServer = UuidAllocationServer(
        appContext = appContext,
        allocationServiceUuid = allocationServiceUuid,
        allocationCharacteristicUuid = allocationCharacteristicUuid,
        onUuidAllocated = onUuidAllocatedListener,
    )

    private val uuidAllocationClient = UuidAllocationClient(
        appContext = appContext,
        onLog = logger,
        clientNodeAddr = localNodeAddress
    )

    init {
        uuidAllocationServer.start()
    }


    suspend fun addBluetoothConnection(
        remoteBluetooothAddr: String,
    ) {
        logger(Log.DEBUG, "AddBluetoothConnection to $remoteBluetooothAddr", null)
        withContext(Dispatchers.IO) {
            val dataUuid = uuidAllocationClient.requestUuidAllocation(
                remoteAddress = remoteBluetooothAddr,
                uuidMask = uuidMask,
            )

            val remoteDevice = bluetoothAdapter?.getRemoteDevice(remoteBluetooothAddr)

            val socket: BluetoothSocket?
            try {
                logger(Log.DEBUG, "AddBluetoothConnection : got data UUID: $dataUuid, " +
                        "creating rfcomm sockettoservice", null)
                socket = remoteDevice?.createInsecureRfcommSocketToServiceRecord(
                    dataUuid
                )

                socket?.also {
                    logger(Log.DEBUG, "AddBluetoothConnection: connecting", null)
                    it.connect()
                    logger(Log.DEBUG, "AddBluetoothConnection: connected, submit runnable", null)
                    val iSocket = it.asISocket()
                    handleNewSocketConnection(iSocket)
                }

            }catch(e:SecurityException){
                logger(Log.ERROR, "addBluetoothConnection: SecurityException", e)
            }catch(e: Exception) {
                logger(Log.ERROR, "addBluetoothConnection: other exception", e)
            }
        }
    }

    suspend fun addWifiConnection(remoteAddr: Int) {
        if(remoteAddr == localNodeAddress)
            logger(Log.DEBUG, "Nah, not now", null)

        coroutineScope {
            val responseCompleteableDeferred = CompletableDeferred<MmcpHotspotResponse>()
            launch {
                val response = incomingMmcpMessages.mapNotNull {
                    it as? MmcpHotspotResponse //Need to filter better
                }.first()

                responseCompleteableDeferred.complete(response)
            }

            requestLocalHotspot(remoteAddr)
            val hotspotResponse = responseCompleteableDeferred.await()

            logger(Log.INFO, "$logPrefix : addWifiConnection: got response ${hotspotResponse.result}", null)
            val ssid = hotspotResponse.result.config?.ssid
            val passphrase = hotspotResponse.result.config?.passphrase
            if(ssid != null && passphrase != null) {
                val network = connectToHotspot(ssid, passphrase)
                if(network != null) {
                    withContext(Dispatchers.IO) {
                        val networkBoundDatagramSocket = VirtualNodeDatagramSocket(
                            port = 0,
                            localNodeVirtualAddress = localNodeAddress,
                            ioExecutorService = connectionExecutor,
                            router = this@AndroidVirtualNode,
                            onMmcpHelloReceivedListener = { },
                            logger = logger,
                        )

                        //Binding something to the network helps to avoid older versions of Android
                        //deciding to disconnect from this network.
                        network.bindSocket(networkBoundDatagramSocket)
                        logger(Log.INFO, "$logPrefix : addWifiConnectionConnect:Created network bound port on ${networkBoundDatagramSocket.localPort}", null)

                        for(i in 0 until 50) {
                            logger(Log.INFO, "$logPrefix : addWifiConnectionConnect:Sending hello to 192.168.49.1:${hotspotResponse.result.config.port} from local:${networkBoundDatagramSocket.localPort}", null)

                            val helloMessageId = Random.nextInt()
                            networkBoundDatagramSocket.sendHello(
                                messageId = helloMessageId,
                                nextHopAddress = InetAddress.getByName("192.168.49.1"),
                                nextHopPort = hotspotResponse.result.config.port
                            )
                            delay(1000)
                        }


                    }
                }else {
                    logger(Log.ERROR, "$logPrefix : addWifiConnectionConnect: to hotspot: returned null network", null)
                }

                // Create a new datagram socket, bind it to the network, then
                // addNewDatagramNeighborConnection(remoteAddress, remotePort, socket
            }

        }
        //1: send the request
        //2: wait for response
        //3: init connection
    }

    @Suppress("DEPRECATION") //Must use deperecated class to support pre-SDK29
    @SuppressLint("MissingPermission") //Permissions will be set by the app, not the library
    fun WifiManager.addOrLookupNetwork(config: WifiConfiguration): Int {
        val existingNetwork = configuredNetworks.firstOrNull {
            it.SSID == config.SSID && it.status != WifiConfiguration.Status.DISABLED
        }

        logger(Log.DEBUG, "$logPrefix addOrLookupNetwork: existingNetworkId=${existingNetwork?.networkId}", null)
        return existingNetwork?.networkId ?: addNetwork(config)
    }


    /**
     * Connect to the given hotspot as a station.
     */
    @Suppress("DEPRECATION") //Must use deprecated classes to support pre-SDK29
    suspend fun connectToHotspot(ssid: String, passphrase: String): Network? {
        logger(Log.INFO, "$logPrefix Connecting to hotspot: ssid=$ssid passphrase=$passphrase", null)

        val completable = CompletableDeferred<Network?>()
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

            val callback = object: ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logger(Log.DEBUG, "$logPrefix connectToHotspot: connection available", null)
                    completable.complete(network)
                }

                override fun onUnavailable() {
                    logger(Log.DEBUG, "$logPrefix connectToHotspot: connection unavailable", null)
                    completable.complete(null)
                    super.onUnavailable()
                }
            }

            logger(Log.DEBUG, "$logPrefix connectToHotspot: Requesting network for $ssid / $passphrase", null)
            connectivityManager.requestNetwork(request, callback)
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
            val configNetworkId = wifiManager.addOrLookupNetwork(wifiConfig)
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

            val callback = object: ConnectivityManager.NetworkCallback() {


                override fun onAvailable(network: Network) {
                    logger(Log.DEBUG, "$logPrefix connectToHotspot: connection available", null)
                    completable.complete(network)
                }

                override fun onUnavailable() {
                    logger(Log.DEBUG, "$logPrefix connectToHotspot: connection unavailable", null)
                    completable.complete(null)
                    super.onUnavailable()
                }
            }

            logger(Log.DEBUG, "$logPrefix connectToHotspot: requesting network for $ssid", null)
            connectivityManager.requestNetwork(networkRequest, callback)
        }

        return completable.await()
    }

}