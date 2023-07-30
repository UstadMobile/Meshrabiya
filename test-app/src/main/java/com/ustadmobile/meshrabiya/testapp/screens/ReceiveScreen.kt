package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import com.ustadmobile.meshrabiya.testapp.viewmodel.ReceiveUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.ReceiveViewModel
import org.kodein.di.compose.localDI

@Composable
fun ReceiveScreen(
    onSetAppUiState: (AppUiState) -> Unit,
    viewModel: ReceiveViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                ReceiveViewModel(it)
            },
            defaultArgs = null,
        )
    ),
) {
    val uiState by viewModel.uiState.collectAsState(ReceiveUiState())

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    ReceiveScreen(
        uiState = uiState,
        onClickAccept = viewModel::onClickAcceptIncomingTransfer,
    )
}

@Composable
fun ReceiveScreen(
    uiState: ReceiveUiState,
    onClickAccept: (TestAppServer.IncomingTransfer) -> Unit =  { },
) {
    LazyColumn {
        items(
            items = uiState.incomingTransfers,
            key = { Pair(it.fromHost, it.id) }
        ) { transfer ->
            ListItem(
                headlineContent = {
                    Text(transfer.name)
                },
                supportingContent = {
                    Text("From ${transfer.fromHost.hostAddress}")
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            onClickAccept(transfer)
                        }
                    ) {
                        Icon(
                            imageVector  = Icons.Default.Check,
                            contentDescription = "Accept",
                        )
                    }
                }
            )
        }
    }
}
