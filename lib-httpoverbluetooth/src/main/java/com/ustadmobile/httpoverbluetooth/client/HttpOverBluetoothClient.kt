package com.ustadmobile.httpoverbluetooth.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.util.UUID

/**
 * As per https://developer.android.com/guide/topics/connectivity/bluetooth/connect-bluetooth-devices#connect-server
 * point 3 "Unlike TCP/IP, RFCOMM allows only one connected client per channel at a time,"
 *
 * HttpOverBluetoothClient will contact the use UuidAllocationClient to get a UUID allocated by the
 * server. It will then use Bluetooth Classic RFCOMM to run the actual request over the allocated
 * UUID.
 *
 */
class HttpOverBluetoothClient(
    private val appContext: Context,
    private val uuidAllocationClient: UuidAllocationClient = UuidAllocationClient(appContext),
    //private val rawHttp: RawHttp,
) {

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter


    /**
     * @param remoteAddress the real bluetooth mac address of the remote device
     * @param remoteUuidAllocationUuid the uuid of the GATT service running the UUID allocation on
     * the remote device
     * @param remoteUuidAllocationCharacteristicUuid the uuid of the gatt characteristic that is usedd
     * to issue uuids for data transfer
     */
    suspend fun sendRequest(
        remoteAddress: String,
        remoteUuidAllocationUuid: UUID,
        remoteUuidAllocationCharacteristicUuid: UUID,
//        request: RawHttpRequest
    )  {
        val adapter = bluetoothAdapter ?: return /*rawHttp.parseResponse(
            "HTTP/1.1 500 Internal Server Error\n" +
                    "Content-Type: text/plain\n"
        ).withBody(StringBody("HttpOverBluetooth: No  bluetooth adapter"))*/

        val dataUuid = uuidAllocationClient.requestUuidAllocation(
            remoteAddress = remoteAddress,
            remoteServiceUuid = remoteUuidAllocationUuid,
            remoteCharacteristicUuid = remoteUuidAllocationCharacteristicUuid,
        )

        val remoteDevice = adapter.getRemoteDevice(remoteAddress)

        withContext(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            var inStream: InputStream? = null
            var outStream: OutputStream? = null

            try {
                val startTime = System.currentTimeMillis()
                try {
                    socket = remoteDevice.createInsecureRfcommSocketToServiceRecord(
                        dataUuid
                    )
                    Log.i("HttpOverBluetoothTag", "Connecting to server on $dataUuid")
                    socket.connect()
                    Log.i("HttpOverBluetoothTag", "Socket connected $dataUuid")

                    inStream = socket.inputStream
                    outStream = socket.outputStream
                    val inReader = BufferedReader(inStream.reader())
                    val serverSays = inReader.readLine()
                    Log.i(LOG_TAG, "Got message from server: $serverSays")
                }catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }catch(e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
            }
        }
    }
}