package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.NeighborNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.appstate.FabState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
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

data class NeighborNodeListUiState(
    val appUiState: AppUiState = AppUiState(),
    val neighborNodes: List<NeighborNodeState> = emptyList(),
    val filter: Filter = Filter.ALL_NODES,
    val connectingInProgressSsid: String? = null,
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

        enum class Filter(val label: String) {
            ALL_NODES("All"), NEIGHBORS("Neighbors")
        }

    }
}

class NeighborNodeListViewModel(
    di: DI
) : ViewModel(){

    private val _uiState = MutableStateFlow(NeighborNodeListUiState())

    val uiState: Flow<NeighborNodeListUiState> = _uiState.asStateFlow()

    private val virtualNode: AndroidVirtualNode by di.instance()

    private val logger: MNetLogger by di.instance()

    private val _snackbars = MutableSharedFlow<SnackbarMessage>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val snackbars: Flow<SnackbarMessage> = _snackbars.asSharedFlow()

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
                        allNodes = it.originatorMessages,
                        connectingInProgressSsid =
                            if(it.wifiState.wifiStationState.status == WifiStationState.Status.CONNECTING) {
                                it.wifiState.wifiStationState.config?.ssid
                            }else {
                                null
                            },
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
                _snackbars.tryEmit(SnackbarMessage("Failed to connect: $e"))
                logger(Log.ERROR, "Failed to connect", e)
            }

        }
    }

}