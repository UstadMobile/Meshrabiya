package com.ustadmobile.meshrabiya.vnet

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
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotManagerAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class AndroidVirtualNode(
    val appContext: Context,
    allocationServiceUuid: UUID,
    allocationCharacteristicUuid: UUID,
    logger: com.ustadmobile.meshrabiya.MNetLogger = com.ustadmobile.meshrabiya.MNetLogger { _, _, _ -> },
    localMNodeAddress: Int = randomApipaAddr(),
): VirtualNode(
    allocationServiceUuid = allocationServiceUuid,
    allocationCharacteristicUuid = allocationCharacteristicUuid,
    logger = logger,
    localNodeAddress = localMNodeAddress,
    hotspotManager = LocalHotspotManagerAndroid(appContext, logger, localMNodeAddress),
) {


    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val connectivityManager: ConnectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

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

    private val uuidAllocationClient = UuidAllocationClient(appContext, onLog = logger )

    init {
        uuidAllocationServer.start()
    }


    suspend fun addBluetoothConnection(
        remoteBluetooothAddr: String,
        remoteAllocationServiceUuid: UUID,
        remoteAllocationCharacteristicUuid: UUID,
    ) {
        logger(Log.DEBUG, "AddBluetoothConnection to $remoteBluetooothAddr", null)
        withContext(Dispatchers.IO) {
            val dataUuid = uuidAllocationClient.requestUuidAllocation(
                remoteAddress = remoteBluetooothAddr,
                remoteServiceUuid = remoteAllocationServiceUuid,
                remoteCharacteristicUuid = remoteAllocationCharacteristicUuid,
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
                connectToHotspot(ssid, passphrase)
            }

        }
        //1: send the request
        //2: wait for response
        //3: init connection
    }

    fun WifiManager.addOrLookupNetwork(config: WifiConfiguration): Int {
        val existingNetwork = configuredNetworks.firstOrNull {
            it.SSID == config.SSID && it.status != WifiConfiguration.Status.DISABLED
        }

        logger(Log.DEBUG, "addOrLookupNetwork: existingNetworkId=${existingNetwork?.networkId}", null)
        return existingNetwork?.networkId ?: addNetwork(config)
    }


    suspend fun connectToHotspot(ssid: String, passphrase: String) {
        logger(Log.INFO, "$logPrefix Connecting to hotspot: ssid=$ssid passphrase=$passphrase", null)

        val completable = CompletableDeferred<Boolean>()
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
                    logger(Log.DEBUG, "$logPrefix connection available", null)
                    completable.complete(true)
                }

                override fun onUnavailable() {
                    logger(Log.DEBUG, "$logPrefix connection unavailable", null)
                    completable.complete(false)
                    super.onUnavailable()
                }
            }
            logger(Log.DEBUG, "$logPrefix Requesting connection to $ssid / $passphrase", null)
            connectivityManager.requestNetwork(request, callback)
            completable.await()
        }else {
            //use pre-Android 10 WifiManager API
            val wifiConfig = WifiConfiguration().apply {
                SSID =  "\"$ssid\""
                preSharedKey = "\"$passphrase\""

                /* Setting hiddenSSID = true is necessary, even though it is not hidden
                 * Android won't connect to an SSID if it thinks the SSID is not there. The SSID
                 * might have created only a few ms ago by the other peer, and therefor won't be
                 * in the scan list. Setting hiddenSSID to true will ensure that Android attempts to
                 * connect whether or not the network is in currently known scan results.
                 */
                hiddenSSID = true
            }
            val configNetworkId = wifiManager.addOrLookupNetwork(wifiConfig)
            val currentlyConnectedNetworkId = wifiManager.connectionInfo.networkId
            logger(Log.DEBUG, "Currently connected to networkId: $currentlyConnectedNetworkId", null)

            if(currentlyConnectedNetworkId == configNetworkId) {
                logger(Log.DEBUG, "Already connected to target networkid", null)
            }else {
                wifiManager.enableNetwork(configNetworkId, true)
            }

        }
    }

    companion object {



    }

}