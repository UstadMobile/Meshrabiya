package com.ustadmobile.test_app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.trimIfExceeds
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.NeighborNodeState
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiState
import com.ustadmobile.test_app.screens.LocalVirtualNodeScreen
import com.ustadmobile.test_app.ui.theme.HttpOverBluetoothTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.withDI
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

    private lateinit var virtualNode: AndroidVirtualNode

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


    private val vNetLogger = com.ustadmobile.meshrabiya.MNetLogger { priority, message, exception ->
        when (priority) {
            Log.DEBUG -> Log.d(LOG_TAG, message, exception)
            Log.INFO -> Log.i(LOG_TAG, message, exception)
            Log.WARN -> Log.w(LOG_TAG, message, exception)
            Log.ERROR -> Log.e(LOG_TAG, message, exception)
            Log.ASSERT -> Log.wtf(LOG_TAG, message, exception)
            Log.VERBOSE -> Log.v(LOG_TAG, message, exception)
        }

        val logDisplay = buildString {
            append(message)
            if (exception != null) {
                append(" Exception: ")
                append(exception.toString())
            }
        }

        activityUiState.update { prev ->
            prev.copy(
                logLines = buildList {
                    add(LogLine(logDisplay))
                    addAll(prev.logLines.trimIfExceeds(100))
                }
            )
        }
    }



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

        virtualNode = AndroidVirtualNode(
            appContext = applicationContext,
            uuidMask = UUID_MASK,
            logger = vNetLogger,
        )
        activityUiState.update { prev ->
            prev.copy(localAddress = virtualNode.localNodeAddress)
        }

        lifecycleScope.launch {
            virtualNode.neighborNodesState.collect {
                activityUiState.update { prev ->
                    prev.copy(remoteNodes = it)
                }
            }
        }

        lifecycleScope.launch {
            virtualNode.localHotSpotState.collect { hotspotState ->
                activityUiState.update { prev ->
                    prev.copy(localHotspotState = hotspotState)
                }
            }
        }


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
                virtualNode.addBluetoothConnection(
                    remoteBluetooothAddr = device.address,
                )
            }catch(e: Exception) {
                vNetLogger(Log.ERROR, "Error adding bluetooth connection", e)
            }

        }
    }

    fun onSetLocalOnlyHotspotEnabled(enabled: Boolean) {
        if(enabled){
            lifecycleScope.launch {
                virtualNode.sendRequestWifiConnectionMmcpMessage(virtualNode.localNodeAddress)
            }
        }
    }

    fun onClickNodeRequestWifiHotspot(nodeAddr: Int) {
        lifecycleScope.launch {
            virtualNode.addWifiConnection(nodeAddr)
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
    var selectedItem: String? by remember {
        mutableStateOf(null)
    }


    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text("Meshrabiya")
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedItem == "localvirtualnode",
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
                    selected = selectedItem == "neighbornodes" ,
                    label = { Text("Neighbor Nodes") },
                    onClick = { /*TODO*/ },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ConnectWithoutContact,
                            contentDescription = null,
                        )
                    }
                )

                NavigationBarItem(
                    selected = selectedItem == "mesh" ,
                    label = { Text("Mesh") },
                    onClick = { /*TODO*/ },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Radar,
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
            )
        }
    }
}

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = "localvirtualnode",
){
    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = startDestination
    ) {
        composable("localvirtualnode") {
            LocalVirtualNodeScreen()
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
                headlineText = {
                    Text(node.remoteAddress.addressToDotNotation())
                },
                supportingText = {
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
