package com.ustadmobile.meshrabiya.testapp.composable

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ustadmobile.meshrabiya.MeshrabiyaConstants.LOG_TAG
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import java.util.regex.Pattern


fun interface ConnectBluetoothLauncher {
    fun launch(bluetoothConfig: MeshrabiyaBluetoothState)
}

data class ConnectBluetoothLauncherResult(
    val device: BluetoothDevice?,
)

/**
 * Manage connecting a Bluetooth device including requesting permission (if required) and using
 * CompanionDeviceManager to associate with the device.
 *
 * Use as follows
 *
 * val bluetoothLauncher = rememberBluetoothConnectLauncher { result ->
 *     if(result.device != null) {
 *         //connect and use it
 *     }
 * }
 *
 * ...
 *
 * //Where config provides the bluetooth config e.g. device name etc.
 *
 * bluetoothLauncher.launch(config)
 */
@Composable
fun rememberBluetoothConnectLauncher(
    onResult: (ConnectBluetoothLauncherResult) -> Unit,
) : ConnectBluetoothLauncher {

    var associateAfterPermissionConfig: MeshrabiyaBluetoothState? by remember {
        mutableStateOf(null)
    }

    val context = LocalContext.current

    val associateIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    )  { result ->
        if(result.resultCode == Activity.RESULT_OK) {
            val device: BluetoothDevice? = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE
            )

            onResult(ConnectBluetoothLauncherResult(device))
        }
    }

    fun associate(config: MeshrabiyaBluetoothState) {
        Log.d(LOG_TAG, "ConnectBluetooth: start associate")
        val deviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(Pattern.quote(config.deviceName)))
            .build()
        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        val deviceManager : CompanionDeviceManager =
            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        deviceManager.associate(
            pairingRequest,
            object: CompanionDeviceManager.Callback() {
                @Deprecated("Thank you Google")
                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.d(LOG_TAG, "ConnectBluetooth: onDeviceFound: Launching intent sender")
                    associateIntentSenderLauncher.launch(
                        IntentSenderRequest.Builder(intentSender)
                            .build()
                    )

                }

                override fun onFailure(p0: CharSequence?) {
                    Log.e(LOG_TAG, "ConnectBluetooth: associateRequest: onFailure: $p0")
                    onResult(ConnectBluetoothLauncherResult(device = null))
                }
            },
            null
        )
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val btConfig = associateAfterPermissionConfig ?: return@rememberLauncherForActivityResult
        associateAfterPermissionConfig = null

        if(granted) {
            associate(btConfig)
        }else {
            onResult(
                ConnectBluetoothLauncherResult(
                device = null
            )
            )
        }
    }

    return ConnectBluetoothLauncher {
        Log.d(LOG_TAG, "ConnectBluetoothLauncher: config=${it}")
        //check permission
        if(Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG, "ConnectBluetoothLauncher: request bluetooth connect permission")
            associateAfterPermissionConfig = it
            requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }else {
            Log.d(LOG_TAG, "ConnectBluetoothLauncher: start association")
            associate(it)
        }
    }
}
