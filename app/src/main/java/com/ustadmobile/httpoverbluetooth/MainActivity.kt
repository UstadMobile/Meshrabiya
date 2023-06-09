package com.ustadmobile.httpoverbluetooth

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.ustadmobile.httpoverbluetooth.client.HttpOverBluetoothClient
import com.ustadmobile.httpoverbluetooth.ui.theme.HttpOverBluetoothTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.acra.ACRA
import rawhttp.core.RawHttp
import java.util.UUID
import android.text.format.DateFormat as AndroidDateFormat

data class MainUiState(
    val serverEnabled: Boolean = false,
    val serverMessage: String = "Hello Bluetooth World",
    val logLines: List<String> = emptyList(),
    val selectedServerAddr: String? = null,
    val selectedServerName: String? = null,
    val requestFrequency: Int = 10,
    val sendRequestsEnabled: Boolean = false,
    val numRequests: Int = 0,
    val numFailedRequests: Int = 0,
    val numSuccessfulRequests: Int = 0,
    val totalRequests: Int = 0,
)

class ManualReportException(message: String): Exception(message)

class MainActivity : ComponentActivity() {

    var uiState = MutableStateFlow(MainUiState())

    private val rawHttp = RawHttp()

    private val httpOverBluetothServer: MessageReplyBluetoothHttpServer by lazy {
        MessageReplyBluetoothHttpServer(
            appContext = applicationContext,
            rawHttp = rawHttp,
        ).also {
            it.message = "Hello Bluetooth World"
            it.listener = MessageReplyBluetoothHttpServer.ServerListener { fromDevice, reply ->
                onLogLine(Log.INFO, "Server: Respond to $fromDevice w/message $reply")
            }
        }
    }

    private val dateFormatter by lazy {
        AndroidDateFormat.getDateFormat(this)
    }

    private val timeFormatter by lazy {
        AndroidDateFormat.getTimeFormat(this)
    }

    private var sendRequestJob: Job? = null

    /**
     * When in client mode, this is the frequency of requests - 1 request per sendRequestFrequency seconds
     */
    private var sendRequestFrequency: Int = 10

    private val bluetothClient: HttpOverBluetoothClient by lazy {
        HttpOverBluetoothClient(
            appContext = applicationContext,
            rawHttp = rawHttp,
            onLog = { priority, message, exception ->
                onLogLine(priority,"Client: $message", exception)
            }
        )
    }

    fun onSetServerEnabled(enabled: Boolean) {
        if(enabled) {
            httpOverBluetothServer.start()
        }else {
            httpOverBluetothServer.stop()
        }

        uiState.update { prev ->
            prev.copy(serverEnabled = enabled)
        }

        if(enabled) {
            onLogLine(Log.DEBUG, "Server enabled")
        }else {
            onLogLine(Log.DEBUG,"Server disabled")
        }

    }

    private fun <T> List<T>.trimIfExceeds(numItems: Int): List<T> {
        return if(size > numItems)
            subList(0, numItems)
        else
            this
    }

    fun onClickShowSendReport() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Send report")
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.view_feedback_layout, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.feedback_edit_comment)
        builder.setView(dialogView)
        builder.setPositiveButton("Send") { dialogInterface, _ ->
            ACRA.errorReporter.handleSilentException(ManualReportException(editText.text.toString()))
            Toast.makeText(this, "Thanks, report submitted", Toast.LENGTH_LONG).show()
            dialogInterface.cancel()
        }
        builder.setNegativeButton("Cancel") { dialogInterface, i -> dialogInterface.cancel() }
        val dialog = builder.create()
        dialog.show()
    }

    fun onLogLine(priority: Int, line: String, exception: Exception? = null) {
        val date = java.util.Date()
        val timestamp = "${dateFormatter.format(date)} ${timeFormatter.format(date)}"
        when(priority) {
            Log.DEBUG -> Log.d(LOG_TAG, line, exception)
            Log.INFO -> Log.i(LOG_TAG, line, exception)
            Log.WARN -> Log.w(LOG_TAG, line, exception)
            Log.ERROR -> Log.e(LOG_TAG, line, exception)
            Log.ASSERT -> Log.wtf(LOG_TAG, line, exception)
            Log.VERBOSE -> Log.v(LOG_TAG, line, exception)
        }

        val newUiState = uiState.updateAndGet { prev ->
            prev.copy(
                logLines = buildList {
                    add("[$timestamp] $line")
                    addAll(prev.logLines.trimIfExceeds(MAX_LOG_LINES - 1))
                }
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            ACRA.errorReporter.putCustomData(
                "httpoverbluetooth-logs",
                newUiState.logLines.joinToString(separator = ",\n")
            )
        }
    }

    fun onChangeServerMessage(text: String) {
        httpOverBluetothServer.message = text
        uiState.update { prev ->
            prev.copy(serverMessage = text)
        }
    }

    val launchIntentSender = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(), activityResultRegistry
    ) { result ->
        if(result.resultCode == Activity.RESULT_OK) {
            val device: BluetoothDevice? = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE
            )
            println("Got device: ${device?.address}")
            onServerSelected(device)
        }
    }

    fun onClickSelectServer() {
        val deviceFilter = BluetoothDeviceFilter.Builder()
            .build()

        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            // Find only devices that match this request filter.
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        val deviceManager : CompanionDeviceManager =
            getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        Log.d(LOG_TAG, "Starting associate request")
        deviceManager.associate(
            pairingRequest,
            object: CompanionDeviceManager.Callback() {
                @Deprecated("Thank you Google")
                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.d(LOG_TAG, "onDeviceFound")
                    launchIntentSender.launch(
                        IntentSenderRequest.Builder(intentSender)
                        .build()
                    )
                }

                override fun onFailure(p0: CharSequence?) {
                    Log.e(LOG_TAG, "FAIL: $p0")
                }
            },
            null
        )
    }

    fun onSetSendRequestsEnabled(enabled: Boolean) {
        uiState.update { prev ->
            prev.copy(sendRequestsEnabled = enabled)
        }

        if(enabled) {
            sendRequestJob?.cancel()
            sendRequestJob = lifecycleScope.launch {
                while(coroutineContext.isActive){
                    lifecycleScope.launch { sendRequest() }
                    delay(sendRequestFrequency * 1000L)
                }
            }
        }else {
            sendRequestJob?.cancel()
            sendRequestJob = null
        }

    }

    suspend fun sendRequest() {
        val request = rawHttp.parseRequest(
            "GET /hello.txt HTTP/1.1\r\n" +
                    "Host: www.example.com\r\n"
        )
        val remoteAddress = uiState.value.selectedServerAddr ?: return

        try {
            bluetothClient.sendRequest(
                remoteAddress = remoteAddress,
                remoteUuidAllocationUuid = UUID.fromString(MessageReplyBluetoothHttpServer.SERVICE_UUID),
                remoteUuidAllocationCharacteristicUuid = UUID.fromString(
                    MessageReplyBluetoothHttpServer.CHARACTERISTIC_UUID),
                request = request
            ).use { response ->
                val strBody = response.response.body.get().decodeBodyToString(Charsets.UTF_8)
                Log.i(LOG_TAG, "Received response $strBody")
                onLogLine(Log.DEBUG,"Client: received \"$strBody\"")
                uiState.update { prev ->
                    prev.copy(
                        numRequests = prev.numRequests  + 1,
                        numSuccessfulRequests =  prev.numSuccessfulRequests + 1,
                    )
                }
            }
        }catch(e: Exception) {
            uiState.update { prev ->
                prev.copy(
                    numRequests =  prev.numRequests + 1,
                    numFailedRequests =  prev.numFailedRequests + 1,
                )
            }

            onLogLine(Log.ERROR, "Client: exception: $e")
            Log.e(LOG_TAG, "Exception sending request", e)
        }
    }

    fun onServerSelected(device: BluetoothDevice?) {
        try {
            uiState.update { prev ->
                prev.copy(
                    selectedServerAddr = device?.address,
                    selectedServerName = device?.name,
                    numFailedRequests = 0,
                    numRequests = 0,
                )
            }
        }catch(e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun onChangeClientRequestFrequency(requestFrequency: Int) {
        uiState.update { prev ->
            prev.copy(
                requestFrequency = requestFrequency,
            )
        }

        if(requestFrequency > 2) {
            sendRequestFrequency = requestFrequency
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
                        onServerSelected = this::onServerSelected,
                        onChangeServerMessage = this::onChangeServerMessage,
                        onClickSelectServer = this::onClickSelectServer,
                        onSetSendRequestsEnabled = this::onSetSendRequestsEnabled,
                        onChangeClientRequestFrequency = this::onChangeClientRequestFrequency,
                        onClickSendReport = this::onClickShowSendReport,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(LOG_TAG, "MainActivity: onDestroy")
        httpOverBluetothServer.close()

        super.onDestroy()
    }

    companion object {

        const val LOG_TAG = "HttpOverBluetoothTag"

        const val MAX_LOG_LINES = 30

    }
}

@Composable
fun MainScreen(
    uiStateFlow: Flow<MainUiState>,
    onServerSelected: (BluetoothDevice?) -> Unit = { },
    onSetServerEnabled: (Boolean) -> Unit = { },
    onChangeServerMessage: (String) -> Unit = { },
    onClickSelectServer: () -> Unit = { },
    onChangeClientRequestFrequency: (Int) -> Unit = { },
    onSetSendRequestsEnabled: (Boolean) -> Unit = { },
    onClickSendReport: () -> Unit = { },
) {
    val uiState by uiStateFlow.collectAsState(MainUiState())
    MainScreen(
        uiState = uiState,
        onServerSelected = onServerSelected,
        onSetServerEnabled =  onSetServerEnabled,
        onChangeServerMessage = onChangeServerMessage,
        onClickSelectServer = onClickSelectServer,
        onChangeClientRequestFrequency = onChangeClientRequestFrequency,
        onSetSendRequestsEnabled = onSetSendRequestsEnabled,
        onClickSendReport = onClickSendReport,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onServerSelected: (BluetoothDevice?) -> Unit = { },
    onSetServerEnabled: (Boolean) -> Unit = { },
    onChangeServerMessage: (String) -> Unit = { },
    onClickSelectServer: () -> Unit = { },
    onChangeClientRequestFrequency: (Int) -> Unit = { },
    onSetSendRequestsEnabled: (Boolean) -> Unit = { },
    onClickSendReport: () -> Unit = { },
) {

    val launchDiscoverable = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            Log.i("MainActivity", "Discoverable")
        }
    )

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Row(

        ) {
            Text(
                "HTTP Over Bluetooth 0.1",
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .weight(1.0f)
            )

            IconButton(
                onClick = onClickSendReport
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send report",
                )
            }
        }


        Text(
            text = "Server",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
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

        if(uiState.serverEnabled) {
            Button(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onClick = {
                    val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    }
                    launchDiscoverable.launch(discoverableIntent)
                }
            ) {
                Text("Make Discoverable")
            }

            OutlinedTextField(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                value = uiState.serverMessage,
                onValueChange =onChangeServerMessage,
                label = { Text("Server message") }
            )
        }

        Text(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
            text = "Client"
        )

        if(uiState.selectedServerAddr != null) {
            ListItem(
                headlineText = {
                    Text("${uiState.selectedServerName} (${uiState.selectedServerAddr})")
                },
                supportingText = {
                    val percentageSuccess = if(uiState.numRequests > 0) {
                        "(${((uiState.numSuccessfulRequests * 100)/ uiState.numRequests)}%)"
                    }else {
                        ""
                    }

                    Text("${uiState.numSuccessfulRequests}/${uiState.numRequests} " +
                            "$percentageSuccess requests successful")
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            onServerSelected(null)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                        )
                    }
                }
            )

            OutlinedTextField(
                value = uiState.requestFrequency.toString(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Request frequency (seconds)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                onValueChange = {
                    onChangeClientRequestFrequency(it.trim().toIntOrNull() ?: 0)
                }
            )

            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .toggleable(
                        role = Role.Switch,
                        value = uiState.sendRequestsEnabled,
                        onValueChange = onSetSendRequestsEnabled,
                    )
            ) {
                Switch(checked = uiState.sendRequestsEnabled, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text("Send requests")
            }

        }else {
            Button(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                onClick = onClickSelectServer,
            ) {
                Text("Select Server")
            }
        }



        Text(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
            text = "Logs"
        )

        uiState.logLines.forEach {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                text = it,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
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