package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.core.DataStore as JetpackDataStore
import androidx.datastore.preferences.core.Preferences
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.vnet.NodeRole
import com.ustadmobile.meshrabiya.vnet.OriginatingMessageManager
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import java.util.concurrent.ScheduledExecutorService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ScheduledFuture
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.storage.StorageDataStore
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.role.EmergentRoleManager
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader

class AndroidVirtualNode(
    emergentRoleManagerParam: EmergentRoleManager? = null,
    private val context: Context,
    port: Int = 0,
    json: Json = Json,
    logger: MNetLogger = MNetLoggerStdout(),
    private val jetpackDataStore: JetpackDataStore<Preferences>,
    address: InetAddress = randomApipaInetAddr(),
    config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
    private val scheduledExecutorService: ScheduledExecutorService,
    private val originatingMessageManager: OriginatingMessageManager,
) : VirtualNode(
    port = port,
    logger = logger,
    address = address,
    json = json,
    config = config,
) {

    private val storageDataStore: StorageDataStore = StorageDataStore.getInstance(context)

    private val coreGossipBroadcastService = CoreGossipBroadcastService(
        originatingMessageManager = originatingMessageManager,
        sendToNode = { addr, bytes -> sendToNode(addr, bytes) }
    )

    // These are injected/set externally after construction
    private var meshGossipService: MeshGossipService? = null
    private var meshEcosystemListener: MeshEcosystemListener? = null
    private var distributedStorageManager: DistributedStorageManager? = null
    private var intelligentDistributedComputeService: IntelligentDistributedComputeService? = null

    companion object {
        @Volatile
        private var instance: AndroidVirtualNode? = null

        fun getInstance(
            emergentRoleManagerParam: EmergentRoleManager? = null,
            context: Context,
            port: Int = 0,
            json: Json = Json,
            logger: MNetLogger = MNetLoggerStdout(),
            jetpackDataStore: JetpackDataStore<Preferences>,
            address: InetAddress = randomApipaInetAddr(),
            config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
            scheduledExecutorService: ScheduledExecutorService,
            originatingMessageManager: OriginatingMessageManager,
        ): AndroidVirtualNode {
            return instance ?: synchronized(this) {
                instance ?: AndroidVirtualNode(
                    emergentRoleManagerParam,
                    context,
                    port,
                    json,
                    logger,
                    jetpackDataStore,
                    address,
                    config,
                    scheduledExecutorService,
                    originatingMessageManager
                ).also { instance = it }
            }
        }

        fun resetInstance() {
            instance = null
        }
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private val newWifiConnectionListener = MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener {
        addNewNeighborConnection(
            address = it.neighborInetAddress,
            port = it.neighborPort,
            neighborNodeVirtualAddr = it.neighborVirtualAddress,
            socket = it.socket,
        )
    }

    fun getNodeId(): Int = addressAsInt

    fun getHopCountToNode(nodeAddress: Int): Int? {
        val originatorMsg = originatingMessageManager.getOriginatorMessages()[nodeAddress]
        return originatorMsg?.hopCount?.toInt()
    }

    fun sendToNode(nodeAddress: Int, data: ByteArray) {
        val header = VirtualPacketHeader(
            toAddr = nodeAddress,
            toPort = 0,
            fromAddr = addressAsInt,
            fromPort = 0,
            lastHopAddr = addressAsInt,
            hopCount = 1,
            maxHops = config.maxHops.toByte(),
            payloadSize = data.size
        )
        val packetBuffer = ByteArray(VirtualPacketHeader.HEADER_SIZE + data.size).apply {
            header.toBytes(this, 0)
            System.arraycopy(data, 0, this, VirtualPacketHeader.HEADER_SIZE, data.size)
        }
        val packet = VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = packetBuffer,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE,
            headerAlreadyInData = true
        )
        route(packet, null, null)
    }

    override val meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid = MeshrabiyaWifiManagerAndroid(
        appContext = context,
        logger = logger,
        localNodeAddr = addressAsInt,
        router = this,
        chainSocketFactory = chainSocketFactory,
        ioExecutor = connectionExecutor,
        dataStore = jetpackDataStore,
        json = json,
        onNewWifiConnectionListener = newWifiConnectionListener,
    )

    private val _bluetoothState = MutableStateFlow(MeshrabiyaBluetoothState())

    private fun updateBluetoothState() {
        try {
            val deviceName = bluetoothAdapter?.name
            if (_bluetoothState.value.deviceName != deviceName) {
                _bluetoothState.value = MeshrabiyaBluetoothState(deviceName = deviceName)
            }
        } catch (e: SecurityException) {
            safeLog(
                level = LogLevel.WARN,
                tag = "AndroidVirtualNode",
                message = "Could not get device name",
                throwable = e
            )
        }
    }

    private val bluetoothStateBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> updateBluetoothState()
                    BluetoothAdapter.STATE_OFF -> _bluetoothState.value = MeshrabiyaBluetoothState(deviceName = null)
                }
            }
        }
    }

    private val receiverRegistered = AtomicBoolean(false)

    val emergentRoleManager: EmergentRoleManager = emergentRoleManagerParam ?: EmergentRoleManager.getInstance(
        context = context,
        virtualNode = this,
        meshRoleManager = MeshRoleManager(this, context)
    )
    private var meshTrafficRouter: Any? = null

    private var currentWifiState: MeshrabiyaWifiState = MeshrabiyaWifiState()
    private var currentBluetoothState: MeshrabiyaBluetoothState = MeshrabiyaBluetoothState()
    private val _nodeState = MutableStateFlow(LocalNodeState())

    fun setMeshGossipService(service: MeshGossipService) {
        meshGossipService = service
    }

    fun setMeshEcosystemListener(listener: MeshEcosystemListener) {
        meshEcosystemListener = listener
    }

    fun setDistributedStorageManager(manager: DistributedStorageManager) {
        distributedStorageManager = manager
    }

    fun setIntelligentDistributedComputeService(service: IntelligentDistributedComputeService) {
        intelligentDistributedComputeService = service
    }

    fun getMeshGossipService(): MeshGossipService? = meshGossipService
    fun getMeshEcosystemListener(): MeshEcosystemListener? = meshEcosystemListener
    fun getDistributedStorageManager(): DistributedStorageManager? = distributedStorageManager
    fun getIntelligentDistributedComputeService(): IntelligentDistributedComputeService? = intelligentDistributedComputeService
    fun getCoreGossipBroadcastService(): CoreGossipBroadcastService = coreGossipBroadcastService

    fun initializeMeshEcosystemListener(
        meshNetworkInterface: MeshNetworkInterface,
        meshGossipService: MeshGossipService,
        connectionPoolSize: Int = MeshrabiyaConstants.getConnectionPoolSize(),
        distributedStorageManager: DistributedStorageManager,
        computeService: IntelligentDistributedComputeService,
        coreGossipBroadcastService: CoreGossipBroadcastService = this.coreGossipBroadcastService
    ) {
        meshEcosystemListener = MeshEcosystemListener(
            meshNetworkInterface,
            meshGossipService,
            coreGossipBroadcastService,
            connectionPoolSize
        )
        meshEcosystemListener?.registerStorageManager(distributedStorageManager)
        meshEcosystemListener?.registerComputeService(computeService)
    }

    fun announceService(serviceAnnouncement: ServiceAnnouncement, signedBundle: ByteArray) {
        val announcementBytes = Json.encodeToString(serviceAnnouncement).toByteArray()
        val payload = announcementBytes + signedBundle
        val neighborList: List<Int> = neighbors().map { it.first }
        for (neighbor in neighborList) {
            sendToNode(neighbor, payload)
        }
        safeLog(
            level = LogLevel.INFO,
            tag = "AndroidVirtualNode",
            message = "Service announced: ${serviceAnnouncement.serviceId} to ${neighborList.size} neighbors"
        )
    }

    fun requestServiceBundle(serviceId: String, requesterOnionAddress: String): ByteArray? {
        val serviceNodes: List<Int> = neighbors().map { it.first }
        for (nodeAddr in serviceNodes) {
            val requestPayload = buildServiceBundleRequest(serviceId, requesterOnionAddress)
            sendToNode(nodeAddr, requestPayload)
            val response = receiveServiceBundleResponse(nodeAddr, serviceId)
            if (response != null) {
                safeLog(
                    level = LogLevel.INFO,
                    tag = "AndroidVirtualNode",
                    message = "Service bundle for $serviceId received from $nodeAddr"
                )
                return response
            }
        }
        safeLog(
            level = LogLevel.WARN,
            tag = "AndroidVirtualNode",
            message = "Service bundle for $serviceId not found in mesh"
        )
        return null
    }

    private fun buildServiceBundleRequest(serviceId: String, onionAddress: String): ByteArray {
        val requestString = "REQUEST_BUNDLE:$serviceId:$onionAddress"
        return requestString.toByteArray()
    }

    private fun receiveServiceBundleResponse(nodeAddr: Int, serviceId: String): ByteArray? {
        return null
    }

    override val originatingMessageManager = OriginatingMessageManager(
        localNodeInetAddr = address,
        logger = logger,
        scheduledExecutor = scheduledExecutorService,
        nextMmcpMessageId = { nextMmcpMessageId() },
        getWifiState = { currentWifiState },
        getFitnessScore = { getCurrentFitnessScore() },
        getNodeRole = { getCurrentNodeRole() }
    )

    private val roleUpdateFuture: ScheduledFuture<*> = scheduledExecutorService.scheduleAtFixedRate(
        {
            try {
                emergentRoleManager.updateRoles()
            } catch (e: Exception) {
                safeLog(
                    level = LogLevel.WARN,
                    tag = "AndroidVirtualNode",
                    message = "Failed to update roles",
                    throwable = e
                )
            }
        },
        30_000L,
        60_000L,
        java.util.concurrent.TimeUnit.MILLISECONDS
    )

    init {
        context.registerReceiver(
            bluetoothStateBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        receiverRegistered.set(true)
        coroutineScope.launch {
            meshrabiyaWifiManager.state.combine(_bluetoothState) { wifiState, bluetoothState ->
                wifiState to bluetoothState
            }.collect { (wifiState, bluetoothState) ->
                currentWifiState = wifiState
                currentBluetoothState = bluetoothState
                val connectUri = generateConnectLink(
                    hotspot = wifiState.connectConfig,
                    bluetoothConfig = bluetoothState
                ).uri
                _nodeState.update { prev: LocalNodeState ->
                    prev.copy(
                        wifiState = wifiState,
                        bluetoothState = bluetoothState,
                        connectUri = connectUri
                    )
                }
            }
        }
    }

    private fun updateState(
        wifiState: MeshrabiyaWifiState,
        bluetoothState: MeshrabiyaBluetoothState,
        connectUri: String
    ) {
        _nodeState.update { prev: LocalNodeState ->
            prev.copy(
                wifiState = wifiState,
                bluetoothState = bluetoothState,
                connectUri = connectUri
            )
        }
    }

    override fun close() {
        super.close()
        roleUpdateFuture.cancel(false)
        if (receiverRegistered.getAndSet(false)) {
            context.unregisterReceiver(bluetoothStateBroadcastReceiver)
        }
    }

    suspend fun connectAsStation(config: WifiConnectConfig) {
        meshrabiyaWifiManager.connectToHotspot(config)
    }

    suspend fun disconnectWifiStation() {
        meshrabiyaWifiManager.disconnectStation()
    }

    override suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand,
        hotspotType: HotspotType,
    ): LocalHotspotResponse? {
        updateBluetoothState()
        return super.setWifiHotspotEnabled(enabled, preferredBand, hotspotType)
    }

    suspend fun lookupStoredBssid(ssid: String): String? {
        return meshrabiyaWifiManager.lookupStoredBssid(ssid)
    }

    fun storeBssid(ssid: String, bssid: String?) {
        safeLog(
            level = LogLevel.DEBUG,
            tag = "AndroidVirtualNode",
            message = "storeBssid: Store BSSID for $ssid : $bssid"
        )
        if (bssid != null) {
            coroutineScope.launch {
                meshrabiyaWifiManager.storeBssidForAddress(ssid, bssid)
            }
        } else {
            safeLog(
                level = LogLevel.WARN,
                tag = "AndroidVirtualNode",
                message = "storeBssid: BSSID for $ssid is NULL, can't save to avoid prompts on reconnect"
            )
        }
    }

    override fun getCurrentFitnessScore(): Int {
        return (emergentRoleManager.calculateFitnessScore() * 100).toInt()
    }

    override fun getCurrentNodeRole(): Byte {
        val roles = emergentRoleManager.getCurrentMeshRoles()
        return if (roles.isNotEmpty()) roles.first().ordinal.toByte() else MeshRole.MESH_PARTICIPANT.ordinal.toByte()
    }

    fun updateWifiState(newState: MeshrabiyaWifiState) {
        _nodeState.update { prev: LocalNodeState ->
            prev.copy(wifiState = newState)
        }
    }

    fun initializeMeshTrafficRouter(orbotService: Any?, gatewayCapabilities: Any?) {
        safeLog(
            level = LogLevel.INFO,
            tag = "AndroidVirtualNode",
            message = "MeshTrafficRouter initialization available"
        )
    }

    fun handleGatewayTraffic(packet: VirtualPacket): Boolean {
        val currentRoles = emergentRoleManager.getCurrentMeshRoles()
        val isGateway = currentRoles.any {
            it == MeshRole.CLEARNET_GATEWAY || it == MeshRole.TOR_GATEWAY
        }
        if (isGateway && isInternetDestination(packet)) {
            safeLog(
                level = LogLevel.DEBUG,
                tag = "AndroidVirtualNode",
                message = "handleGatewayTraffic: Routing packet to ${packet.header.toAddr} via gateway"
            )
            return routeViaGateway(packet)
        }
        return false
    }

    private fun isInternetDestination(packet: VirtualPacket): Boolean {
        return isInternetDestination(packet.header.toAddr)
    }

    private fun routeViaGateway(packet: VirtualPacket): Boolean {
        val startTime = System.currentTimeMillis()
        val packetSize = packet.data.size
        try {
            safeLog(
                level = LogLevel.DETAILED,
                tag = "AndroidVNode",
                message = "Starting gateway routing",
                details = mapOf(
                    "destination" to packet.header.toAddr.toString(),
                    "packetSize" to packetSize.toString(),
                    "isInternetDestination" to isInternetDestination(packet).toString()
                )
            )
            if (!isInternetDestination(packet)) {
                safeLog(
                    level = LogLevel.DETAILED,
                    tag = "AndroidVNode",
                    message = "Destination is not internet-bound, skipping gateway routing"
                )
                return false
            }
            val routerStartTime = System.currentTimeMillis()
            val meshTrafficRouter = getMeshTrafficRouter()
            val routerAcquisitionTime = System.currentTimeMillis() - routerStartTime
            if (meshTrafficRouter != null) {
                safeLog(
                    level = LogLevel.DETAILED,
                    tag = "AndroidVNode",
                    message = "MeshTrafficRouter acquired",
                    details = mapOf(
                        "acquisitionTimeMs" to routerAcquisitionTime.toString(),
                        "routerClass" to meshTrafficRouter.javaClass.simpleName
                    )
                )
                val routingStartTime = System.currentTimeMillis()
                val routeMethod = meshTrafficRouter.javaClass.getMethod("routePacket", VirtualPacket::class.java)
                routeMethod.invoke(meshTrafficRouter, packet)
                val routingTime = System.currentTimeMillis() - routingStartTime
                val totalTime = System.currentTimeMillis() - startTime
                safeLog(
                    level = LogLevel.INFO,
                    tag = "AndroidVNode",
                    message = "Successfully routed packet via gateway",
                    details = mapOf(
                        "destination" to packet.header.toAddr.toString(),
                        "packetSize" to packetSize.toString(),
                        "routingTimeMs" to routingTime.toString(),
                        "totalTimeMs" to totalTime.toString(),
                        "throughputBps" to (packetSize * 1000 / maxOf(totalTime, 1)).toString()
                    )
                )
                return true
            } else {
                val totalTime = System.currentTimeMillis() - startTime
                safeLog(
                    level = LogLevel.DETAILED,
                    tag = "AndroidVNode",
                    message = "MeshTrafficRouter not available, using fallback routing",
                    details = mapOf(
                        "destination" to packet.header.toAddr.toString(),
                        "packetSize" to packetSize.toString(),
                        "routerAcquisitionTimeMs" to routerAcquisitionTime.toString(),
                        "totalTimeMs" to totalTime.toString(),
                        "fallbackReason" to "RouterNotAvailable"
                    )
                )
                return false
            }
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            safeLog(
                level = LogLevel.ERROR,
                tag = "AndroidVNode",
                message = "Gateway routing failed with exception: ${e.message}",
                details = mapOf(
                    "destination" to packet.header.toAddr.toString(),
                    "packetSize" to packetSize.toString(),
                    "totalTimeMs" to totalTime.toString(),
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "Unknown error")
                ),
                throwable = e
            )
            return false
        }
    }

    private fun getMeshTrafficRouter(): Any? {
        val startTime = System.currentTimeMillis()
        return try {
            val possibleClasses = listOf(
                "com.ustadmobile.meshrabiya.service.mesh.MeshTrafficRouter",
                "com.ustadmobile.orbotmeshrabiyaintegration.interfaces.MeshTrafficRouter",
                "com.ustadmobile.meshrabiya.routing.MeshTrafficRouter"
            )
            for (className in possibleClasses) {
                val routerClass = try {
                    Class.forName(className)
                } catch (e: ClassNotFoundException) {
                    continue
                }
                try {
                    val instanceMethod = routerClass.getMethod("getInstance")
                    val instance = instanceMethod.invoke(null)
                    if (instance != null) {
                        val endTime = System.currentTimeMillis()
                        safeLog(
                            level = LogLevel.DETAILED,
                            tag = "AndroidVNode",
                            message = "MeshTrafficRouter acquired via reflection",
                            details = mapOf(
                                "className" to className,
                                "acquisitionTimeMs" to (endTime - startTime).toString(),
                                "method" to "getInstance"
                            )
                        )
                        return instance
                    }
                } catch (e: NoSuchMethodException) {
                    try {
                        val instanceField = routerClass.getDeclaredField("INSTANCE")
                        instanceField.isAccessible = true
                        val instance = instanceField.get(null)
                        if (instance != null) {
                            val endTime = System.currentTimeMillis()
                            safeLog(
                                level = LogLevel.DETAILED,
                                tag = "AndroidVNode",
                                message = "MeshTrafficRouter acquired via field access",
                                details = mapOf(
                                    "className" to className,
                                    "acquisitionTimeMs" to (endTime - startTime).toString(),
                                    "method" to "fieldAccess"
                                )
                            )
                            return instance
                        }
                    } catch (fieldException: Exception) {
                        safeLog(
                            level = LogLevel.DETAILED,
                            tag = "AndroidVNode",
                            message = "Field access failed for $className: ${fieldException.message}"
                        )
                    }
                }
            }
            val endTime = System.currentTimeMillis()
            safeLog(
                level = LogLevel.DETAILED,
                tag = "AndroidVNode",
                message = "MeshTrafficRouter not found in any expected location",
                details = mapOf(
                    "searchTimeMs" to (endTime - startTime).toString(),
                    "classesSearched" to possibleClasses.size.toString(),
                    "searchedClasses" to possibleClasses.joinToString(",")
                )
            )
            null
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            safeLog(
                level = LogLevel.WARN,
                tag = "AndroidVNode",
                message = "Failed to acquire MeshTrafficRouter via reflection: ${e.message}",
                details = mapOf(
                    "searchTimeMs" to (endTime - startTime).toString(),
                    "errorType" to e.javaClass.simpleName
                ),
                throwable = e
            )
            null
        }
    }

    private fun isInternetDestination(address: InetAddress): Boolean {
        return try {
            val isInternet = when {
                address.isLoopbackAddress -> false
                address.isLinkLocalAddress -> false
                address.isSiteLocalAddress -> false
                address.isMulticastAddress -> false
                else -> {
                    val addrBytes = address.address
                    !(addrBytes[0] == 10.toByte() && addrBytes[1] == 255.toByte())
                }
            }
            isInternet
        } catch (e: Exception) {
            false
        }
    }

    private fun isInternetDestination(addr: Int): Boolean {
        val addrBytes = ByteArray(4)
        addrBytes[0] = (addr shr 24).toByte()
        addrBytes[1] = (addr shr 16).toByte()
        return !(addrBytes[0] == 10.toByte() && addrBytes[1] == 255.toByte())
    }

    private fun safeLog(
        level: LogLevel,
        tag: String,
        message: String,
        details: Map<String, String>? = null,
        throwable: Throwable? = null
    ) {
        val detailMsg = details?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
        val fullMsg = if (detailMsg.isNotEmpty()) "$message | $detailMsg" else message
        try {
            logger.log(level.ordinal, "$tag: $fullMsg", throwable)
        } catch (_: Exception) { }
    }

    private val gossipListeners = mutableListOf<(Int, ByteArray) -> Unit>()

    fun addGossipListener(listener: (senderId: Int, messageBytes: ByteArray) -> Unit) {
        gossipListeners.add(listener)
    }

    fun onGossipMessageReceived(senderId: Int, messageBytes: ByteArray) {
        for (listener in gossipListeners) {
            listener(senderId, messageBytes)
        }

        // Attempt to deserialize and determine messageType (replace with your actual logic)
        val (messageType, messageObj) = try {
            deserializeMessage(messageBytes) // You must implement this method or use your existing one
        } catch (e: Exception) {
            null to null
        }

        // List of broadcast message types to handle
        val broadcastTypes = setOf("ComputeTaskRequest", "StorageNodeRequest", "OriginatorAnnouncement")
        if (messageType != null && messageType in broadcastTypes) {
            coreGossipBroadcastService.onReceiveBroadcast(messageBytes, messageType, messageObj)
        }
    }

    fun createMeshGossipService(
        virtualNode: VirtualNode,
        emergentRoleManager: EmergentRoleManager,
        scheduledExecutorService: ScheduledExecutorService
    ): MeshGossipService {
        return MeshGossipService.getInstance(
            virtualNode = virtualNode,
            meshRoleManager = emergentRoleManager.meshRoleManager,
            context = context,
            scheduledExecutorService = scheduledExecutorService,
            originatingMessageManager = originatingMessageManager,
            coreGossipBroadcastService = coreGossipBroadcastService
        )
    }
}