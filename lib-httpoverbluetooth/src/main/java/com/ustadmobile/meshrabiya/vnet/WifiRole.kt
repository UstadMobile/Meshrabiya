package com.ustadmobile.meshrabiya.vnet

enum class WifiRole {

    NONE, LOCAL_ONLY_HOTSPOT, WIFI_DIRECT_GROUP_OWNER, CLIENT,

    @Suppress("unused") //Reserved for future use
    CLIENT_RELAY,

}