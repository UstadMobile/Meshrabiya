package com.ustadmobile.meshrabiya.vnet.bluetooth

import kotlinx.serialization.Serializable

@Serializable
data class MeshrabiyaBluetoothState(
    val deviceName: String? = null,
) {
}