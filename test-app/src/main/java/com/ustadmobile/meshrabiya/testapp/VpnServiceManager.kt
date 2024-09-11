package com.ustadmobile.meshrabiya.testapp

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.meshrabiya.lib_vpn.MeshrabiyaVpnService

class VpnServiceManager(private val context: Context) {
    fun prepareVpn(): Intent? {
        return VpnService.prepare(context)
    }

    fun startVpn() {
        val intent = Intent(context, MeshrabiyaVpnService::class.java)
        context.startService(intent)
    }

    fun stopVpn() {
        val intent = Intent(context, MeshrabiyaVpnService::class.java)
        context.stopService(intent)
    }
}