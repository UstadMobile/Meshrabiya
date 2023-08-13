package com.ustadmobile.meshrabiya.testapp.screens

import android.Manifest
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.min as mathmin
import com.ustadmobile.meshrabiya.testapp.viewmodel.LocalVirtualNodeViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.NEARBY_WIFI_PERMISSION_NAME
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.composable.ConnectWifiLauncherResult
import com.ustadmobile.meshrabiya.testapp.composable.ConnectWifiLauncherStatus
import com.ustadmobile.meshrabiya.testapp.composable.rememberConnectWifiLauncher
import com.ustadmobile.meshrabiya.testapp.hasBluetoothConnectPermission
import com.ustadmobile.meshrabiya.testapp.hasNearbyWifiDevicesOrLocationPermission
import com.ustadmobile.meshrabiya.testapp.viewmodel.LocalVirtualNodeUiState
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshrabiyaConnectLink
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import kotlinx.coroutines.launch
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun LocalVirtualNodeScreen(
    viewModel: LocalVirtualNodeViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                LocalVirtualNodeViewModel(it)
            },
            defaultArgs = null,
        )
    ),
    onSetAppUiState: (AppUiState) -> Unit,
    snackbarHostState: SnackbarHostState,
){
    val uiState: LocalVirtualNodeUiState by viewModel.uiState.collectAsState(
        initial = LocalVirtualNodeUiState()
    )
    val snackbar by viewModel.snackbars.collectAsState(initial = null)
    val di = localDI()
    val node: VirtualNode by di.instance()
    val logger: MNetLogger by di.instance()
    val scope = rememberCoroutineScope()


    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    LaunchedEffect(snackbar) {
        snackbar?.also {
            snackbarHostState.showSnackbar(message = it.message)
        }
    }

    val context = LocalContext.current

    val requestBluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted) {
            viewModel.onSetIncomingConnectionsEnabled(true)
        }
    }

    val requestNearbyWifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted) {
            if(context.hasBluetoothConnectPermission()) {
                viewModel.onSetIncomingConnectionsEnabled(true)
            }else if(Build.VERSION.SDK_INT >= 31) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    LocalVirtualNodeScreen(
        uiState = uiState,
        onSetIncomingConnectionsEnabled = { enabled ->
            if(enabled && !context.hasNearbyWifiDevicesOrLocationPermission()) {
                requestNearbyWifiPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
            }else if(enabled && !context.hasBluetoothConnectPermission() && Build.VERSION.SDK_INT >= 31) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }else {
                viewModel.onSetIncomingConnectionsEnabled(enabled)
            }
        },
        onSetBand = viewModel::onConnectBandChanged,
        onClickDisconnectWifiStation = viewModel::onClickDisconnectStation,
        node = node as AndroidVirtualNode,
        onConnectWifiLauncherResult = { result ->
            if(result.hotspotConfig != null) {
                viewModel.onConnectWifi(result.hotspotConfig)
            }else {
                scope.launch {
                    snackbarHostState.showSnackbar(message = "ERROR: ${result.exception?.message}")
                }
            }
        },
        logger = logger,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVirtualNodeScreen(
    uiState: LocalVirtualNodeUiState,
    logger: MNetLogger,
    node: AndroidVirtualNode,
    onSetIncomingConnectionsEnabled: (Boolean) -> Unit = { },
    onSetBand: (ConnectBand) -> Unit = { },
    onConnectWifiLauncherResult: (ConnectWifiLauncherResult) -> Unit,
    onClickDisconnectWifiStation: () -> Unit = { },
    snackbarHostState: SnackbarHostState,
){
    val barcodeEncoder = remember {
        BarcodeEncoder()
    }

    var connectLauncherState by remember {
        mutableStateOf(ConnectWifiLauncherStatus.INACTIVE)
    }

    val connectLauncher = rememberConnectWifiLauncher(
        logger = logger,
        onStatusChange = {
            connectLauncherState = it
        },
        node = node,
        onResult = onConnectWifiLauncherResult,
    )

    val scope = rememberCoroutineScope()
    val di = localDI()

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            ListItem(
                headlineContent = {
                    Text(
                        text = uiState.localAddress.addressToDotNotation(),
                    )
                },
                supportingContent = {
                    Column {
                        Text("Virtual mesh address")

                        Row {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                "This is your virtual mesh address. It can be used to reach " +
                                        "this node from any other node connected the mesh, even if they" +
                                        " are not connected directly e.g. when Device A is connected to " +
                                        "Device B, and Device B is connected to Device C, Device A and " +
                                        "C can reach each other via the virtual mesh address."
                            )

                        }
                    }
                },
            )
        }

        item(key = "hotspotheader") {
            ListItem(
                headlineContent = {
                    Text("Hotspot")
                },
            )
        }

        if(uiState.connectBandVisible) {
            item(key = "band") {
                var expanded: Boolean by remember {
                    mutableStateOf(false)
                }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    onExpandedChange = {
                        expanded = !expanded
                    }
                ) {
                    OutlinedTextField(
                        value = uiState.band.toString(),
                        readOnly = true,
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        label = {
                            Text("Band")
                        },
                        onValueChange = { },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = expanded
                            )
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                        }
                    ) {
                        uiState.bandOptions.forEach {
                            DropdownMenuItem(
                                text = { Text(it.toString()) },
                                onClick = {
                                    expanded = false
                                    onSetBand(it)
                                }
                            )
                        }
                    }
                }
            }
        }

        item(key = "hotspotswitch") {
            Column {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .toggleable(
                            role = Role.Switch,
                            value = uiState.incomingConnectionsEnabled,
                            onValueChange = {
                                onSetIncomingConnectionsEnabled(it)
                            },
                        )
                ) {
                    Switch(checked = uiState.incomingConnectionsEnabled, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Hotspot enabled")
                }

                ListItem(
                    headlineContent = {

                    },
                    supportingContent = {
                        Row {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text("This creates a hotspot that other devices can use to connect to " +
                                    "this device as a WiFi station (client). It will not share mobile Internet. " +
                                    "Any device can operate as a WiFi hotspot and station (client) " +
                                    "simultaneously.")
                        }
                    }
                )
            }
        }

        val connectUri = uiState.connectUri
        if(connectUri != null && uiState.incomingConnectionsEnabled) {
            item("qrcode") {
                val config = LocalConfiguration.current
                val screenWidth = config.screenWidthDp
                val screenWidthPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    screenWidth.toFloat(),
                    Resources.getSystem().displayMetrics,
                )
                val width = mathmin(screenWidthPx.toInt(), 900)

                val qrCodeBitmap = remember(connectUri) {
                    barcodeEncoder.encodeBitmap(
                        connectUri, BarcodeFormat.QR_CODE, width, width
                    ).asImageBitmap()
                }

                Image(
                    bitmap = qrCodeBitmap,
                    contentDescription = null
                )
            }
        }

        if(uiState.incomingConnectionsEnabled) {
            item(key = "hotspotstate") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    text = "SSID: ${uiState.wifiState?.config?.ssid} (${uiState.wifiState?.config?.band})\n" +
                            "Passphrase: ${uiState.wifiState?.config?.passphrase}\n" +
                            "Port: ${uiState.wifiState?.config?.port}\n"
                )
            }
        }

        item(key = "stationheader") {
            ListItem(
                headlineContent = {
                    Text("Wifi station (client) connection")
                },
            )
        }

        val stationState = uiState.wifiState?.wifiStationState
        if(stationState != null) {
            item(key = "stationstate") {
                if(stationState.status == WifiStationState.Status.INACTIVE) {
                    OutlinedButton(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        onClick = {
                            qrCodeScannerLauncher.launch(ScanOptions().apply {
                                setOrientationLocked(false)
                                setPrompt("Enable the hotspot in the Meshrabiya app on the device " +
                                        "you want to connect to, then scan the QR code."
                                )
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            })
                        },
                    ) {
                        Text("Connect via QR Code Scan")
                    }
                }else {
                    ListItem(
                        headlineContent = {
                            Text(stationState.config?.ssid ?: "(Unknown SSID)")
                        },
                        supportingContent = {
                            Text(
                                (stationState.config?.nodeVirtualAddr?.addressToDotNotation() ?: "") +
                                        " - ${stationState.status}"
                            )
                        },
                        leadingContent = {
                            if(stationState.status == WifiStationState.Status.CONNECTING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp)
                                )
                            }else {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "",
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    onClickDisconnectWifiStation()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Disconnect",
                                )
                            }
                        }
                    )
                }
            }
        }

        if(connectLauncherState != ConnectWifiLauncherStatus.INACTIVE) {
            item("connectlauncherstatus") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp)
                    )

                    Text(
                        modifier = Modifier.padding(8.dp),
                        text = connectLauncherState.toString(),
                    )
                }
            }
        }

        item(key="stationinfo") {
            ListItem(
                headlineContent = {

                },
                supportingContent = {
                    Row {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            "Connects to the WiFi direct hotspot of another device as a WiFi " +
                                    "station (client). If you are connected to a 'normal' WiFi access " +
                                    "point, this will temporarily replace your normal WiFi connection"
                        )
                    }
                }
            )
        }
    }

}
