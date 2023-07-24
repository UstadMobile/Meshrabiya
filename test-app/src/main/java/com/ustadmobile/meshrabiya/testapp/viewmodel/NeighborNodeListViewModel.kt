package com.ustadmobile.meshrabiya.testapp.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.NeighborNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.appstate.FabState
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
    val filter: Filter = Filter.ALL_NODES,
    internal val allNodes: Map<Int, VirtualNode.LastOriginatorMessage> = emptyMap(),
) {

    val nodes: Map<Int, VirtualNode.LastOriginatorMessage>
        get() {
            return if(filter == Filter.ALL_NODES) {
                allNodes
            }else {
                allNodes.filter { it.value.hopCount == 1.toByte() }
            }
        }

    companion object {

        enum class Filter(val id: Int, val label: String) {
            ALL_NODES(1, "All"), NEIGHBORS(2, "Neighbors")
        }

    }
}

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
            virtualNode.state.collect {
                _uiState.update { prev ->
                    prev.copy(
                        allNodes = it.originatorMessages
                    )
                }
            }
        }
    }

    fun onClickFilterChip(filter: NeighborNodeListUiState.Companion.Filter) {
        _uiState.update { prev ->
            prev.copy(
                filter = filter
            )
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