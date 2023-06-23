package com.ustadmobile.meshrabiya.vnet.localhotspot

import kotlinx.coroutines.flow.Flow


interface LocalHotspotManager {
    suspend fun request(requestMessageId: Int, request: LocalHotspotRequest): LocalHotspotResponse

    val state: Flow<LocalHotspotState>

    val is5GhzSupported: Boolean

}