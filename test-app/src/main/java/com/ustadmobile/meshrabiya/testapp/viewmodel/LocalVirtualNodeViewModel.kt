package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.WifiDirectError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

data class LocalVirtualNodeUiState(
    val localAddress: Int = 0,
    val bandOptions: List<ConnectBand> = listOf(ConnectBand.BAND_2GHZ),
    val hotspotTypeOptions: List<HotspotType> = listOf(HotspotType.AUTO,
        HotspotType.WIFIDIRECT_GROUP, HotspotType.LOCALONLY_HOTSPOT),
    val band: ConnectBand = bandOptions.first(),
    val hotspotTypeToCreate: HotspotType = hotspotTypeOptions.first(),
    val wifiState: MeshrabiyaWifiState? = null,
    val bluetoothState: MeshrabiyaBluetoothState? = null,
    val connectUri: String? = null,
    val appUiState: AppUiState = AppUiState(),
){
    val incomingConnectionsEnabled: Boolean
        get() = wifiState?.connectConfig != null

    val connectBandVisible: Boolean
        get() = Build.VERSION.SDK_INT >= 29 && wifiState?.connectConfig == null
}

class LocalVirtualNodeViewModel(
    di: DI
) : ViewModel(){

    private val _uiState = MutableStateFlow(LocalVirtualNodeUiState())

    val uiState: Flow<LocalVirtualNodeUiState> = _uiState.asStateFlow()

    private val _snackbars = MutableSharedFlow<SnackbarMessage>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val snackbars: Flow<SnackbarMessage> = _snackbars.asSharedFlow()

    private val node: AndroidVirtualNode by di.instance()

    private val logger: MNetLogger by di.instance()

    init {
        viewModelScope.launch {
            node.state.collect {
                _uiState.update { prev ->
                    prev.copy(
                        localAddress = it.address,
                        wifiState = it.wifiState,
                        bluetoothState = it.bluetoothState,
                        connectUri = it.connectUri,
                        appUiState = AppUiState(
                            title = "This node"
                        )
                    )
                }
            }
        }

        if(node.meshrabiyaWifiManager.is5GhzSupported) {
            _uiState.update { prev ->
                prev.copy(
                    bandOptions = listOf(ConnectBand.BAND_5GHZ, ConnectBand.BAND_2GHZ),
                    band = ConnectBand.BAND_5GHZ,
                )
            }
        }

    }

    fun onConnectBandChanged(band: ConnectBand) {
        logger(Log.DEBUG, "Click: Set band to $band")
        _uiState.update { prev ->
            prev.copy(
                band = band,
            )
        }
    }

    fun onSetHotspotTypeToCreate(hotspotType: HotspotType) {
        logger(Log.DEBUG, "Click: HotspotType to $hotspotType")
        _uiState.update { prev ->
            prev.copy(
                hotspotTypeToCreate = hotspotType
            )
        }
    }

    fun onSetIncomingConnectionsEnabled(enabled: Boolean) {
        val startStopStr = if(enabled) "Start" else "Stop"
        logger(Log.DEBUG, "Click: $startStopStr Hotspot")
        viewModelScope.launch {
            val response = node.setWifiHotspotEnabled(
                enabled = enabled,
                preferredBand = _uiState.value.band,
                hotspotType = _uiState.value.hotspotTypeToCreate,
            )
            if(response != null && response.errorCode != 0) {
                val errorStr = WifiDirectError.errorString(response.errorCode)
                _snackbars.tryEmit(SnackbarMessage("ERROR enabling incoming connections: $errorStr"))
            }
        }
    }


    fun onConnectWifi(
        hotspotConfig: WifiConnectConfig
    ) {
        viewModelScope.launch {
            try {
                node.connectAsStation(hotspotConfig)
            }catch(e: Exception) {
                _snackbars.tryEmit(SnackbarMessage("Failed to connect: $e"))
                logger(Log.ERROR, "Failed to connect", e)
            }

        }
    }

    fun onClickDisconnectStation() {
        logger(Log.DEBUG, "Click: Disconnect station")
        viewModelScope.launch {
            node.disconnectWifiStation()
        }
    }


}