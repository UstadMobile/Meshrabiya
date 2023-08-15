package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import java.net.InetAddress


data class SendFileUiState(
    val pendingTransfers: List<TestAppServer.OutgoingTransfer> = emptyList(),
    val appUiState: AppUiState = AppUiState(),
)

//Screen is essentially a list of pending transfers with a FAB to send a file. Clicking the fab triggers
//the file selector, then selecting a recipient.
class SendFileViewModel(
    di: DI,
    private val onNavigateToSelectReceiveNode: (Uri) -> Unit,
) : ViewModel(){

    private val _uiState = MutableStateFlow(SendFileUiState())

    val uiState: Flow<SendFileUiState> = _uiState.asStateFlow()

    private val testAppServer: TestAppServer by di.instance()

    init {
        _uiState.update { prev ->
            prev.copy(
                appUiState = AppUiState(
                    title = "Send"
                )
            )
        }

        viewModelScope.launch {
            testAppServer.outgoingTransfers.collect {
                _uiState.update { prev ->
                    prev.copy(
                        pendingTransfers = it,
                    )
                }
            }
        }
    }

    fun onSelectFileToSend(
        uri: Uri?,
    ) {
        if(uri == null)
            return

        onNavigateToSelectReceiveNode(uri)
    }

}