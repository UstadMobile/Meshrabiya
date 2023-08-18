package com.ustadmobile.meshrabiya.util

import java.net.DatagramSocket
import java.net.ServerSocket
import kotlin.random.Random


fun findFreePort(preferred: Int = 0): Int {
    var portToTry = if(preferred == 0) Random.nextInt(1025, 65_536) else preferred
    while(true){
        try {
            ServerSocket(portToTry).close()
            DatagramSocket(portToTry).close()
            return portToTry
        }catch(e: Exception) {
            //Do nothing - try another port
        }
        portToTry = Random.nextInt(1025, 65_536)
    }
}
