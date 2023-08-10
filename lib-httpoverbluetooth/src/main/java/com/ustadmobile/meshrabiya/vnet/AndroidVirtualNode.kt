package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.server.AbstractHttpOverBluetoothServer
import com.ustadmobile.meshrabiya.server.OnUuidAllocatedListener
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AndroidVirtualNode(
    val appContext: Context,
    uuidMask: UUID,
    port: Int = 0,
    logger: MNetLogger = MNetLoggerStdout(),
    localMNodeAddress: Int = randomApipaAddr(),
    json: Json,
    dataStore: DataStore<Preferences>,
    config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
): VirtualNode(
    uuidMask = uuidMask,
    port = port,
    logger = logger,
    localNodeAddress = localMNodeAddress,
    json = json,
    config = config,
) {


    private val bluetoothManager: BluetoothManager by lazy {
        appContext.getSystemService(BluetoothManager::class.java)
    }


    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    /**
     * Listen to the WifiManager for new wifi connections being established.. When they are
     * established call addNewDatagramNeighborConnection to setup the neighbor connection.
     */
    private val newWifiConnectionListener = MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener {
        addNewNeighborConnection(
            address = it.neighborInetAddress,
            port = it.neighborPort,
            neighborNodeVirtualAddr =  it.neighborVirtualAddress,
            socket = it.socket,
        )
    }

    override val meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid = MeshrabiyaWifiManagerAndroid(
        appContext = appContext,
        logger = logger,
        localNodeAddr = localMNodeAddress,
        router = this,
        chainSocketFactory = chainSocketFactory,
        ioExecutor = connectionExecutor,
        dataStore = dataStore,
        json = json,
        onNewWifiConnectionListener = newWifiConnectionListener,
    )

    private val _bluetoothState = MutableStateFlow(MeshrabiyaBluetoothState())

    private fun updateBluetoothState() {
        try {
            val deviceName = bluetoothAdapter?.name
            _bluetoothState.takeIf { it.value.deviceName != deviceName }?.update { prev ->
                MeshrabiyaBluetoothState(deviceName = deviceName)

            }
        }catch(e: SecurityException) {
            logger(Log.WARN, "Could not get device name", e)
        }
    }

    private val bluetoothStateBroadcastReceiver: BroadcastReceiver = object: BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent != null && intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when(state) {
                    BluetoothAdapter.STATE_ON -> {
                        updateBluetoothState()
                    }

                    BluetoothAdapter.STATE_OFF -> {
                        _bluetoothState.value = MeshrabiyaBluetoothState(
                            deviceName = null
                        )
                    }
                }
            }
        }
    }

    private val receiverRegistered = AtomicBoolean(false)

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


    init {
        //uuidAllocationServer.start()
        appContext.registerReceiver(
            bluetoothStateBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        receiverRegistered.set(true)

        coroutineScope.launch {
            meshrabiyaWifiManager.state.combine(_bluetoothState) { wifiState, bluetoothState ->
                wifiState to bluetoothState
            }.collect {
                _state.update { prev ->
                    prev.copy(
                        wifiState = it.first,
                        bluetoothState = it.second,
                        connectUri = generateConnectLink(
                            hotspot = it.first.config,
                            bluetoothConfig = it.second,
                        ).uri
                    )
                }
            }
        }
    }


    suspend fun addBluetoothConnection(
        remoteBluetooothAddr: String,
    ) {
        logger(Log.DEBUG, "AddBluetoothConnection to $remoteBluetooothAddr", null)
        /*
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
         */
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

            logger(Log.INFO, "$logPrefix : addWifiConnection (by remote address): got response ${hotspotResponse.result}", null)
            val config = hotspotResponse.result.config

            if(config != null) {
                addWifiConnection(config)
            }else {
                logger(Log.ERROR, "$logPrefix: addWifiConnection: Received null config", null)
            }
        }
    }


    override fun close() {
        super.close()

        if(receiverRegistered.getAndSet(false)) {
            appContext.unregisterReceiver(bluetoothStateBroadcastReceiver)
        }
    }

    suspend fun addWifiConnection(
        config: WifiConnectConfig,
    ) {
        meshrabiyaWifiManager.connectToHotspot(config)
    }

    override suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand,
    ) : LocalHotspotResponse?{
        updateBluetoothState()
        return super.setWifiHotspotEnabled(enabled, preferredBand)
    }

    suspend fun lookupStoredBssid(addr: Int) : String? {
        return meshrabiyaWifiManager.lookupStoredBssid(addr)
    }

    suspend fun storeBssidForAddress(addr: Int, bssid: String) {
        meshrabiyaWifiManager.storeBssidForAddress(addr, bssid)
    }
}