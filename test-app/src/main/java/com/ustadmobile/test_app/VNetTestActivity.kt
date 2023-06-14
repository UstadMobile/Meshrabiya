package com.ustadmobile.test_app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.httpoverbluetooth.MNetLogger
import com.ustadmobile.httpoverbluetooth.ext.addressToDotNotation
import com.ustadmobile.httpoverbluetooth.vnet.MNode
import com.ustadmobile.httpoverbluetooth.vnet.RemoteMNodeState
import com.ustadmobile.test_app.ui.theme.HttpOverBluetoothTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class TestActivityUiState(
    val localAddress: Int = 0,
    val remoteNodes: List<RemoteMNodeState> = emptyList()
)

class VNetTestActivity : ComponentActivity() {


    private lateinit var mNetNode: MNode

    private val activityUiState = MutableStateFlow<TestActivityUiState>(TestActivityUiState())

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


    private val vNetLogger = MNetLogger { priority, message, exception ->
        when(priority) {
            Log.DEBUG -> Log.d(LOG_TAG, message, exception)
            Log.INFO -> Log.i(LOG_TAG, message, exception)
            Log.WARN -> Log.w(LOG_TAG, message, exception)
            Log.ERROR -> Log.e(LOG_TAG, message, exception)
            Log.ASSERT -> Log.wtf(LOG_TAG, message, exception)
            Log.VERBOSE -> Log.v(LOG_TAG, message, exception)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mNetNode = MNode(
            appContext = applicationContext,
            allocationServiceUuid = SERVICE_UUID,
            allocationCharacteristicUuid = CHARACTERISTIC_UUID,
            logger = vNetLogger,
        )
        activityUiState.update { prev ->
            prev.copy(localAddress = mNetNode.localMNodeAddress)
        }

        lifecycleScope.launch {
            mNetNode.remoteNodeStates.collect {
                activityUiState.update { prev ->
                    prev.copy(remoteNodes = it)
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
                    TestScreen(
                        uiState = activityUiState,
                        onClickMakeDiscoverable = this::onClickMakeDiscoverable,
                        onClickAddNode = this::onClickAddNode
                    )
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
    }

    fun onDeviceSelected(bluetoothDevice: BluetoothDevice?) {
        val device = bluetoothDevice ?: return

        lifecycleScope.launch {
            try {
                mNetNode.addBluetoothConnection(
                    remoteBluetooothAddr = device.address,
                    remoteAllocationServiceUuid = SERVICE_UUID,
                    remoteAllocationCharacteristicUuid = CHARACTERISTIC_UUID,
                )
            }catch(e: Exception) {
                vNetLogger(Log.ERROR, "Error adding bluetooth connection", e)
            }

        }
    }

    companion object {

        val SERVICE_UUID = UUID.fromString("db803871-2136-4a7a-8859-6fdc28b567b6")

        val CHARACTERISTIC_UUID = UUID.fromString("d4a16cbe-4113-4098-bf0c-bc7d10ab2fdf")
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
) {
    val uiStateVal: TestActivityUiState by uiState.collectAsState(
        TestActivityUiState()
    )

    TestScreen(
        uiState = uiStateVal,
        onClickAddNode = onClickAddNode,
        onClickMakeDiscoverable = onClickMakeDiscoverable,
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(
    uiState: TestActivityUiState,
    onClickAddNode: () -> Unit = { },
    onClickMakeDiscoverable: () -> Unit = { },
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ){
        item(key = "header") {
            Text(
                modifier = Modifier.padding(),
                text = "Mashrabiya - ${uiState.localAddress.addressToDotNotation()}"
            )
        }

        item(key = "makediscoverable") {
            Button(onClick = onClickMakeDiscoverable) {
                Text("Make discoverable")
            }
        }

        item(key = "addnode") {
            Button(
                onClick = onClickAddNode
            ) {
                Text("Add node")
            }
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
                    Text("Ping: ${node.pingTime}ms")
                },
            )
        }
    }
}
