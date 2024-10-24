package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.ustadmobile.meshrabiya.testapp.VpnServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class VpnTestViewModel(application: Application) : AndroidViewModel(application) {
    private val vpnServiceManager = VpnServiceManager(application)
    private val _vpnStatus = MutableStateFlow(VpnStatus.DISCONNECTED)
    val vpnStatus: StateFlow<VpnStatus> = _vpnStatus

    fun prepareVpn(): Intent? {
        return vpnServiceManager.prepareVpn()
    }

    fun startVpn() {
        vpnServiceManager.startVpn()
        _vpnStatus.value = VpnStatus.CONNECTED
    }

    fun stopVpn() {
        vpnServiceManager.stopVpn()
        _vpnStatus.value = VpnStatus.DISCONNECTED
    }

}

enum class VpnStatus {
    DISCONNECTED, CONNECTED
}




