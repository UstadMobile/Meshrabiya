package com.ustadmobile.meshrabiya.client

import rawhttp.core.RawHttpResponse
import java.io.Closeable

/**
 * BluetoothHttpResponse containing the RawHttpResponse itself. This MUST be closed when used to
 * release the underlying socket.
 */
class BluetoothHttpResponse(
    val response: RawHttpResponse<*>,
    internal val onClose: () -> Unit,
) : Closeable{

    override fun close() {
        onClose()
    }

}