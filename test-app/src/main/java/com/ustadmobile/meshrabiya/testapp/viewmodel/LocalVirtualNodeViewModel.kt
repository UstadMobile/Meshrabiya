package com.ustadmobile.meshrabiya.testapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
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
    val wifiState: MeshrabiyaWifiState? = null,
    val bluetoothState: MeshrabiyaBluetoothState? = null,
    val connectUri: String? = null,
    val appUiState: AppUiState = AppUiState(),
){
    val incomingConnectionsEnabled: Boolean
        get() = wifiState?.config != null
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

    }

    fun onSetIncomingConnectionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val response = node.setWifiHotspotEnabled(enabled)
            if(response != null && response.errorCode != 0) {
                val errorStr = WifiDirectError.errorString(response.errorCode)
                _snackbars.tryEmit(SnackbarMessage("ERROR enabling incoming connections: $errorStr"))
            }
        }
    }

}