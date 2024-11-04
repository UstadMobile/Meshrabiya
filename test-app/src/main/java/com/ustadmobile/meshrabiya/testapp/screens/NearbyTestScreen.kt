package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.testapp.ViewModelFactory
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestUiState
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestViewModel
import org.kodein.di.compose.localDI

@Composable
fun NearbyTestRoute(
    viewModel: NearbyTestViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = {
                NearbyTestViewModel(it)
            },
            defaultArgs = null,
        )
    ),
    onSetAppUiState: (AppUiState) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.appUiState) {
        onSetAppUiState(uiState.appUiState)
    }

    NearbyTestScreen(
        uiState = uiState,
        onSendMessage = viewModel::sendMessage,
        onStartNetwork = viewModel::startNetwork,
        onStopNetwork = viewModel::stopNetwork
    )
}

@Composable
fun NearbyTestScreen(
    uiState: NearbyTestUiState,
    onSendMessage: (String) -> Unit,
    onStartNetwork: () -> Unit,
    onStopNetwork: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val logScrollState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = {
                if (uiState.isNetworkRunning) onStopNetwork() else onStartNetwork()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (uiState.isNetworkRunning) "Stop Network" else "Start Network")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Discovered Nodes
        Text(
            text = "Discovered Nodes: ${
                uiState.discoveredEndpoints.joinToString(", ") {
                    it.ipAddress?.hostAddress ?: "Unknown IP"
                }
            }",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Connected Nodes
        Text(
            text = "Connected Nodes: ${
                uiState.connectedEndpoints.joinToString(", ") {
                    it.ipAddress?.hostAddress ?: "Unknown IP"
                }
            }",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Messages section
        Text("Messages:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                .padding(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(uiState.messages) { message ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "Sender: ${message.sender}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = message.message,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Divider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                }
            }
        }

        // Message input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        onSendMessage(messageText)  // Use the passed function
                        messageText = ""
                    }
                },
                enabled = uiState.isNetworkRunning && messageText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Send")
            }
        }

        // Logs section
        Spacer(modifier = Modifier.height(16.dp))
        Text("Logs:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            state = logScrollState,
            modifier = Modifier
                .height(100.dp)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            items(uiState.logs) { log ->
                Text(log, style = MaterialTheme.typography.bodySmall)
            }
        }

        LaunchedEffect(uiState.logs.size) {
            if (uiState.logs.isNotEmpty()) {
                logScrollState.animateScrollToItem(uiState.logs.size - 1)
            }
        }
    }
}







