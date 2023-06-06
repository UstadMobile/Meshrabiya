package com.ustadmobile.httpoverbluetooth

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ustadmobile.httpoverbluetooth.MainActivity.Companion.LOG_TAG
import com.ustadmobile.httpoverbluetooth.client.HttpOverBluetoothClient
import com.ustadmobile.httpoverbluetooth.server.AbstractHttpOverBluetoothServer
import com.ustadmobile.httpoverbluetooth.ui.theme.HttpOverBluetoothTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class MainUiState(
    val serverEnabled: Boolean = false,
)

class MainActivity : ComponentActivity() {

    var httpOverBluetothServer: AbstractHttpOverBluetoothServer? = null

    var uiState = MutableStateFlow(MainUiState())

    private val bluetothClient: HttpOverBluetoothClient by lazy {
        HttpOverBluetoothClient(applicationContext)
    }

    fun onSetServerEnabled(enabled: Boolean) {
        if(enabled) {
            httpOverBluetothServer = AbstractHttpOverBluetoothServer(
                applicationContext,
                UUID.fromString(CONTROL_UUID),
                //UUID.fromString(BLE_UUID),
            )
        }else {
            httpOverBluetothServer?.close()
            httpOverBluetothServer = null
        }

        uiState.update { prev ->
            prev.copy(serverEnabled = enabled)
        }
    }

    fun onServerSelected(device: BluetoothDevice) {
        lifecycleScope.launch {
            bluetothClient.sendRequest(
                device.address,
                UUID.fromString(CONTROL_UUID),
            )
        }
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
                    MainScreen(
                        uiStateFlow = uiState,
                        onSetServerEnabled = this::onSetServerEnabled,
                        onServerSelected = this::onServerSelected
                    )
                }
            }
        }
    }

    companion object {

        const val LOG_TAG = "HttpOverBluetoothTag"

        const val CONTROL_UUID = "066cbe21-8a51-49e0-8551-7c13d8ff6084"

        const val BLE_UUID = "066cbe21-8a51-49e0-8551-7c13d8ff7085"

    }
}

@Composable
fun MainScreen(
    uiStateFlow: Flow<MainUiState>,
    onServerSelected: (BluetoothDevice) -> Unit = { },
    onSetServerEnabled: (Boolean) -> Unit = { },
) {
    val uiState by uiStateFlow.collectAsState(MainUiState())
    MainScreen(
        uiState = uiState,
        onServerSelected = onServerSelected,
        onSetServerEnabled =  onSetServerEnabled,
    )
}

@Composable
fun MainScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onServerSelected: (BluetoothDevice) -> Unit = { },
    onSetServerEnabled: (Boolean) -> Unit = { },
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result ->
            if(result.resultCode == Activity.RESULT_OK) {
                val device: BluetoothDevice? = result.data?.getParcelableExtra(
                    CompanionDeviceManager.EXTRA_DEVICE
                )
                println("Got device: ${device?.address}")
                device?.also(onServerSelected)
            }
        }
    )

    val launchDiscoverable = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            Log.i("MainActivity", "Discoverable")
        }
    )

    Column {
        Text(
            text = "Hello!",
            modifier = modifier
        )

        Button(
            modifier = Modifier.padding(16.dp),
            onClick = {
                val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                launchDiscoverable.launch(discoverableIntent)
            }
        ) {
            Text("Make Discoverable")
        }

        Row(
            modifier = Modifier
                .padding(16.dp)
                .toggleable(
                    role = Role.Switch,
                    value = uiState.serverEnabled,
                    onValueChange = onSetServerEnabled,
                )
        ) {
            Switch(checked = uiState.serverEnabled, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text("Server Enabled")
        }

        Button(
            onClick = {
                val deviceFilter = BluetoothDeviceFilter.Builder()
                    .build()

                val pairingRequest: AssociationRequest = AssociationRequest.Builder()
                    // Find only devices that match this request filter.
                    .addDeviceFilter(deviceFilter)
                    .setSingleDevice(false)
                    .build()


                val deviceManager : CompanionDeviceManager =
                    context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
                Log.d(LOG_TAG, "Starting associate request")
                deviceManager.associate(
                    pairingRequest,
                    object: CompanionDeviceManager.Callback() {
                        @Deprecated("Thank you Google")
                        override fun onDeviceFound(intentSender: IntentSender) {
                            Log.d(LOG_TAG, "onDeviceFound")
                            launcher.launch(IntentSenderRequest.Builder(intentSender)
                                .build())
                        }

                        override fun onFailure(p0: CharSequence?) {
                            Log.e(LOG_TAG, "FAIL: $p0")
                        }
                    },
                    null
                )
            }
        ) {
            Text("Select Server")
        }
    }

}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    HttpOverBluetoothTheme {
        MainScreen(
            uiState = MainUiState(serverEnabled = true)
        )
    }
}