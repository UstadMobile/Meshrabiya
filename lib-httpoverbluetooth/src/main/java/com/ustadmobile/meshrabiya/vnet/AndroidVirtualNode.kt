package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.meshrabiya.client.UuidAllocationClient
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.server.AbstractHttpOverBluetoothServer
import com.ustadmobile.meshrabiya.server.OnUuidAllocatedListener
import com.ustadmobile.meshrabiya.server.UuidAllocationServer
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

class AndroidVirtualNode(
    val appContext: Context,
    uuidMask: UUID,
    port: Int = 0,
    logger: com.ustadmobile.meshrabiya.MNetLogger = com.ustadmobile.meshrabiya.MNetLogger { _, _, _ -> },
    localMNodeAddress: Int = randomApipaAddr(),
    json: Json,
): VirtualNode(
    uuidMask = uuidMask,
    port = port,
    logger = logger,
    localNodeAddress = localMNodeAddress,
    json = json,
) {


    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )


    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    /**
     * Listen to the WifiManager for new wifi connections being established.. When they are
     * established call addNewDatagramNeighborConnection to setup the neighbor connection.
     */
    private val newWifiConnectionListener = MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener {
        addNewDatagramNeighborConnection(it.neighborInetAddress, it.neighborPort, it.socket)
    }

    override val hotspotManager: MeshrabiyaWifiManager = MeshrabiyaWifiManagerAndroid(
        appContext = appContext,
        logger = logger,
        localNodeAddr = localMNodeAddress,
        router = this,
        ioExecutor = connectionExecutor,
        onNewWifiConnectionListener = newWifiConnectionListener,
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

        coroutineScope.launch {
            hotspotManager.state.collect {
                _state.update { prev ->
                    prev.copy(
                        wifiState = it,
                        connectUri = generateConnectUri(
                            hotspot = it.config
                        )
                    )
                }
            }
        }
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

    suspend fun addWifiConnection(remoteVirtualAddr: Int) {
        if(remoteVirtualAddr == localNodeAddress)
            logger(Log.DEBUG, "Nah, not now", null)

        coroutineScope {
            val responseCompleteableDeferred = CompletableDeferred<MmcpHotspotResponse>()
            launch {
                val response = incomingMmcpMessages.mapNotNull {
                    it as? MmcpHotspotResponse //Need to filter better
                }.first()

                responseCompleteableDeferred.complete(response)
            }

            sendRequestWifiConnectionMmcpMessage(remoteVirtualAddr)
            val hotspotResponse = responseCompleteableDeferred.await()

            logger(Log.INFO, "$logPrefix : addWifiConnection: got response ${hotspotResponse.result}", null)
            val config = hotspotResponse.result.config
            if(config != null) {
                hotspotManager.connectToHotspot(config)
            }else {
                logger(Log.ERROR, "$logPrefix: addWifiConnection: Received null config", null)
            }
        }
    }





}