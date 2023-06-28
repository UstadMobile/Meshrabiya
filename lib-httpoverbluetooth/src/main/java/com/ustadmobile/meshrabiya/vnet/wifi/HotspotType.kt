package com.ustadmobile.meshrabiya.vnet.wifi

enum class HotspotType(val flag: Int) {
    LOCALONLY_HOTSPOT(1), WIFIDIRECT_GROUP(2);

    companion object {
        fun fromFlag(flag: Int): HotspotType {
            return values().first { it.flag == flag }
        }
    }
}