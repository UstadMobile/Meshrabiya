package com.ustadmobile.meshrabiya.testapp.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.appstate.FabState
import com.ustadmobile.meshrabiya.testapp.viewmodel.SendFileUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.SendFileViewModel
import org.kodein.di.compose.localDI

@Composable
fun SendFileScreen(
    uiState: SendFileUiState,
){
    LazyColumn {
        items(
            items = uiState.pendingTransfers,
            key = { it.id }
        ) {transfer ->
            ListItem(
                headlineContent = {
                    Text("${transfer.name} -> ${transfer.toHost.hostAddress}")
                }
            )
        }
    }
}

@Composable
fun SendFileScreen(
    onNavigateToSelectReceiveNode: (Uri) -> Unit,
    onSetAppUiState: (AppUiState) -> Unit,
    viewModel: SendFileViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                SendFileViewModel(it, onNavigateToSelectReceiveNode)
            },
            defaultArgs = null,
        )
    ),

    ) {
    val uiState: SendFileUiState by viewModel.uiState.collectAsState(SendFileUiState())

    val launcherPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.onSelectFileToSend(uri)
    }

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState.copy(
            fabState = FabState(
                visible = true,
                label = "Send File",
                icon = Icons.Default.Send,
                onClick = {
                    launcherPicker.launch(arrayOf("*/*"))
                }
            )
        ))
    }

    SendFileScreen(
        uiState = uiState,
    )

}
