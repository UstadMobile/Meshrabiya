package com.meshrabiya.lib_vpn


import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream

import java.io.IOException


import java.nio.ByteBuffer

class MeshrabiyaVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val builder = Builder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("VPN Service started")
        establishVpn()
        return START_STICKY
    }

    private fun establishVpn() {
        try {
            builder.apply {
                addAddress("10.0.0.2", 24)
                addRoute("192.168.0.0", 24)
                addDnsServer("1.1.1.1")
                addDnsServer("8.8.8.8")
                setSession("MeshrabiyaVPN")
                setMtu(1500)
                allowFamily(OsConstants.AF_INET)
                allowFamily(OsConstants.AF_INET6)
            }

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                log("VPN interface established successfully")
                Thread { handleTraffic() }.start()
            } else {
                log("Failed to establish VPN interface")
            }
        } catch (e: Exception) {
            log("Error establishing VPN: ${e.message}")
        }
    }

    private fun handleTraffic() {
        val buffer = ByteBuffer.allocate(32767)
        while (true) {
            try {
                buffer.clear()
                val length = vpnInterface?.fileDescriptor?.let { FileInputStream(it).channel.read(buffer) } ?: -1
                if (length > 0) {
                    buffer.flip()
                    log("packet: ${buffer.array().take(length)}")
                    vpnInterface?.fileDescriptor?.let { FileOutputStream(it).channel.write(buffer) }
                }
            } catch (e: IOException) {
                log("Error handling VPN traffic: ${e.message}")
                break
            }
        }
    }

    override fun onRevoke() {
        log("VPN Service revoked")
        vpnInterface?.close()
        super.onRevoke()
    }

    private fun log(message: String) {
        Log.d("MeshrabiyaVpnService", message)
    }
}
