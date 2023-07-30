package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.SelectDestNodeUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.SelectDestNodeViewModel
import org.kodein.di.compose.localDI

@Composable
fun SelectDestNodeScreen(
    uriToSend: String,
    onSetAppUiState: (AppUiState) -> Unit,
    navigateOnDone: () -> Unit,
    viewModel: SelectDestNodeViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                SelectDestNodeViewModel(it, uriToSend, navigateOnDone)
            },
            defaultArgs = null,
        )
    )
){
    val uiState by viewModel.uiState.collectAsState(SelectDestNodeUiState())
    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }
    SelectDestNodeScreen(
        uiState = uiState,
        onClickNode = viewModel::onClickDest,
    )
}

@Composable
fun SelectDestNodeScreen(
    uiState: SelectDestNodeUiState,
    onClickNode: (Int) -> Unit,
) {
    LazyColumn {
        items(
            items = uiState.nodes.entries.toList(),
            key = { it.key }
        ){ nodeEntry ->
            NodeListItem(
                nodeAddr = nodeEntry.key,
                nodeEntry =  nodeEntry.value,
                onClick = {
                    onClickNode(nodeEntry.key)
                }
            )
        }
    }

}


