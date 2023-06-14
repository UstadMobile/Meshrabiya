package com.ustadmobile.httpoverbluetooth.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.MNetLogger
import com.ustadmobile.httpoverbluetooth.client.UuidAllocationClient
import com.ustadmobile.httpoverbluetooth.ext.addressToDotNotation
import com.ustadmobile.httpoverbluetooth.ext.appendOrReplace
import com.ustadmobile.httpoverbluetooth.ext.readRemoteAddress
import com.ustadmobile.httpoverbluetooth.ext.writeAddress
import com.ustadmobile.httpoverbluetooth.server.AbstractHttpOverBluetoothServer
import com.ustadmobile.httpoverbluetooth.server.OnUuidAllocatedListener
import com.ustadmobile.httpoverbluetooth.server.UuidAllocationServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.Exception
import kotlin.random.Random

//Generate a random Automatic Private IP Address
fun randomApipaAddr(): Int {
    //169.254
    val fixedSection = (169 shl 24).or(254 shl 16)

    val randomSection = Random.nextInt(Short.MAX_VALUE.toInt())

    return fixedSection.or(randomSection)
}

/**
 * Mashrabiya Node
 *
 * Connection refers to the underlying "real" connection to some other device. There may be multiple
 * connections to the same remote node (e.g. Bluetooth, Sockets running over WiFi, etc)
 *
 * Addresses are 32 bit integers in the APIPA range
 */
open class MNode(
    val appContext: Context,
    val allocationServiceUuid: UUID,
    val allocationCharacteristicUuid: UUID,
    val logger: MNetLogger = MNetLogger { _, _, _, -> },
    val localMNodeAddress: Int = randomApipaAddr(),
): RemoteMNodeManager.RemoteMNodeManagerListener {

    //This executor is used for direct I/O activities
    private val connectionExecutor = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    private val scheduledExecutor = Executors.newScheduledThreadPool(2)

    //All incoming packets get read and emitted here. - maybe? could be a buffering pain
    private val _incomingPackets = MutableSharedFlow<MNetPacket>()

    //The underlying connections to other nodes (eg. real bluetooth). - Map of address to ISocket
    //For each one we make a thread that will read and then call onIncomingPacket
    val transportSockets = mutableMapOf<String, ISocket>()

    private val serverSockets: List<MNetServerSocket> = mutableListOf()

    private val underlyingSocketIdsAtomic = AtomicInteger(1)

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val remoteMNodeConnectionManagers = CopyOnWriteArrayList<RemoteMNodeManager>()

    private val _remoteNodeStates = MutableStateFlow(emptyList<RemoteMNodeState>())

    val remoteNodeStates: Flow<List<RemoteMNodeState>> = _remoteNodeStates.asStateFlow()

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
                handleNewBluetoothConnection(iSocket)
            }catch(e: SecurityException) {
                logger(Log.ERROR, "Accept new node via Bluetooth: security exception exception", e)
            }catch(e: Exception) {
                logger(Log.ERROR, "Accept new node via Bluetooth: connect exception", e)
            }
        }
    }

    override fun onNodeStateChanged(remoteMNodeState: RemoteMNodeState) {
        _remoteNodeStates.update { prev ->
            prev.appendOrReplace(remoteMNodeState) { it.remoteAddress == remoteMNodeState.remoteAddress }
        }
    }

    private val uuidAllocationServer = UuidAllocationServer(
        appContext = appContext,
        allocationServiceUuid = allocationServiceUuid,
        allocationCharacteristicUuid = allocationCharacteristicUuid,
        onUuidAllocated = onUuidAllocatedListener,
    )

    private val uuidAllocationClient = UuidAllocationClient(appContext, onLog = { _, _, _ -> } )

    init {
        uuidAllocationServer.start()
    }

    private fun handleNewBluetoothConnection(
        iSocket: ISocket
    ) {
        logger(Log.DEBUG, "MNode.handleNewBluetoothConnection: write address: " +
                localMNodeAddress.addressToDotNotation(),null)

        iSocket.outputStream.writeAddress(localMNodeAddress)
        iSocket.outputStream.flush()

        val remoteAddress = iSocket.inStream.readRemoteAddress()
        logger(Log.DEBUG, "MNode.handleNewBluetoothConnection: read remote address: " +
                remoteAddress.addressToDotNotation(),null)

        val newRemoteNodeManager = RemoteMNodeManager(
            remoteAddress = remoteAddress,
            localMNodeAddress = localMNodeAddress,
            connectionExecutor = connectionExecutor,
            scheduledExecutor = scheduledExecutor,
            logger = logger,
            listener = this,
        ).also {
            it.addConnection(iSocket)
        }

        remoteMNodeConnectionManagers.add(newRemoteNodeManager)
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
                    handleNewBluetoothConnection(iSocket)
                }

            }catch(e:SecurityException){
                logger(Log.ERROR, "addBluetoothConnection: SecurityException", e)
            }catch(e: Exception) {
                logger(Log.ERROR, "addBluetoothConnection: other exception", e)
            }
        }
    }


    fun open(port: Int): MNetServerSocket {
        //Add a new VServerSocket to the list, which will then accept connections
        TODO()
    }

    suspend fun connect(destination: String, port: Int): MNetClientSocket {
        //Send CONNECT packet to destination
        //val originPort = outgoingAtomic.incrementAndGet()

        //wait for incoming ACCEPT
        //e.g. _incomingPackets.filter { it.dest == thisNode && it.op == ACCEPT }

        TODO()
    }

    fun onIncomingPacket(packet: MNetPacket) {

    }

    suspend fun sendPacket(nextHop: String, packet: MNetPacket) {
        // transportSockets[nextHop].outputStream.write
        // Wait for ACK (if more than one hop)
    }

}