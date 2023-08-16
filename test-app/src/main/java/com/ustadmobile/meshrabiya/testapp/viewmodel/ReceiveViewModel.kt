package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.MeshrabiyaConstants.LOG_TAG
import com.ustadmobile.meshrabiya.testapp.App
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.appstate.FabState
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File

data class ReceiveUiState(
    val incomingTransfers: List<TestAppServer.IncomingTransfer> = emptyList(),
    val appUiState: AppUiState = AppUiState(),
)

class ReceiveViewModel(
    private val di: DI
): ViewModel() {

    private val testAppServer: TestAppServer by di.instance()

    private val receiveDir: File by di.instance(tag = App.TAG_RECEIVE_DIR)

    private val _uiState = MutableStateFlow(ReceiveUiState())

    val uiState: Flow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {prev ->
            prev.copy(
                appUiState = AppUiState(
                    title = "Receive",
                    fabState = FabState(visible = false),
                )
            )
        }

        viewModelScope.launch {
            testAppServer.incomingTransfers.collect {
                _uiState.update { prev ->
                    prev.copy(
                        incomingTransfers = it
                    )
                }
            }
        }
    }

    fun onClickAcceptIncomingTransfer(
        transfer: TestAppServer.IncomingTransfer
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                receiveDir.takeIf { !it.exists() }?.mkdirs()
                val destFile = File(receiveDir, transfer.name)
                testAppServer.acceptIncomingTransfer(transfer, destFile)
                Log.i(LOG_TAG, "Received!! ${transfer.name} = ${destFile.length()} bytes")

            }
        }
    }

    fun onClickDeclineIncomingTransfer(
        transfer: TestAppServer.IncomingTransfer
    ) {
        viewModelScope.launch {
            testAppServer.onDeclineIncomingTransfer(transfer)
        }
    }

    fun onClickDeleteTransfer(
        transfer: TestAppServer.IncomingTransfer
    ) {
        viewModelScope.launch {
            testAppServer.onDeleteIncomingTransfer(transfer)
        }
    }


}