package com.ustadmobile.meshrabiya.testapp.server

import android.util.Log
import com.ustadmobile.meshrabiya.ext.toPem
import com.ustadmobile.meshrabiya.vnet.quic.generateKeyPair
import com.ustadmobile.meshrabiya.vnet.quic.generateX509Cert
//import net.luminis.http3.server.Http3ApplicationProtocolFactory
import net.luminis.quic.Version
import net.luminis.quic.log.SysOutLogger
import net.luminis.quic.server.ServerConnector
import net.luminis.tls.env.PlatformMapping
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.net.DatagramSocket
import java.security.Security

class TestAppServer(
    private val wwwDir: File
) {

    /*
    fun start() {
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())

        val keyPair = generateKeyPair()
        val certificate = generateX509Cert(keyPair)
        val keyIn = ByteArrayInputStream(keyPair.private.toPem().encodeToByteArray())
        val certIn = ByteArrayInputStream(certificate.toPem().encodeToByteArray())

        val log = SysOutLogger()
        val serverSocket = DatagramSocket()
        val serverConnector = ServerConnector(
            serverSocket, certIn, keyIn, listOf(Version.QUIC_version_1), false, log
        )
        val http3AppConnectionFactory = Http3ApplicationProtocolFactory(wwwDir)
        serverConnector.registerApplicationProtocol("h3", http3AppConnectionFactory)
        serverConnector.start()
        Log.i("Http3ServerTest", "Started OK")
    }*/

}