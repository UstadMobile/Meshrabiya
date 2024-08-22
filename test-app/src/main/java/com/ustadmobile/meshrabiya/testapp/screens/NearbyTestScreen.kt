package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestViewModel
import kotlinx.coroutines.launch


@Composable
fun NearbyTestScreen(viewModel: NearbyTestViewModel = viewModel()) {
    val logs by viewModel.logs.collectAsState()

    Column(modifier = Modifier.padding(16.dp)) {
        Button(
            onClick = { viewModel.startNearbyNetwork() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Start Nearby Network")
        }

        Button(
            onClick = { viewModel.stopNearbyNetwork() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Stop Nearby Network")
        }

        Button(
            onClick = { viewModel.sendTestPacket() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Send Test Packet")
        }

        Button(
            onClick = { viewModel.sendBroadcastMessage() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Send Broadcast Message")
        }

        Text(
            "Logs:",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}