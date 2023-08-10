package com.ustadmobile.meshrabiya.testapp.screens

import android.Manifest
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
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
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.testapp.NEARBY_WIFI_PERMISSION_NAME
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.hasBluetoothConnectPermission
import com.ustadmobile.meshrabiya.testapp.hasNearbyWifiDevicesOrLocationPermission
import com.ustadmobile.meshrabiya.testapp.viewmodel.LocalVirtualNodeUiState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import org.kodein.di.compose.localDI

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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVirtualNodeScreen(
    uiState: LocalVirtualNodeUiState,
    onSetIncomingConnectionsEnabled: (Boolean) -> Unit = { },
    onSetBand: (ConnectBand) -> Unit = { },
){

    val barcodeEncoder = remember {
        BarcodeEncoder()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Text(
                text = uiState.localAddress.addressToDotNotation(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
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
                Text("Allow incoming connections")
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

        item(key = "hotspotstate") {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                text = "Wifi Role ${uiState.wifiState?.wifiRole}\n" +
                        "Local Hotspot: ${uiState.wifiState?.config?.ssid}\n" +
                        "Band: ${uiState.wifiState?.config?.band}\n" +
                        "Passphrase: ${uiState.wifiState?.config?.passphrase}\n" +
                        "Port: ${uiState.wifiState?.config?.port}\n" +
                        "LocalLinkAddr: ${uiState.wifiState?.config?.linkLocalAddr}\n" +
                        "Bluetooth Name: ${uiState.bluetoothState?.deviceName}"
            )
        }
    }

}
