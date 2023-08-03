package com.ustadmobile.meshrabiya.test

import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FileEchoSocketServer(
    private val file: File,
    port: Int,
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor(),
) : Runnable {

    private val serverSocket = ServerSocket(port)

    private val future = executorService.submit(this)

    val localPort: Int
        get() = serverSocket.localPort


    override fun run() {
        while(!Thread.interrupted()) {
            val client = serverSocket.accept()
            FileInputStream(file).use { fileIn ->
                fileIn.copyTo(client.getOutputStream())
            }
            client.close()
        }
    }

    fun close() {
        future.cancel(true)
    }

}