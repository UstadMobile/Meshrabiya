package com.ustadmobile.meshrabiya.vnet.wifi

enum class HotspotStatus {
    STARTED, STARTING, STOPPED,

    @Suppress("unused") //Reserved for future use
    STOPPING;

    fun isSettled(): Boolean {
        return this == STARTED || this == STOPPED
    }
}
