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
import java.util.UUID
import kotlin.Exception

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
    private val onLogError: (message: String, exception: Exception?) -> Unit = { _, _ -> },
    private val uuidAllocationClient: UuidAllocationClient = UuidAllocationClient(
        appContext = appContext,
        onLogError = onLogError,
    ),
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
     * Send an HTTP request over bluetooth. You *MUST* close the response when finished with it
     * to release the underlying bluetooth socket.
     *
     * @param remoteAddress the real bluetooth mac address of the remote device
     * @param remoteUuidAllocationUuid the uuid of the GATT service running the UUID allocation on
     * the remote device
     * @param remoteUuidAllocationCharacteristicUuid the uuid of the gatt characteristic that is usedd
     * to issue uuids for data transfer
     * @param request the http request to send.
     *
     * @return BluetoothHttpResponse containing the response. The body will not be eagerly read. The
     * response MUST be closed.
     */
    suspend fun sendRequest(
        remoteAddress: String,
        remoteUuidAllocationUuid: UUID,
        remoteUuidAllocationCharacteristicUuid: UUID,
        request: RawHttpRequest
    ) : BluetoothHttpResponse {
        val adapter = bluetoothAdapter ?: return newInternalErrorResponse("No bluetooth adapter")
        if(!adapter.isEnabled)
            return newTextResponse(statusCode = 503, responseLine = "Service Unavailable",
                text = "Bluetooth not enabled")

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

            try {
                socket = remoteDevice.createInsecureRfcommSocketToServiceRecord(
                    dataUuid
                ) ?: throw IllegalStateException()
                Log.d(LOG_TAG, "Connecting to server on $dataUuid")
                socket.connect()
                Log.d(LOG_TAG, "Socket connected on $dataUuid : sending request ${request.method} ${request.uri}")

                //inStream and outStream will be closed when the underlying socket is closed.
                val inStream = socket.inputStream
                val outStream = socket.outputStream
                request.writeTo(outStream)
                val httpResponse = rawHttp.parseResponse(inStream)
                Log.d(LOG_TAG, "Received response: ${httpResponse.statusCode} ${httpResponse.startLine.reason}")

                return@withContext BluetoothHttpResponse(
                    response = httpResponse,
                    onClose = {
                        //if keep-alive is used, we could send additional requests here.
                        socket.close()
                        Log.d(LOG_TAG, "Closed response/socket for $dataUuid")
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