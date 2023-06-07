package com.ustadmobile.httpoverbluetooth.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.httpoverbluetooth.HttpOverBluetoothConstants.UUID_BUSY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest
import rawhttp.core.body.StringBody
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
    private val rawHttp: RawHttp,
    private val uuidAllocationClient: UuidAllocationClient = UuidAllocationClient(appContext),
) {

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private fun newTextResponse(
        statusCode: Int,
        responseLine: String,
        text: String,
    ) : BluetoothHttpResponse {
        return BluetoothHttpResponse(
            response = rawHttp.parseResponse(
                "HTTP/1.1 $statusCode $responseLine\n" +
                        "Content-Type: text/plain\n"
            ).withBody(StringBody(text)),
            onClose = { }
        )
    }

    private fun newInternalErrorResponse(message: String): BluetoothHttpResponse {
        return newTextResponse(
            statusCode = 500,
            responseLine = "Internal Server Error",
            text = message,
        )
    }

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
        request: RawHttpRequest
    ) : BluetoothHttpResponse {
        val adapter = bluetoothAdapter ?: return newInternalErrorResponse("No bluetooth adapter")

        val dataUuid = uuidAllocationClient.requestUuidAllocation(
            remoteAddress = remoteAddress,
            remoteServiceUuid = remoteUuidAllocationUuid,
            remoteCharacteristicUuid = remoteUuidAllocationCharacteristicUuid,
        )

        if(dataUuid == UUID_BUSY) {
            return newTextResponse(503, "Service Unavailable",
                "Server UUID port not allocated: busy")
        }

        val remoteDevice = adapter.getRemoteDevice(remoteAddress)

        return withContext(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            var inStream: InputStream? = null
            var outStream: OutputStream? = null

            try {
                socket = remoteDevice.createInsecureRfcommSocketToServiceRecord(
                    dataUuid
                ) ?: throw IllegalStateException()
                Log.i(LOG_TAG, "Connecting to server on $dataUuid")
                socket.connect()
                Log.i(LOG_TAG, "Socket connected on $dataUuid : sending request ${request.method} ${request.uri}")

                inStream = socket.inputStream
                outStream = socket.outputStream
                request.writeTo(outStream)
                val httpResponse = rawHttp.parseResponse(inStream)
                Log.i(LOG_TAG, "Received response: ${httpResponse.statusCode} ${httpResponse.startLine.reason}")

                return@withContext BluetoothHttpResponse(
                    response = httpResponse,
                    onClose = {
                        Log.i(LOG_TAG, "Closing response/socket for $dataUuid")
                        //if keep-alive is used, we could send additional requests here.
                        socket.close()
                    }
                )
            }catch(e: SecurityException) {
                e.printStackTrace()
                socket?.close()
                return@withContext newInternalErrorResponse(e.toString())
            }catch(e: Exception) {
                e.printStackTrace()
                socket?.close()
                return@withContext newInternalErrorResponse(e.toString())
            }
        }
    }
}