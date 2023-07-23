package com.ustadmobile.meshrabiya.test

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.vnet.NodeConfig
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import kotlinx.serialization.json.Json
import org.mockito.kotlin.mock
import java.util.UUID


class TestVirtualNode(
    uuidMask: UUID = UUID.randomUUID(),
    localNodeAddress: Int = randomApipaAddr(),
    port: Int = 0,
    logger: MNetLogger = MNetLoggerStdout(),
    override val hotspotManager: MeshrabiyaWifiManager = mock { },
    json: Json,
    config: NodeConfig = NodeConfig(maxHops = 5),
) : VirtualNode(
    uuidMask = uuidMask,
    port = port,
    logger = logger,
    json = json,
    config = config,
    localNodeAddress = localNodeAddress,
)