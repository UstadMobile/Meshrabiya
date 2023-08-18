package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class AndroidVirtualNode(
    val appContext: Context,
    port: Int = 0,
    json: Json = Json,
    logger: MNetLogger = MNetLoggerStdout(),
    dataStore: DataStore<Preferences>,
    address: InetAddress = randomApipaInetAddr(),
    config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
): VirtualNode(
    port = port,
    logger = logger,
    address = address,
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
     * Listen to the WifiManager for new wifi station connections being established.. When they are
     * established call addNewNeighborConnection to initialize the exchange of originator messages.
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
        localNodeAddr = addressAsInt,
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
            _bluetoothState.takeIf { it.value.deviceName != deviceName }?.value =
                MeshrabiyaBluetoothState(deviceName = deviceName)
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



    init {
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
                            hotspot = it.first.connectConfig,
                            bluetoothConfig = it.second,
                        ).uri
                    )
                }
            }
        }
    }


    override fun close() {
        super.close()

        if(receiverRegistered.getAndSet(false)) {
            appContext.unregisterReceiver(bluetoothStateBroadcastReceiver)
        }
    }

    suspend fun connectAsStation(
        config: WifiConnectConfig,
    ) {
        meshrabiyaWifiManager.connectToHotspot(config)
    }

    suspend fun disconnectWifiStation() {
        meshrabiyaWifiManager.disconnectStation()
    }

    override suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand,
    ) : LocalHotspotResponse?{
        updateBluetoothState()
        return super.setWifiHotspotEnabled(enabled, preferredBand)
    }

    suspend fun lookupStoredBssid(ssid: String) : String? {
        return meshrabiyaWifiManager.lookupStoredBssid(ssid)
    }

    /**
     * Store the BSSID for the given SSID. This ensures that when we make subsequent connection
     * attempts we don't need to use the companiondevicemanager again. The BSSID must be provided
     * when reconnecting on Android 10+ if we want to avoid a confirmation dialog.
     */
    fun storeBssid(ssid: String, bssid: String?) {
        logger(Log.DEBUG, "$logPrefix: storeBssid: Store BSSID for $ssid : $bssid")
        if(bssid != null) {
            coroutineScope.launch {
                meshrabiyaWifiManager.storeBssidForAddress(ssid, bssid)
            }
        }else {
            logger(Log.WARN, "$logPrefix : storeBssid: BSSID for $ssid is NULL, can't save to avoid prompts on reconnect")
        }
    }

}