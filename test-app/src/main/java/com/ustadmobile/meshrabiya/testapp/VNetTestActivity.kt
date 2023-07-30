package com.ustadmobile.meshrabiya.testapp

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ConnectWithoutContact
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.vnet.NeighborNodeState
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.screens.LocalVirtualNodeScreen
import com.ustadmobile.meshrabiya.testapp.screens.NeighborNodeListScreen
import com.ustadmobile.meshrabiya.testapp.screens.SelectDestNodeScreen
import com.ustadmobile.meshrabiya.testapp.screens.SendFileScreen
import com.ustadmobile.meshrabiya.testapp.theme.HttpOverBluetoothTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.withDI
import java.net.URLEncoder
import java.util.UUID

data class TestActivityUiState(
    val localAddress: Int = 0,
    val remoteNodes: List<NeighborNodeState> = emptyList(),
    val localHotspotState: MeshrabiyaWifiState? = null,
    val logLines: List<LogLine> = emptyList(),
)

//TODO: show QR code e.g. https://stackoverflow.com/questions/28232116/android-using-zxing-generate-qr-code
class VNetTestActivity : ComponentActivity(), DIAware {

    override val di by closestDI()

    private val activityUiState = MutableStateFlow(TestActivityUiState())

    private val scanQrCode = registerForActivityResult(ScanQrCodeContract()) { qrCode ->
        Toast.makeText(this, qrCode, Toast.LENGTH_LONG).show()
    }

    val launchCompanionDeviceIntentSender = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(), activityResultRegistry
    ) { result ->
        if(result.resultCode == Activity.RESULT_OK) {
            val device: BluetoothDevice? = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE
            )
            println("Got device: ${device?.address}")
            onDeviceSelected(device)
        }
    }

    val launchMakeDiscoverable = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(), activityResultRegistry
    ) {
        Log.i("MainActivity", "Discoverable")
    }


    private val vNetLogger = MNetLoggerAndroid()

    fun onClickLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val clip: ClipData = ClipData.newPlainText("Logs",
            "===Mashrabiya ${activityUiState.value.localAddress}===\n" +
                activityUiState.value.logLines.joinToString(separator = ",\n") {
                    "#${it.lineId} ${it.line}"
                }
        )
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied logs!", Toast.LENGTH_LONG).show()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HttpOverBluetoothTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    MeshrabiyaTestApp(di)

                    /*
                    TestScreen(
                        uiState = activityUiState,
                        onClickMakeDiscoverable = this::onClickMakeDiscoverable,
                        onClickAddNode = this::onClickAddNode,
                        onSetLocalOnlyHotspotEnabled = this::onSetLocalOnlyHotspotEnabled,
                        onClickLogs = this::onClickLogs,
                        onClickNodeRequestWifiHotspot = this::onClickNodeRequestWifiHotspot,
                    )*/
                }
            }
        }
    }

    fun onClickMakeDiscoverable() {
        val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }

        launchMakeDiscoverable.launch(discoverableIntent)
    }

    fun onClickAddNode() {
        scanQrCode.launch(Unit)

        /*
        val deviceFilter = BluetoothDeviceFilter.Builder()
            .build()

        val associationRequest: AssociationRequest = AssociationRequest.Builder()
            // Find only devices that match this request filter.
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        val deviceManager : CompanionDeviceManager =
            getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        Log.d(LOG_TAG, "Starting associate request")
        deviceManager.associate(
            associationRequest,
            object: CompanionDeviceManager.Callback() {
                @Deprecated("Thank you Google")
                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.d(LOG_TAG, "CompanionDeviceManager.onDeviceFound")
                    launchCompanionDeviceIntentSender.launch(
                        IntentSenderRequest.Builder(intentSender)
                            .build()
                    )
                }

                override fun onFailure(p0: CharSequence?) {
                    Log.e(LOG_TAG, "CompanionDeviceManager.associate: onFailure: $p0")
                }
            },
            null
        )
         */
    }

    fun onDeviceSelected(bluetoothDevice: BluetoothDevice?) {
        val device = bluetoothDevice ?: return

        lifecycleScope.launch {
            try {
//                virtualNode.addBluetoothConnection(
//                    remoteBluetooothAddr = device.address,
//                )
            }catch(e: Exception) {
                vNetLogger(Log.ERROR, "Error adding bluetooth connection", e)
            }

        }
    }

    fun onSetLocalOnlyHotspotEnabled(enabled: Boolean) {
        if(enabled){
            lifecycleScope.launch {
                //virtualNode.sendRequestWifiConnectionMmcpMessage(virtualNode.localNodeAddress)
            }
        }
    }

    fun onClickNodeRequestWifiHotspot(nodeAddr: Int) {
        lifecycleScope.launch {
            //virtualNode.addWifiConnection(nodeAddr)
        }
    }


    companion object {

        val UUID_MASK = UUID.fromString("db803871-2136-4a7a-8859-6fdc28b567b6")

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshrabiyaTestApp(
    di: DI
) = withDI(di) {
    val navController: NavHostController = rememberNavController()
    var appUiState: AppUiState by remember {
        mutableStateOf(AppUiState())
    }

    var selectedItem: String? by remember {
        mutableStateOf(null)
    }


    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(appUiState.title)
            })
        },
        floatingActionButton = {
            if(appUiState.fabState.visible) {
                ExtendedFloatingActionButton(
                    onClick = appUiState.fabState.onClick,
                    icon = {
                        appUiState.fabState.icon?.also {
                            Icon(imageVector = it, contentDescription = null)
                        }
                    },
                    text = {
                        Text(appUiState.fabState.label ?: "")
                    }
                )
            }

        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = navController.currentDestination?.route == "localvirtualnode",
                    label = { Text("This Node") },
                    onClick = {
                        navController.navigate("localvirtualnode")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null
                        )
                    }
                )

                NavigationBarItem(
                    selected = navController.currentDestination?.route == "network" ,
                    label = { Text("Network") },
                    onClick = {
                        navController.navigate("neighbornodes")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ConnectWithoutContact,
                            contentDescription = null,
                        )
                    }
                )

                NavigationBarItem(
                    selected = selectedItem == "send" ,
                    label = { Text("Send") },
                    onClick = {
                        navController.navigate("send")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                        )
                    }
                )

                NavigationBarItem(
                    selected = selectedItem == "Receive" ,
                    label = { Text("Receive") },
                    onClick = { /*TODO*/ },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                        )
                    }
                )
            }
        }

    ) { contentPadding ->
        // Screen content
        Box(
            modifier = Modifier.padding(contentPadding)
        ) {
            AppNavHost(
                navController = navController,
                onSetAppUiState = {
                    appUiState = it
                }
            )
        }
    }
}

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = "localvirtualnode",
    onSetAppUiState: (AppUiState) -> Unit = { },
){
    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = startDestination
    ) {
        composable("localvirtualnode") {
            LocalVirtualNodeScreen(
                onSetAppUiState = onSetAppUiState,
            )
        }

        composable("neighbornodes") {
            NeighborNodeListScreen(onSetAppUiState = onSetAppUiState)
        }

        composable("send") {
            SendFileScreen(
                onNavigateToSelectReceiveNode = {uri ->
                    navController.navigate("selectdestnode/${URLEncoder.encode(uri.toString(), "UTF-8")}")
                },
                onSetAppUiState = onSetAppUiState,
            )
        }

        composable("selectdestnode/{sendFileUri}") { backStackEntry ->
            val uriToSend = backStackEntry.arguments?.getString("sendFileUri")
                ?: throw IllegalArgumentException("No uri to send")
            SelectDestNodeScreen(
                uriToSend = uriToSend,
                navigateOnDone = {
                    navController.popBackStack()
                },
                onSetAppUiState =  onSetAppUiState,
            )
        }
    }
}


@Composable
@Preview
fun TestScreenPreview() {
    TestScreen(
        uiState = TestActivityUiState()
    ) {

    }
}


@Composable
fun TestScreen(
    uiState: Flow<TestActivityUiState>,
    onClickAddNode: () -> Unit = { },
    onClickMakeDiscoverable: () -> Unit = { },
    onSetLocalOnlyHotspotEnabled: (Boolean) -> Unit = { },
    onClickLogs: () -> Unit = { },
    onClickNodeRequestWifiHotspot: (address: Int) -> Unit = { },
) {
    val uiStateVal: TestActivityUiState by uiState.collectAsState(
        TestActivityUiState()
    )

    TestScreen(
        uiState = uiStateVal,
        onClickAddNode = onClickAddNode,
        onClickMakeDiscoverable = onClickMakeDiscoverable,
        onSetLocalOnlyHotspotEnabled = onSetLocalOnlyHotspotEnabled,
        onClickLogs = onClickLogs,
        onClickNodeRequestWifiHotspot = onClickNodeRequestWifiHotspot,
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(
    uiState: TestActivityUiState,
    onClickAddNode: () -> Unit = { },
    onClickMakeDiscoverable: () -> Unit = { },
    onSetLocalOnlyHotspotEnabled: (Boolean) -> Unit = { },
    onClickLogs: () -> Unit = { },
    onClickNodeRequestWifiHotspot: (address: Int) -> Unit = { },
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ){
        item(key = "header") {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = "Mashrabiya (0.1a) - ${uiState.localAddress.addressToDotNotation()}"
            )
        }


        item(key = "hotspotswitch") {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .toggleable(
                        role = Role.Switch,
                        value = uiState.localHotspotState?.config != null,
                        onValueChange = onSetLocalOnlyHotspotEnabled,
                    )
            ) {
                Switch(checked = uiState.localHotspotState?.config != null, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text("Local hotspot enabled")
            }
        }

        item(key = "hotspotstate") {
            Text(
                style = MaterialTheme.typography.bodySmall,
                text = "Local Hotspot: ${uiState.localHotspotState?.config?.ssid}\n" +
                        "Passphrase: ${uiState.localHotspotState?.config?.passphrase}\n" +
                        "Port: ${uiState.localHotspotState?.config?.port}"
            )
        }

        item(key = "makediscoverable") {
            Button(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onClick = onClickMakeDiscoverable
            ) {
                Text("Make discoverable")
            }
        }

        item(key = "addnode") {
            Button(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onClick = onClickAddNode
            ) {
                Text("Add node")
            }
        }

        item(key = "nodeheader") {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = "Nodes"
            )
        }

        items(
            items = uiState.remoteNodes,
            key = { it.remoteAddress }
        ) { node ->
            ListItem(
                headlineContent = {
                    Text(node.remoteAddress.addressToDotNotation())
                },
                supportingContent = {
                    Column {
                        Row {
                            if(node.hasBluetoothConnection) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = "Bluetooth"
                                )
                            }

                            if(node.hasWifiConnection) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "Wifi"
                                )
                            }
                        }
                        Text("Ping: ${node.pingTime}ms Received ${node.pingsReceived}/${node.pingsSent}")
                    }

                },
                trailingContent = {
                    var expanded by remember { mutableStateOf(false) }

                    IconButton(
                        onClick = { expanded = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = {  expanded = false }
                        ) {
                            DropdownMenuItem(
                                onClick = {
                                    onClickNodeRequestWifiHotspot(node.remoteAddress)
                                },
                                text = {
                                    Text("Request Wifi Hotspot")
                                }
                            )
                        }
                    }
                }
            )
        }

        item(key = "logheader") {
            TextButton(
                onClick = onClickLogs,
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                    text = "Logs"
                )
            }
        }

        items(
            items = uiState.logLines,
            key = { it.lineId }
        ){
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                text = it.line,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

    }
}
