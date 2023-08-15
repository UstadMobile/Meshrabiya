package com.ustadmobile.httpoverbluetooth

import android.content.Context
import com.ustadmobile.meshrabiya.server.AbstractHttpOverBluetoothServer
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.body.StringBody
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class MessageReplyBluetoothHttpServer(
    appContext: Context,
    rawHttp: RawHttp,
    message: String = "Hello World",
): AbstractHttpOverBluetoothServer(
    appContext = appContext,
    rawHttp = rawHttp,
    allocationServiceUuid = UUID.fromString(UUID_MASK),
    allocationCharacteristicUuid = UUID.fromString(CHARACTERISTIC_UUID),
    maxClients = 4,
) {

    private val messageAtomic = AtomicReference(message)

    fun interface ServerListener {
        fun onResponseSent(fromDevice: String, replySent: String)
    }

    var listener: ServerListener? = null

    var message: String
        get() = messageAtomic.get()
        set(value) {
            messageAtomic.set(value)
        }

    override fun handleRequest(
        remoteDeviceAddress: String,
        request: RawHttpRequest,
    ): RawHttpResponse<*> {
        val replyMessage = messageAtomic.get()
        val response = rawHttp.parseResponse("HTTP/1.1 200 OK\n" +
                "Content-Type: text/plain\n"
        ).withBody(StringBody(replyMessage))
        listener?.onResponseSent(remoteDeviceAddress, replyMessage)

        return response
    }

    companion object {

        const val UUID_MASK = "21d46f25-4640-4791-a67b-2a32ddd104b3"

        const val CHARACTERISTIC_UUID = "d7747e7c-b0d3-4093-bca8-547ee070e41a"

    }
}