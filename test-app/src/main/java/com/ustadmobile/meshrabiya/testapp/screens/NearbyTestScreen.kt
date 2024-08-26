package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestViewModel

@Composable
fun NearbyTestScreen(viewModel: NearbyTestViewModel) {
    val logs by viewModel.logs.collectAsState()
    val isNetworkRunning by viewModel.isNetworkRunning.collectAsState()
    val discoveredEndpoints by viewModel.discoveredEndpoints.collectAsState()
    val connectedEndpoints by viewModel.connectedEndpoints.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Network control button
        Button(
            onClick = {
                if (isNetworkRunning) viewModel.stopNearbyNetwork()
                else viewModel.startNearbyNetwork()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isNetworkRunning) "Stop Network" else "Start Network")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Discovered Endpoints
        Text("Discovered Endpoints:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .height(120.dp)
                .fillMaxWidth()
        ) {
            items(discoveredEndpoints) { endpoint ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(endpoint, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { viewModel.connectToEndpoint(endpoint) },
                        enabled = isNetworkRunning && !connectionStatus.containsKey(endpoint)
                    ) {
                        Text("Connect")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connected Endpoints
        Text("Connected Endpoints:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .height(120.dp)
                .fillMaxWidth()
        ) {
            items(connectedEndpoints) { endpoint ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(endpoint.hostAddress, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { viewModel.sendTestMessage(endpoint.hostAddress) },
                        enabled = isNetworkRunning
                    ) {
                        Text("Send Test")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection Statuses
        Text("Connection Statuses:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .height(120.dp)
                .fillMaxWidth()
        ) {
            items(connectionStatus.toList()) { (endpointId, isConnected) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(endpointId, modifier = Modifier.weight(1f))
                    Text(
                        if (isConnected) "Connected" else "Disconnected",
                        color = if (isConnected) Color.Green else Color.Red
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Logs
        Text("Logs:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(logs) { log ->
                Text(log)
            }
        }
    }
}