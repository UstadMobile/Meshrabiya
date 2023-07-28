package com.ustadmobile.meshrabiya.testapp.server

import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.test.TestVirtualNode
import com.ustadmobile.meshrabiya.test.connectTo
import kotlinx.serialization.json.Json
import net.luminis.httpclient.AndroidH3Factory
import net.luminis.quic.DatagramSocketFactory
import net.luminis.tls.env.PlatformMapping
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.security.Security
import kotlin.io.path.readBytes
import kotlin.random.Random

class TestAppServerInstrumentedTest {

    @field:Rule
    @JvmField
    val tempDir = TemporaryFolder()

    @Test
    fun shouldStart() {
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())
        val wwwDir = tempDir.newFolder()
        val indexHtmlFile = File(wwwDir, "index.html")
        val randomFile = File(wwwDir, "random.dat")
        val randomBytes = Random.nextBytes(5000)
        randomFile.writeBytes(randomBytes)


        indexHtmlFile.writeText("<html><body>Hello World</body></html>")
        val testServer = TestAppServer.newTestServerWithRandomKey(wwwDir)
        testServer.start()

        val h3Factory = AndroidH3Factory()
        val builder = h3Factory.newClientBuilder()
        builder.disableCertificateCheck()
        val http3Client = builder.build()

        val request = h3Factory.newRequestBuilder()
            .uri(URI("https://localhost:${testServer.localPort}"))
            .build()

        val response = http3Client.send(request,
            h3Factory.bodyHandlers().ofString())

        println(response.body())

        val fileRequest = h3Factory.newRequestBuilder()
            .uri(URI("https://localhost:${testServer.localPort}/random.dat"))
            .build()

        val downloadedRandomDat = tempDir.newFile().toPath()
        val fileResponse = http3Client.send(
            fileRequest, h3Factory.bodyHandlers().ofFile(downloadedRandomDat)
        )

        val downloadedPath = fileResponse.body()
        Assert.assertArrayEquals(randomBytes, downloadedPath.readBytes())
    }


    @Test
    fun shouldRunOverVirtualNet() {
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())

        val json = Json {
            encodeDefaults = true
        }
        val testNode1 = TestVirtualNode(
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 1).ip4AddressToInt(),
            json = json,
        )

        val testNode2 = TestVirtualNode(
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 2).ip4AddressToInt(),
            json = json,
        )

        try {
            testNode1.connectTo(testNode2)

            val h3Factory = AndroidH3Factory()

            val wwwDir = tempDir.newFolder()
            val randomFile = File(wwwDir, "random.dat")
            val randomBytes = Random.nextBytes(5000)
            randomFile.writeBytes(randomBytes)

            val testServer = TestAppServer.newTestServerWithRandomKey(
                wwwDir = wwwDir,
                socket = testNode2.createBoundDatagramSocket(0)
            )
            testServer.start()

            val http3Client = h3Factory.newClientBuilder()
                .disableCertificateCheck()
                .datagramSocketFactory {
                    testNode1.createBoundDatagramSocket(0)
                }
                .build()

            val fileRequest = h3Factory.newRequestBuilder()
                .uri(URI("https://169.254.1.2:${testServer.localPort}/random.dat"))
                .build()

            val downloadedRandomDat = tempDir.newFile().toPath()
            val fileResponse = http3Client.send(
                fileRequest, h3Factory.bodyHandlers().ofFile(downloadedRandomDat)
            )

            val downloadedPath = fileResponse.body()
            Assert.assertArrayEquals(randomBytes, downloadedPath.readBytes())
        }finally {
            testNode1.close()
            testNode2.close()
        }
    }

}
