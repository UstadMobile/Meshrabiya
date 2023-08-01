package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.appstate.FabState
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import java.net.InetAddress

data class SelectDestNodeUiState(
    val nodes: Map<Int, VirtualNode.LastOriginatorMessage> = emptyMap(),
    val error: String? = null,
    val sendingUri: String = "",
    val appUiState: AppUiState = AppUiState(),
)

class SelectDestNodeViewModel(
    di: DI,
    private val uriToSend: String,
    private val navigateOnDone: () -> Unit,
): ViewModel() {

    private val _uiState = MutableStateFlow(SelectDestNodeUiState())

    val uiState: Flow<SelectDestNodeUiState> = _uiState.asStateFlow()

    private val testAppServer: TestAppServer by di.instance()

    private val virtualNode: AndroidVirtualNode by di.instance()

    init {
        _uiState.update { prev ->
            prev.copy(
                sendingUri = uriToSend,
                appUiState = AppUiState(
                    title = "Select receiver",
                    fabState = FabState(visible = false),
                )
            )
        }

        viewModelScope.launch {
            virtualNode.state.collect {
                _uiState.update { prev ->
                    prev.copy(
                        nodes = it.originatorMessages
                    )
                }
            }
        }
    }

    fun onClickDest(
        destNodeAddr: Int,
    ) {
        val destInetAddr = InetAddress.getByAddress(destNodeAddr.addressToByteArray())

        viewModelScope.launch {
            val transfer = withContext(Dispatchers.IO) {
                try {
                    testAppServer.addOutgoingTransfer(
                        uri = Uri.parse(uriToSend),
                        toNode = destInetAddr,
                    )
                }catch(e: Exception) {
                    Log.e(LOG_TAG, "Exception selecting destination", e)
                    _uiState.update { prev ->
                        prev.copy(
                            error = e.toString()
                        )
                    }
                    null
                }
            }

            if(transfer != null)
                navigateOnDone()
        }
    }

}