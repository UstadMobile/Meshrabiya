package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestViewModel

@Composable
fun NearbyTestScreen(viewModel: NearbyTestViewModel) {
    val isNetworkRunning by viewModel.isNetworkRunning.collectAsState()
    val connectedEndpoints by viewModel.connectedEndpoints.collectAsState()
    val messages by viewModel.messages.collectAsState()
    var messageText by remember { mutableStateOf("") }


    Column(modifier = Modifier.padding(16.dp)) {
        Text("Nearby Connections", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.startNetwork() },
            enabled = !isNetworkRunning
        ) {
            Text("Start Network")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.stopNetwork() },
            enabled = isNetworkRunning
        ) {
            Text("Stop Network")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("Connected Endpoints:")
        connectedEndpoints.forEach { (id, address) ->
            Text("$id: $address")
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = messageText,
            onValueChange = { messageText = it },
            label = { Text("Enter message") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { viewModel.sendMessage(messageText) }) {
            Text("Send Message")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Messages:")
        messages.forEach { (sender, message) ->
            Text("$sender: $message")
        }
    }
}