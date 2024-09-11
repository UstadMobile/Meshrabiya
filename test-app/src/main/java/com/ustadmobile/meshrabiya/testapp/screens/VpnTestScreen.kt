package com.ustadmobile.meshrabiya.testapp.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ustadmobile.meshrabiya.testapp.viewmodel.VpnStatus.CONNECTED
import com.ustadmobile.meshrabiya.testapp.viewmodel.VpnStatus.DISCONNECTED
import com.ustadmobile.meshrabiya.testapp.viewmodel.VpnTestViewModel


@Composable
fun VpnTestScreen(viewModel: VpnTestViewModel, onStartVpn: () -> Unit) {
    val vpnStatus by viewModel.vpnStatus.collectAsState()
    var ipAddress by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VPN Status: ${vpnStatus.name}",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                when (vpnStatus) {
                    DISCONNECTED -> onStartVpn()
                    CONNECTED -> viewModel.stopVpn()
                }
            }
        ) {
            Text(if (vpnStatus == DISCONNECTED) "Start VPN" else "Stop VPN")
        }

        Spacer(modifier = Modifier.height(16.dp))


    }
}