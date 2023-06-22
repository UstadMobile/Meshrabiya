package com.ustadmobile.meshrabiya.vnet.localhotspot

import kotlinx.coroutines.flow.Flow


interface LocalHotspotManager {
    suspend fun request(request: LocalHotspotRequest): LocalHotspotRequestResult

    val state: Flow<LocalHotspotState>

    val is5GhzSupported: Boolean

}