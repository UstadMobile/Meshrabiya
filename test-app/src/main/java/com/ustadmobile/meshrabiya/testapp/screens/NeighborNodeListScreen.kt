package com.ustadmobile.meshrabiya.testapp.screens

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.MeshrabiyaConnectLink
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.composable.ConnectWifiLauncherStatus
import com.ustadmobile.meshrabiya.testapp.composable.rememberConnectWifiLauncher
import com.ustadmobile.meshrabiya.testapp.viewmodel.NeighborNodeListUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.NeighborNodeListViewModel
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import kotlinx.coroutines.launch
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

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
    snackbarHostState: SnackbarHostState,
) {
    val uiState by viewModel.uiState.collectAsState(NeighborNodeListUiState())
    val snackbar by viewModel.snackbars.collectAsState(initial = null)
    val di = localDI()
    val logger : MNetLogger by di.instance()
    val scope = rememberCoroutineScope()
    val node: VirtualNode by di.instance()
    var connectLauncherState by remember {
        mutableStateOf(ConnectWifiLauncherStatus.INACTIVE)
    }

    val connectLauncher = rememberConnectWifiLauncher(
        logger = logger,
        onStatusChange = {
            connectLauncherState = it
        },
        node = node as AndroidVirtualNode,
    ) { result ->
        if(result.hotspotConfig != null) {
            viewModel.onConnectWifi(result.hotspotConfig)
        }else {
            scope.launch {
                snackbarHostState.showSnackbar(message = "ERROR: ${result.exception?.message}")
            }
        }
    }

    val qrCodeScannerLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        val link = result.contents
        if(link != null) {
            try {
                val connectLink = MeshrabiyaConnectLink.parseUri(
                    uri = link,
                    json = di.direct.instance(),
                )
                val hotspotConfigVal = connectLink.hotspotConfig
                if(hotspotConfigVal != null) {
                    connectLauncher.launch(hotspotConfigVal)
                }else {
                    scope.launch {
                        snackbarHostState.showSnackbar("ERROR: link does not have wificonfig")
                    }
                }
            }catch(e: Exception) {
                Log.e("TestApp", "Exception", e)
            }
        }

    }

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted) {
            qrCodeScannerLauncher.launch(ScanOptions().apply {
                setOrientationLocked(false)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            })
        }
    }

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(
            uiState.appUiState.copy(
                fabState = uiState.appUiState.fabState.copy(
                    onClick = {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                )
            )
        )
    }

    LaunchedEffect(snackbar) {
        val snackbarVal = snackbar
        if(snackbarVal != null) {
            snackbarHostState.showSnackbar(
                message = snackbarVal.message
            )
        }
    }

    NeighborNodeListScreen(
        uiState = uiState,
        connectLauncherState = connectLauncherState,
        onClickFilter = viewModel::onClickFilterChip
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeighborNodeListScreen(
    uiState: NeighborNodeListUiState,
    connectLauncherState: ConnectWifiLauncherStatus,
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

        if(connectLauncherState != ConnectWifiLauncherStatus.INACTIVE || uiState.connectingInProgressSsid != null) {
            item("connectlauncherstatus") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp)
                    )

                    val statusText = if(connectLauncherState != ConnectWifiLauncherStatus.INACTIVE) {
                        connectLauncherState.toString()
                    }else {
                        "Connecting to ${uiState.connectingInProgressSsid}"
                    }

                    Text(
                        modifier = Modifier.padding(8.dp),
                        text = statusText,
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
