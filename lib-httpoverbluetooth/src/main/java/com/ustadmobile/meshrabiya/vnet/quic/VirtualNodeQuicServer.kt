package com.ustadmobile.meshrabiya.vnet.quic

import net.luminis.quic.QuicConnection
import net.luminis.quic.QuicStream
import net.luminis.quic.Version
import net.luminis.quic.log.Logger
import net.luminis.quic.server.ApplicationProtocolConnection
import net.luminis.quic.server.ServerConnector
import java.io.InputStream
import java.net.DatagramSocket

/**
 *
 */
class VirtualNodeQuicServer(
    socket: DatagramSocket,
    certificateFile: InputStream,
    certificateKeyFile: InputStream,
    supportedVersions: List<Version>,
    requireRetry: Boolean,
    log: Logger,
): ServerConnector(
    socket, certificateFile, certificateKeyFile, supportedVersions, requireRetry, log
) {

    class IncomingQuicStreamHandler(private val stream: QuicStream) {
        init {
            //read the packet to find the destination port
            //then return a socket implementation
        }
    }


    internal class MeshrabiyaProtocolConnection(
        private val quicConnection: QuicConnection?,
        private val log: Logger
    ) : ApplicationProtocolConnection {

        override fun acceptPeerInitiatedStream(stream: QuicStream) {

        }

    }

    fun registerHandler() {

    }


    companion object {

        const val PROTO = "meshrabiya"

    }

}