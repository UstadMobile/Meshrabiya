package com.ustadmobile.httpoverbluetooth

import net.luminis.quic.QuicClientConnection
import net.luminis.quic.QuicStream
import net.luminis.quic.log.SysOutLogger
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets


//As per https://bitbucket.org/pjtr/kwik/src/master/src/main/java/net/luminis/quic/sample/echo/SimpleEchoClient.java

class SimpleEchoClient(
    val serverPort: Int
) {
    private lateinit var connection: QuicClientConnection

    init {

    }

    @Throws(IOException::class)
    fun run() {
        val log = SysOutLogger()
        // log.logPackets(true);     // Set various log categories with log.logABC()
        connection = QuicClientConnection.newBuilder()
            .uri(URI.create("echo://localhost:$serverPort"))
            .logger(log)
            .noServerCertificateCheck()
            .build()
        connection.connect(5000, "echo")
        //if expecting peer initiated sessions
        //connection.setPeerInitiatedStreamCallback()

        echo("hello mate!")
        echo("look, a second request on a separate stream!")
        connection.closeAndWait()
    }

    @Throws(IOException::class)
    private fun echo(payload: String) {

        val quicStream: QuicStream = connection.createStream(true)
        val requestData = payload.toByteArray(StandardCharsets.US_ASCII)
        quicStream.outputStream.write(requestData)
        quicStream.outputStream.close()
        print("Response from server: ")
        //quicStream.inputStream.transferTo(System.out)
        quicStream.inputStream.copyTo(System.out)
        println()
    }
}