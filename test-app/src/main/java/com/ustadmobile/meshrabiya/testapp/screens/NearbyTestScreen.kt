package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val messages by viewModel.messages.collectAsState()
    var messageText by remember { mutableStateOf("") }

    // State for log scroll position
    val logScrollState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = {
                if (isNetworkRunning) viewModel.stopNetwork() else viewModel.startNetwork()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isNetworkRunning) "Stop Network" else "Start Network")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Display endpoints in a single line
        Text("Endpoints: ${endpoints.joinToString(", ") { it.endpointId }}", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Text("Messages:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                .padding(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(messages) { message ->
                // Create a card that wraps tightly around the text
                Card(
                    modifier = Modifier
                        .wrapContentWidth() // Wrap content width based on text size
                        .padding(vertical = 4.dp) // Spacing between messages
                        .align(Alignment.Start), // Align to start (left)
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Text(
                        text = message.trim(), // Trim spaces
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(8.dp) // Padding inside each message card
                    )
                }
            }
        }


        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        viewModel.sendMessage(messageText)
                        messageText = ""
                    }
                },
                enabled = isNetworkRunning && messageText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Send")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Logs:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            state = logScrollState,
            modifier = Modifier
                .height(100.dp) // Height can be adjusted as needed
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            items(logs) { log ->
                Text(log, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Automatically scroll to the bottom of the logs
        LaunchedEffect(logs.size) {
            // Check if there are logs before scrolling
            if (logs.isNotEmpty()) {
                logScrollState.animateScrollToItem(logs.size - 1)
            }
        }
    }
}






