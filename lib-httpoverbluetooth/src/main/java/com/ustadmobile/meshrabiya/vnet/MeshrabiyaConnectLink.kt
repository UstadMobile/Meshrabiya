package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.vnet.bluetooth.BluetoothConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotConfig
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.URLDecoder
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import java.net.URLEncoder

/**
 *
 */
data class MeshrabiyaConnectLink(
    val uri: String,
    val virtualAddress: Int,
    val hotspotConfig: HotspotConfig?,
    val bluetoothConfig: BluetoothConfig?
) {

    companion object {

        const val PROTO = "meshrabiya"

        private const val PROTO_PREFIX = "${PROTO}://"

        fun fromComponents(
            nodeAddr: Int,
            port: Int,
            hotspotConfig: HotspotConfig?,
            bluetoothConfig: BluetoothConfig?,
            json: Json,
        ) : MeshrabiyaConnectLink {
            val uri = buildString {
                append("$PROTO_PREFIX${nodeAddr.addressToDotNotation()}:$port}/?")
                if(hotspotConfig != null) {
                    append("hotspot=")
                    append(
                        URLEncoder.encode(json.encodeToString(
                            HotspotConfig.serializer(), hotspotConfig
                        ), "UTF-8")
                    )
                }
                if(hotspotConfig != null && bluetoothConfig != null) {
                    append("&")
                }
                if(bluetoothConfig != null) {
                    append("bluetooth=")
                    append(
                        URLEncoder.encode(json.encodeToString(
                            BluetoothConfig.serializer(), bluetoothConfig
                        ), "UTF-8")
                    )
                }
            }

            return MeshrabiyaConnectLink(
                uri = uri,
                virtualAddress = nodeAddr,
                hotspotConfig = hotspotConfig,
                bluetoothConfig = bluetoothConfig,
            )
        }

        fun parseUri(uri: String, json: Json): MeshrabiyaConnectLink {
            val uriLowerCase = uri.lowercase()
            if(!uriLowerCase.startsWith(PROTO_PREFIX))
                throw IllegalArgumentException("Meshrabiya connect url must start with $PROTO://")

            val addr = uri.substringAfter(PROTO_PREFIX).substringBefore(":")
            val inetAddr = InetAddress.getByName(addr)

            val searchStr = uri.substringAfter("?")
            val searchComponents = searchStr.split('&').map { param ->
                param.split("=", limit = 2).let {
                    Pair(URLDecoder.decode(it[0], "UTF-8"), URLDecoder.decode(it[1], "UTF-8"))
                }
            }.toMap()
            val hotspotConfig = searchComponents["hotspot"]?.let {
                json.decodeFromString(HotspotConfig.serializer(), it)
            }

            val bluetoothConfig = searchComponents["bluetooth"]?.let {
                json.decodeFromString(BluetoothConfig.serializer(), it)
            }

            return MeshrabiyaConnectLink(
                uri = uri,
                virtualAddress = inetAddr.requireAddressAsInt(),
                hotspotConfig = hotspotConfig,
                bluetoothConfig = bluetoothConfig,
            )
        }

    }

}