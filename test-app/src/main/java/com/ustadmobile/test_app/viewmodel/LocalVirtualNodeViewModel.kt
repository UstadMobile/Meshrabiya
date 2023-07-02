package com.ustadmobile.test_app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.test_app.appstate.AppUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

data class LocalVirtualNodeUiState(
    val localAddress: Int = 0,
    val wifiState: MeshrabiyaWifiState? = null,
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

    private val node: AndroidVirtualNode by di.instance()

    init {
        viewModelScope.launch {
            node.state.collect {
                _uiState.update { prev ->
                    prev.copy(
                        localAddress = it.address,
                        wifiState = it.wifiState,
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
            node.setWifiHotspotEnabled(enabled)
        }
    }

}