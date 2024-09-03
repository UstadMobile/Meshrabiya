package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestViewModel


@Composable
fun NearbyTestScreen(viewModel: NearbyTestViewModel) {
    val isNetworkRunning by viewModel.isNetworkRunning.collectAsState()
    val endpoints by viewModel.endpoints.collectAsState()
    val logs by viewModel.logs.collectAsState()
    var broadcastMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = {
                if (isNetworkRunning) viewModel.stopNetwork() else viewModel.startNetwork()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isNetworkRunning) "Stop Network" else "Start Network")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Endpoints:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .height(150.dp)
                .fillMaxWidth()
        ) {
            items(endpoints) { endpoint ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${endpoint.endpointId}: ${endpoint.status}")
                    if (endpoint.isOutgoing) {
                        Text("Outgoing", color = Color.Blue)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = broadcastMessage,
                onValueChange = { broadcastMessage = it },
                label = { Text("Broadcast Message") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.sendBroadcast(broadcastMessage)
                    broadcastMessage = ""
                },
                enabled = isNetworkRunning && broadcastMessage.isNotBlank()
            ) {
                Text("Send")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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