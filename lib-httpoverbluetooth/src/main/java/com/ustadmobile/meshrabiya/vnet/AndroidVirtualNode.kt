package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.meshrabiya.client.UuidAllocationClient
import com.ustadmobile.meshrabiya.server.AbstractHttpOverBluetoothServer
import com.ustadmobile.meshrabiya.server.OnUuidAllocatedListener
import com.ustadmobile.meshrabiya.server.UuidAllocationServer
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotManager
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotState
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotSubReservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class AndroidVirtualNode(
    val appContext: Context,
    allocationServiceUuid: UUID,
    allocationCharacteristicUuid: UUID,
    logger: com.ustadmobile.meshrabiya.MNetLogger = com.ustadmobile.meshrabiya.MNetLogger { _, _, _, -> },
    localMNodeAddress: Int = randomApipaAddr(),
): VirtualNode(
    allocationServiceUuid = allocationServiceUuid,
    allocationCharacteristicUuid = allocationCharacteristicUuid,
    logger = logger,
    localNodeAddress = localMNodeAddress,
) {


    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

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

    private val uuidAllocationClient = UuidAllocationClient(appContext, onLog = { _, _, _ -> } )

    private val localHotspotManager = LocalHotspotManager(appContext, logger)

    override val localHotSpotState: Flow<LocalHotspotState> = localHotspotManager.state

    init {
        uuidAllocationServer.start()
    }


    suspend fun requestLocalHotspot() : LocalHotspotSubReservation {
        return localHotspotManager.request()
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

            var socket: BluetoothSocket? = null
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


}