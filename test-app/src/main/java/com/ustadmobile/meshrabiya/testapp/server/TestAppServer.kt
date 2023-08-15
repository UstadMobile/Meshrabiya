package com.ustadmobile.meshrabiya.testapp.server

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ustadmobile.meshrabiya.ext.copyToWithProgressCallback
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.ext.getUriNameAndSize
import com.ustadmobile.meshrabiya.testapp.ext.updateItem
import com.ustadmobile.meshrabiya.util.FileSerializer
import com.ustadmobile.meshrabiya.util.InetAddressSerializer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.headersContentLength
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger



/**
 * The TestAppServer is used to send/receive files between nodes. Flow as follows:
 * 1. The sender
 */
class TestAppServer(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
    private val mLogger: MNetLogger,
    name: String,
    port: Int = 0,
    private val localVirtualAddr: InetAddress,
    private val receiveDir: File,
    private val json: Json,
) : NanoHTTPD(port), Closeable {

    private val logPrefix: String = "[TestAppServer - $name] "

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    enum class Status {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, DECLINED
    }
    data class OutgoingTransfer(
        val id: Int,
        val name: String,
        val uri: Uri,
        val toHost: InetAddress,
        val status: Status = Status.PENDING,
        val size: Int,
        val transferred: Int = 0,
    )

    @Serializable
    data class IncomingTransfer(
        val id: Int,
        val requestReceivedTime: Long = System.currentTimeMillis(),
        @Serializable(with = InetAddressSerializer::class)
        val fromHost: InetAddress,
        val name: String,
        val status: Status = Status.PENDING,
        val size: Int,
        val transferred: Int = 0,
        val transferTime: Int = 1,
        @Serializable(with = FileSerializer::class)
        val file: File? = null,
    )

    private val transferIdAtomic = AtomicInteger()

    private val _outgoingTransfers = MutableStateFlow(emptyList<OutgoingTransfer>())

    val outgoingTransfers: Flow<List<OutgoingTransfer>> = _outgoingTransfers.asStateFlow()

    val _incomingTransfers = MutableStateFlow(emptyList<IncomingTransfer>())

    val incomingTransfers: Flow<List<IncomingTransfer>> = _incomingTransfers.asStateFlow()

    val localPort: Int
        get() = super.getListeningPort()

    init {
        scope.launch {
            val incomingFiles = receiveDir.listFiles { file, fileName: String? ->
                fileName?.endsWith(".rx.json") == true
            }?.map {
                json.decodeFromString(IncomingTransfer.serializer(), it.readText())
            } ?: emptyList()
            _incomingTransfers.update { prev ->
                buildList {
                    addAll(prev)
                    addAll(incomingFiles.sortedByDescending { it.requestReceivedTime })
                }
            }
        }
    }


    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        mLogger(Log.INFO, "$logPrefix : ${session.method} ${session.uri}")

        if(path.startsWith("/download/")) {
            val xferId = path.substringAfterLast("/").toInt()
            val outgoingXfer = _outgoingTransfers.value.first {
                it.id == xferId
            }

            val contentIn = appContext.contentResolver.openInputStream(outgoingXfer.uri)?.let {
                InputStreamCounter(it.buffered())
            }

            if(contentIn == null) {
                mLogger(Log.ERROR, "$logPrefix Failed to open input stream to serve $path - ${outgoingXfer.uri}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                    "Failed to open InputStream")
            }

            mLogger(Log.INFO, "$logPrefix Sending file for xfer #$xferId")
            val response = newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream",
                contentIn,
                outgoingXfer.size.toLong()
            )

            //Provide status updates by checking how many bytes have been read periodically
            scope.launch {
                while(!contentIn.closed) {
                    _outgoingTransfers.update { prev ->
                        prev.updateItem(
                            updatePredicate = { it.id == xferId },
                            function = { item ->
                                item.copy(
                                    transferred = contentIn.bytesRead,
                                )
                            }
                        )
                    }
                    delay(500)
                }

                val status = if(contentIn.bytesRead == outgoingXfer.size) {
                    Status.COMPLETED
                }else {
                    Status.FAILED
                }
                mLogger(Log.INFO, "$logPrefix Sending file for xfer #$xferId - finished - status=$status")

                _outgoingTransfers.update { prev ->
                    prev.updateItem(
                        updatePredicate = { it.id == xferId },
                        function = { item ->
                            item.copy(
                                transferred = contentIn.bytesRead,
                                status = status
                            )
                        }
                    )
                }
            }

            return response
        }else if(path.startsWith("/send")) {
            mLogger(Log.INFO, "$logPrefix Received incoming transfer request")
            val searchParams = session.queryParameterString.split("&")
                .map {
                    it.substringBefore("=") to it.substringAfter("=")
                }.toMap()

            val id = searchParams["id"]
            val filename = searchParams["filename"]
            val size = searchParams["size"]?.toInt() ?: -1
            val fromAddr = searchParams["from"]

            if(id != null && filename != null && fromAddr != null) {
                val incomingTransfer = IncomingTransfer(
                    id = id.toInt(),
                    fromHost = InetAddress.getByName(fromAddr),
                    name = filename,
                    size = size
                )

                _incomingTransfers.update { prev ->
                    buildList {
                        add(incomingTransfer)
                        addAll(prev)
                    }
                }

                mLogger(Log.INFO, "$logPrefix Added request id $id for $filename from ${incomingTransfer.fromHost}")
                return newFixedLengthResponse("OK")
            }else {
                mLogger(Log.INFO, "$logPrefix incomin transfer request - bad request - missing params")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad request")
            }
        }else if(path.startsWith("/decline")){
            val xferId = path.substringAfterLast("/").toInt()
            _outgoingTransfers.update { prev ->
                prev.updateItem(
                    updatePredicate = { it.id == xferId },
                    function = {
                        it.copy(
                            status = Status.DECLINED
                        )
                    }
                )
            }

            return newFixedLengthResponse("OK")
        }else {
            mLogger(Log.INFO, "$logPrefix : $path - NOT FOUND")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: $path")
        }
    }


    init {

    }

    /**
     * Add an outgoing transfer. This is done using a Uri so that we don't have to make our own
     * copy of the file the user wants to transfer.
     */
    fun addOutgoingTransfer(
        uri: Uri,
        toNode: InetAddress,
        toPort: Int = DEFAULT_PORT,
    ): OutgoingTransfer {
        val transferId = transferIdAtomic.incrementAndGet()

        val nameAndSize = appContext.contentResolver.getUriNameAndSize(uri)
        val effectiveName = nameAndSize.name ?: "unknown"
        mLogger(Log.INFO, "$logPrefix adding outgoing transfer of $uri " +
                "(name=${nameAndSize.name} size=${nameAndSize.size} to $toNode:$toPort")

        val outgoingTransfer = OutgoingTransfer(
            id = transferId,
            name = effectiveName,
            uri = uri ,
            toHost = toNode,
            size = nameAndSize.size.toInt(),
        )


        //tell the other side about the transfer
        val request = Request.Builder().url("http://${toNode.hostAddress}:$toPort/" +
                "send?id=$transferId&filename=${URLEncoder.encode(effectiveName, "UTF-8")}" +
                "&size=${nameAndSize.size}&from=${localVirtualAddr.hostAddress}")
            //.addHeader("connection", "close")
            .build()
        mLogger(Log.INFO, "$logPrefix notifying $toNode of incoming transfer")

        val response = httpClient.newCall(request).execute()
        val serverResponse = response.body?.string()
        mLogger(Log.INFO, "$logPrefix - received response: $serverResponse")

        _outgoingTransfers.update { prev ->
            buildList {
                add(outgoingTransfer)
                addAll(prev)
            }
        }

        return outgoingTransfer
    }

    fun acceptIncomingTransfer(
        transfer: IncomingTransfer,
        destFile: File,
        fromPort: Int = DEFAULT_PORT,
    ) {
        val startTime = System.currentTimeMillis()
        _incomingTransfers.update { prev ->
            prev.updateItem(
                updatePredicate = { it.id == transfer.id },
                function = { item ->
                    item.copy(
                        status = Status.IN_PROGRESS,
                    )
                }
            )
        }

        try {
            val request = Request.Builder()
                .url("http://${transfer.fromHost.hostAddress}:$fromPort/download/${transfer.id}")
                .build()

            val response = httpClient.newCall(request).execute()
            val fileSize = response.headersContentLength()
            var lastUpdateTime = 0L
            val totalTransfered = response.body?.byteStream()?.use { responseIn ->
                FileOutputStream(destFile).use { fileOut ->
                    responseIn.copyToWithProgressCallback(
                        out = fileOut,
                        onProgress = { bytesTransferred ->
                            val timeNow = System.currentTimeMillis()
                            if(timeNow - lastUpdateTime > 500) {
                                _incomingTransfers.update { prev ->
                                    prev.updateItem(
                                        updatePredicate = { it.id == transfer.id },
                                        function = { item ->
                                            item.copy(
                                                transferred = bytesTransferred.toInt()
                                            )
                                        }
                                    )
                                }
                                lastUpdateTime = System.currentTimeMillis()
                            }
                        }
                    )
                }
            }
            response.close()

            val transferDurationMs = (System.currentTimeMillis() - startTime).toInt()
            val incomingTransfersVal = _incomingTransfers.updateAndGet { prev ->
                prev.updateItem(
                    updatePredicate = { it.id == transfer.id },
                    function = { item ->
                        item.copy(
                            transferTime = transferDurationMs,
                            status = if(totalTransfered == fileSize) {
                                 Status.COMPLETED
                            }else {
                                  Status.FAILED
                            },
                            file = destFile,
                            transferred = totalTransfered?.toInt() ?: item.transferred
                        )
                    }
                )
            }

            //Write JSON to file so received files can be listed after app restarts etc.
            val incomingTransfer = incomingTransfersVal.firstOrNull {
                it.id == transfer.id
            }

            if(incomingTransfer != null) {
                val jsonFile = File(receiveDir, "${incomingTransfer.name}.rx.json")
                jsonFile.writeText(json.encodeToString(IncomingTransfer.serializer(), incomingTransfer))
            }

            val speedKBS = transfer.size / transferDurationMs
            mLogger(Log.INFO, "$logPrefix acceptIncomingTransfer successful: Downloaded " +
                    "${transfer.size}bytes in ${transfer.transferTime}ms ($speedKBS) KB/s")
        }catch(e: Exception) {
            mLogger(Log.ERROR, "$logPrefix acceptIncomingTransfer ($transfer) FAILED", e)
            _incomingTransfers.update { prev ->
                prev.updateItem(
                    updatePredicate = { it.id == transfer.id },
                    function = { item ->
                        item.copy(
                            transferred = destFile.length().toInt(),
                            status = Status.FAILED,
                        )
                    }
                )
            }
        }
    }

    suspend fun onDeclineIncomingTransfer(
        transfer: IncomingTransfer,
        fromPort: Int = DEFAULT_PORT,
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("http://${transfer.fromHost.hostAddress}:$fromPort/decline/${transfer.id}")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val strResponse = response.body?.string()
                mLogger(Log.DEBUG, "$logPrefix - onDeclineIncomingTransfer - request to: ${request.url} : response = $strResponse")
            }catch(e: Exception) {
                mLogger(Log.WARN, "$logPrefix - onDeclineIncomingTransfer : exception- request to: ${request.url} : FAIL", e)
            }
        }

        _incomingTransfers.update { prev ->
            prev.updateItem(
                updatePredicate = { it.id == transfer.id },
                function = {
                    it.copy(
                        status = Status.DECLINED,
                    )
                }
            )
        }
    }

    suspend fun onDeleteIncomingTransfer(
        incomingTransfer: IncomingTransfer
    ) {
        withContext(Dispatchers.IO) {
            val jsonFile = incomingTransfer.file?.let {
                File(it.parentFile, it.name + ".rx.json")
            }
            incomingTransfer.file?.delete()
            jsonFile?.delete()
            _incomingTransfers.update { prev ->
                prev.filter { it.id != incomingTransfer.id }
            }
        }
    }


    override fun close() {
        stop()
        scope.cancel()
    }

    companion object {

        const val DEFAULT_PORT = 4242

    }

}