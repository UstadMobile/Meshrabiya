package com.ustadmobile.meshrabiya.test

import android.content.Context
import com.ustadmobile.meshrabiya.ext.asInetAddress
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
    localNodeAddress: Int = randomApipaAddr(),
    port: Int = 0,
    logger: MNetLogger = MNetLoggerStdout(),
    override val meshrabiyaWifiManager: MeshrabiyaWifiManager = mock { },
    json: Json,
    config: NodeConfig = NodeConfig(maxHops = 5),
    private val mockContext: Context? = null,
) : VirtualNode(
    port = port,
    logger = logger,
    json = json,
    config = config,
    address = localNodeAddress.asInetAddress(),
) {
    /**
     * Provides mock context for testing. Returns null by default to avoid service initialization in tests.
     */
    override fun getContext(): Context? = mockContext
}
