package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.NeighborNodeListUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.NeighborNodeListViewModel
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import org.kodein.di.compose.localDI

@Composable
fun NeighborNodeListScreen(
    viewModel: NeighborNodeListViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                NeighborNodeListViewModel(it)
            },
            defaultArgs = null,
        )
    ),
    onSetAppUiState: (AppUiState) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState(NeighborNodeListUiState())

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }


    NeighborNodeListScreen(
        uiState = uiState,
        onClickFilter = viewModel::onClickFilterChip
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeighborNodeListScreen(
    uiState: NeighborNodeListUiState,
    onClickFilter: (NeighborNodeListUiState.Companion.Filter) -> Unit = { },
) {
    LazyColumn {
        item(key = "filterchips") {
            Row(modifier = Modifier.padding(horizontal = 8.dp)){
                NeighborNodeListUiState.Companion.Filter.values().forEach { filter ->
                    FilterChip(
                        modifier = Modifier.padding(8.dp),
                        selected = uiState.filter == filter,
                        onClick = {
                            onClickFilter(filter)
                        },
                        label = {
                            Text(filter.label)
                        }
                    )
                }
            }
        }



        items(
            items = uiState.nodes.entries.toList() ,
            key = { it.key }
        ) { nodeEntry ->
            NodeListItem(nodeEntry.key, nodeEntry.value)
        }

    }

}

@Composable
fun NodeListItem(
    nodeAddr: Int,
    nodeEntry: VirtualNode.LastOriginatorMessage,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.let {
            if(onClick != null) {
                it.clickable(
                    onClick = onClick
                )
            }else {
                it
            }
        },
        headlineContent = {
            Text(nodeAddr.addressToDotNotation())
        },
        supportingContent = {
            Text("Ping ${nodeEntry.originatorMessage.pingTimeSum}ms " +
                    " Hops: ${nodeEntry.hopCount} ")
        },
    )
}
