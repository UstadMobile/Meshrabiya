package com.ustadmobile.test_app.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.NeighborNodeState
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.test_app.appstate.AppUiState
import com.ustadmobile.test_app.appstate.FabState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

data class NeighborNodeListUiState(
    val appUiState: AppUiState = AppUiState(),
    val neighborNodes: List<NeighborNodeState> = emptyList(),
)

class NeighborNodeListViewModel(
    di: DI
) : ViewModel(){

    private val _uiState = MutableStateFlow(NeighborNodeListUiState())

    val uiState: Flow<NeighborNodeListUiState> = _uiState.asStateFlow()

    private val virtualNode: AndroidVirtualNode by di.instance()

    init {
        _uiState.update { prev ->
            prev.copy(
                appUiState = prev.appUiState.copy(
                    title = "Neighbor Nodes",
                    fabState = FabState(
                        visible = true,
                        label = "Add node",
                        icon = Icons.Default.Add,
                    )
                )
            )
        }

        viewModelScope.launch {
            virtualNode.neighborNodesState.collect {
                _uiState.update { prev ->
                    prev.copy(
                        neighborNodes = it
                    )
                }
            }
        }
    }

    fun onConnectWifi(
        hotspotConfig: WifiConnectConfig
    ) {
        viewModelScope.launch {
            try {
                virtualNode.addWifiConnection(hotspotConfig)
            }catch(e: Exception) {
                e.printStackTrace()
            }

        }
    }

    fun onConnectBluetooth(
        deviceAddr: String
    ) {
        viewModelScope.launch {
            try{
                virtualNode.addBluetoothConnection(deviceAddr)
            }catch(e: Exception) {
                e.printStackTrace()
            }

        }
    }

}