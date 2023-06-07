package com.ustadmobile.httpoverbluetooth

import android.content.Context
import com.ustadmobile.httpoverbluetooth.server.AbstractHttpOverBluetoothServer
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.body.StringBody
import java.util.UUID

class EchoBluetoothHttpServer(
    appContext: Context,
    rawHttp: RawHttp,
): AbstractHttpOverBluetoothServer(
    appContext = appContext,
    rawHttp = rawHttp,
    allocationServiceUuid = UUID.fromString(SERVICE_UUID),
    allocationCharacteristicUuid = UUID.fromString(CHARACTERISTIC_UUID),
    maxClients = 4,
) {

    override fun handleRequest(request: RawHttpRequest): RawHttpResponse<*> {
        return rawHttp.parseResponse("HTTP/1.1 200 OK\n" +
                "Content-Type: text/plain\n"
        ).withBody(StringBody("Hello World"))
    }

    companion object {

        const val SERVICE_UUID = "21d46f25-4640-4791-a67b-2a32ddd104b3"

        const val CHARACTERISTIC_UUID = "d7747e7c-b0d3-4093-bca8-547ee070e41a"

    }
}