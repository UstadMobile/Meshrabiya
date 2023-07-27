package com.ustadmobile.meshrabiya.testapp.server

import net.luminis.httpclient.AndroidH3Factory
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

}
