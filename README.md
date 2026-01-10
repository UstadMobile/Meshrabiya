# Meshrabiya
## MeshSettings

**Location:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/settings/MeshSettings.kt`

**Purpose:**
MeshSettings provides centralized configuration and runtime settings for the Meshrabiya library. It manages mesh-related preferences such as replica count and is intended for use by Meshrabiya components only. Use `MeshSettings.init(context)` to initialize and access mesh settings within the library.

Meshrabiya is a mesh network for Android that operates over WiFi. It allows applications
to seamlessly communicate over multiple hops and multiple WiFi direct and/or Local Only Hotspots.
Each device is given a "virtual" IP address (typically a random auto-generated address
e.g. 169.254.x.y). Applications can then use the provided SocketFactory and/or DatagramSocket class
to communicate with other nodes over multiple hops as if they were directly connected. This works
with various higher level networking libraries such as OkHttp. 

It is intended for use in situations where multiple Android devices need to communicate with each 
other and a WiFi access point is not available e.g. schools and health clinics without WiFi 
infrastructure, when hiking, etc. WiFi enables high-speed connections with tests obtaining 300Mbps+.
Using multiple hops over multiple WiFi direct groups enables more devices to connect than is possible
using a single device hotspot.

Meshrabiya is entirely open-source and does not have any proprietary dependencies (e.g. it works 
with Android Open Source Project devices and does not use/require Google Play Services, Nearby 
Connections API, etc).


Meshrabiya provides socket factories (for both TCP and UDP) that can create sockets to route data 
between nodes over multiple hops as if they were directly connected. The socket factory can also
be used with other networking libraries (e.g. OkHttp) to make it easy to send/receive data over the
virtual mesh.

How it works:

* Node A creates a hotspot using a [Wifi Direct Group](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager#createGroup(android.net.wifi.p2p.WifiP2pManager.Channel,%20android.net.wifi.p2p.WifiP2pManager.ActionListener)). 
  or [Local Only Hotspot](https://developer.android.com/guide/topics/connectivity/localonlyhotspot).
  It generates a "connect link" that includes the hotspot SSID, passphrase, ipv6 link local address
  (if a WiFi direct group), BSSID (where possible), and the service port number.
* Node B obtains the connect link by scanning a QR code (this could also potentially be discovered via
  Bluetooth Low Energy Advertising). Node B connects to the hotspot of 
  Node A using the [Wifi Bootstrap API](https://developer.android.com/guide/topics/connectivity/wifi-bootstrap). 
  If Node A created a WiFi direct group, then Node B will use the ipv6 link local address to reach Node 
  A. If Node A created a Local Only Hotspot, then Node B will use the DHCP server address to reach 
  Node A. Node B sends a UDP packet to the known address / service port of Node A to enable Node A 
  to discover Node B. Node A and Node B can now communicate. The connection is done using 
  [WifiNetworkSpecifier](https://developer.android.com/guide/topics/connectivity/wifi-bootstrap) on 
  Android 10+ and using WifiManager on prior versions. On Android 10+ the user will normally only see  
  a confirmation dialog the first time a connection is established between two nodes (except when 
  it is not possible for a single node to maintain the same SSID and/or when the BSSID is unknown).
* Node B creates its own hotspot. Node C (and so forth) can connect. All nodes periodically broadcast
  originator messages that include their virtual IP address and connect link. The propogation of
  originator messages is subject to limits on the maximum number of hops. When a node receives an
  originator message it knows the other node, and it knows the next hop if it wants to send traffic
  to that node. This is based on the [BATMAN Originator Message concept](https://www.open-mesh.org/doc/batman-adv/OGM.html).
* Each node can simultaneously operate both a hotspot for incoming connections and make one outgoing
  connection via its WiFi station (client). There are two possible ways to do this, each of which has
  some advantages and disadvantages:
 * __WiFi Direct Group__ Almost all Android devices (except it seems Android 11+ devices that support 
  [WiFi station - Access Point concurrency](https://developer.android.com/reference/android/net/wifi/WifiManager#isStaApConcurrencySupported())) 
  can create a WiFi direct group and
  simultaneously remain connected to a WiFi access point (as a station). Creating a WiFi direct 
  group creates a hotspot for "legacy devices" that operates a normal hotspot (and does not share 
  Internet). IPv6 link local must be used to avoid an IP conflict
  due to the fact that Android assigns the IP address 192.168.49.1 to all nodes that operate as a 
  WiFi direct group owner. We also use the link local IPv6 address to attempt to calculate the MAC Address, 
  which needs to be specified to avoid a user prompt each time a user reconnects. Using the link local
  address to calculate the MAC address avoids the need to use CompanionDeviceManager to discover the 
  Mac address (which requires using an intent result and results in users seeing two dialog boxes on 
  Android 10).
  It is possible to specify the hotspot SSID, passphrase and band (2.4Ghz or 5Ghz) on any Android 10+
  device.
 * __Local Only Hotspot__ This is supported on all Android 8 devices, however it can only operate
 concurrently with being connected to another hotspot if [WiFi station - Access Point concurrency](https://developer.android.com/reference/android/net/wifi/WifiManager#isStaApConcurrencySupported())
 is supported. This is only available on Android 11+ devices and requires hardware support. Generally
 lower-end devices are less likely to have this feature. Android generates a random subnet range so 
 there is no IP address conflict when one device is both operating as a Local Only Hotspot provider 
 and connected to another Local Only Hotspot at the same time. It is only possible to specify the 
 hotspot SSID, passphrase, and band on Android 13+ using a hidden API.

Want to try it yourself? Download the test app APK from [releases](https://github.com/UstadMobile/Meshrabiya/releases).

Want to collaborate on development? Join us on [#meshrabiya:matrix.org](https://matrix.to/#/#meshrabiya:matrix.org).

![Diagram](doc/android-wifi-networking.svg)

## Getting started

Add repository to settings.gradle :
```
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
      ...
      maven { url "https://devserver3.ustadmobile.com/maven2/" }
    }
}       
```

Add the dependency
```
implementation "com.github.UstadMobile.Meshrabiya:lib-meshrabiya:0.1-snapshot"
```

### Connect devices

Create a Virtual Node:

```
//Create a DataStore instance that Meshrabiya can use to remember networks etc.
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshr_settings")

val myNode = AndroidVirtualNode(
    appContext = applicationContext,
    dataStore = applicationContext.dataStore,
    //optionally - set address, network prefix length, etc.
)

```

Create a hotspot on one node:

```
myNode.setWifiHotspotEnabled(
  enabled = true,
  preferredBand = ConnectBand.BAND_5GHZ,
)

val connectLink = myNode.state.filter {
   it.connectUri != null
}.first()

```

Use the connect link to connect from another node:
```

val connectLink = ... //Get this from QR code scan etc.
val connectConfig = MeshrabiyaConnectLink.parseUri(connectLink).hotspotConfig
if(connectConfig != null) {
  myNode.connectAsStation(connectConfig)
}

```

### Exchange data using TCP

1. On the server side - create a normal server socket:
```
val serverVirtualAddr: InetAddress = myNode.address 
val serverSocket = ServerSocket(port)
```

2. On the client side - use the socket factory to create a socket
```
val socketFactory = myNode.socketFactory
val clientSocket = socketFactory.createSocket(serverVirtualAddr, port)
```

The Socket Factory uses a "socket chain" under the hood. It will lookup the next hop to reach the
given destination. It will then connect to the next hop and write its destination to socket stream,
similar to how an http proxy uses the host header. Each node runs a chain socket forwarding server. 
Once the next hop is the destination (e.g. it reaches a node that has a direct connection to the 
destination node), then the socket is connected to the destination port. See ChainSocketServer for
further details.

The Socket factory will fallback to using the system default socket factory for any destination that
is not on the virtual network (e.g where the ip address does not match the netmask of the virtual 
node). It is therefor possible to use the socket factory anywhere, even when connections to non-virtual
destinations are required - e.g. it can be used with an OKHttp Client and the client will be able to
connect to both virtual and non-virtual addresses.
e.g.
```
val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .socketFactory(myNode.socketFactory)
            .build()
```

### Exchange data using UDP

Create a DatagramSocket with a given port (or use 0 to get a random port assignment)
```
val datagramSocket = myNode.createBoundDatagramSocket(port)
```

The socket can be used the same as a normal DatagramSocket (e.g. by using send/receive), but it will 
send/receive ONLY over the virtual network. Broadcast packets are supported by setting the 
destination address to 255.255.255.255

### Known issues

Instrumented test debug: You must go to test settings, debug tab, and change to "java only" 
debugger type. Thank you, Google.

## 🍴 Fork Information

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)

This is a **highly modified fork** of the original Meshrabiya library developed by UstadMobile FZ-LLC. Our fork extends the core mesh networking capabilities with distributed computing and storage features for the orbot-abhaya-android project.

### 🆚 Upstream vs Fork

**Upstream Repository**: [UstadMobile/Meshrabiya](https://github.com/UstadMobile/Meshrabiya)  
**Fork Repository**: [dreadstar/Meshrabiya](https://github.com/dreadstar/Meshrabiya)  
**Integration**: Used in [orbot-abhaya-android](https://github.com/dreadstar/orbot-abhaya-android)

### 🔄 Major Modifications

This fork includes significant architectural enhancements beyond the original mesh networking:

#### 1. **Distributed Storage Layer**
- **Data Persistence**: Distributed storage across mesh nodes
- **Replication**: Automatic data replication with configurable redundancy
- **Content Discovery**: Advanced content routing and discovery mechanisms
- **Storage Coordination**: Coordinated storage allocation and management

#### 2. **Distributed Compute Framework**
- **Task Orchestration**: Distributed workflow coordination and execution
- **Compute Services**: Library for distributed processing across mesh nodes
- **Resource Management**: Dynamic resource allocation and load balancing
- **Service Interoperability**: Seamless compute+storage integration

#### 3. **Enhanced Mesh Management**
- **Coordinator Adapters**: Advanced mesh coordination requirements
- **EmergentRoleManager**: Power-aware role management and constraints
- **VirtualNode Improvements**: Race condition fixes and coroutine enhancements
- **Service Lifecycle**: Enhanced mesh service lifecycle management

#### 4. **Gateway & Integration**
- **Internet Gateway**: Improved mesh-to-internet gateway functionality
- **Testing Framework**: Enhanced Robolectric-based integration testing
- **API Extensions**: Backward-compatible API extensions for distributed features

### 🏗️ Architecture Differences

```
Original Meshrabiya:          Enhanced Fork:
┌─────────────────┐          ┌─────────────────┐
│   Mesh Core     │          │   Mesh Core     │
│                 │          │                 │
│  ┌───────────┐  │          │  ┌───────────┐  │
│  │ Routing   │  │          │  │ Routing   │  │
│  │ Discovery │  │    →     │  │ Discovery │  │
│  │ Hotspot   │  │          │  │ Hotspot   │  │
│  └───────────┘  │          │  └───────────┘  │
└─────────────────┘          │                 │
                              │  ┌───────────┐  │
                              │  │ Dist.     │  │
                              │  │ Storage   │  │
                              │  └───────────┘  │
                              │                 │
                              │  ┌───────────┐  │
                              │  │ Dist.     │  │
                              │  │ Compute   │  │
                              │  └───────────┘  │
                              └─────────────────┘
```

## Meshrabiya API: Features & Usage

Meshrabiya exposes a unified API for mesh networking, distributed storage, and compute services. The API is designed for reliability, extensibility, and ease of integration in Android apps.

### Key Features

- **Mesh Node Management:** Create/configure virtual nodes, assign virtual IPs, manage roles and routing.
- **Hotspot & Connectivity:** Create WiFi Direct/Local Only Hotspots, generate/parse connect links, connect peers.
- **Socket Factories:** TCP/UDP socket factories for multi-hop mesh communication, compatible with OkHttp.
- **Distributed Storage:** Enable/disable participation, allocate storage, manage drop folders, store/retrieve/stream/delete files.
- **Distributed Compute:** Add/start/cancel tasks, query job types, participate in distributed service layers.
- **Gateway & Proxy Integration:** Enable Tor/Internet gateway, route traffic via proxy, monitor gateway status.
- **Event & State Management:** Register listeners for mesh state, peer count, file/service events, access real-time stats.
- **Settings & Configuration:** Centralized runtime configuration via `MeshSettings`.

### Sample Usage

```kotlin
// 1. Initialize the API singleton and mesh node
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshr_settings")
val meshrabiyaApi: MeshrabiyaApi = MeshrabiyaApiImpl.getInstance()
meshrabiyaApi.initMesh(applicationContext)

// 2. Start mesh networking and create a hotspot
meshrabiyaApi.startMesh { result ->
    if (result.isSuccess) {
        // Mesh started, hotspot active
    }
}

// 3. Connect to another node using a connect link
val connectLink = meshrabiyaApi.getConnectLink()
val hotspotConfig = MeshrabiyaConnectLink.parseUri(connectLink).hotspotConfig
if (hotspotConfig != null) {
    // Connect as station (API method if exposed)
    // myNode.connectAsStation(hotspotConfig)
}

// 4. Exchange data using TCP sockets
val serverSocket = ServerSocket(port)
val socketFactory = meshrabiyaApi.getSocketFactory()
val clientSocket = socketFactory.createSocket(serverVirtualAddr, port)

// 5. Exchange data using UDP sockets
val datagramSocket = meshrabiyaApi.createBoundDatagramSocket(port)
datagramSocket.send(...)

// 6. Enable distributed storage participation
meshrabiyaApi.setStorageParticipationEnabled(true) { result ->
    if (result.isSuccess) {
        // Storage participation enabled
    }
}

// 7. Store and retrieve files
meshrabiyaApi.storeFile(file) { result ->
    result.onSuccess { fileId ->
        // File stored, fileId available
    }
}
meshrabiyaApi.retrieveFile(fileId) { result ->
    result.onSuccess { file ->
        // File retrieved
    }
}

// 8. Enable Tor gateway and proxy routing
meshrabiyaApi.setTorGatewayEnabled(true) { result -> /* ... */ }
meshrabiyaApi.setProxy("127.0.0.1", 9050) // Set Tor SOCKS proxy
meshrabiyaApi.setProxyActive(true)        // Activate proxy routing

// 9. Register event listeners
meshrabiyaApi.setOnMeshStateChanged { newState -> /* ... */ }
meshrabiyaApi.setOnFileRetrieved { fileId, file -> /* ... */ }
```

**Best Practices:**
- Always initialize the API singleton once per app lifecycle.
- Use the canonical interface (`MeshrabiyaApi`) for all interactions.
- Register listeners early for real-time updates.
- Use provided socket factories for all mesh communications.
- Manage storage and compute participation via API methods.
- Use connect links for peer discovery and connection.
- For gateway/proxy features, ensure correct port and host are set based on Orbot or other proxy services.

See [MeshrabiyaApi.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt) and [MeshrabiyaApiImpl.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt) for full API details.
___
### 🔄 Upstream Compatibility

- **API Compatibility**: Maintains backward compatibility with original Meshrabiya API
- **Selective Updates**: Incorporates upstream security fixes and performance improvements
- **Extension Points**: New features use extension interfaces to avoid conflicts
- **Migration Path**: Existing Meshrabiya applications can upgrade with minimal changes

### 🛠️ Building the Fork

```bash
# Clone the fork
git clone https://github.com/dreadstar/Meshrabiya.git
cd Meshrabiya

# Build all modules
./gradlew build

# Run tests
./gradlew test

# Build library AAR
./gradlew :lib-meshrabiya:assembleRelease
```

### 🧪 Testing

```bash
# Unit tests
./gradlew :lib-meshrabiya:test

# Integration tests (requires robolectric)
./gradlew :lib-meshrabiya:testDebugUnitTest

# Test app (for manual testing)
./gradlew :test-app:assembleDebug
```

### 🔀 Synchronizing with Upstream

We periodically review upstream changes and selectively integrate:

1. **Security Fixes**: Always integrated
2. **Performance Improvements**: Evaluated and integrated where compatible
3. **API Changes**: Carefully reviewed for backward compatibility
4. **New Features**: Integrated if they don't conflict with our extensions

### 📋 Modification Log

Recent major modifications (see full commit history for details):

- **2025-01**: Distributed Storage & Compute integration
- **2024-12**: Enhanced mesh coordination and role management  
- **2024-11**: Gateway integration improvements
- **2024-10**: Architecture modernization and build fixes
- **2024-09**: VirtualNode race condition resolution

## 📄 License

This fork maintains the original **LGPL-3.0** license with dual copyright:

- **Original Work**: Copyright © 2023 UstadMobile FZ-LLC
- **Fork Modifications**: Copyright © 2025 Tyrone Thomas/BreakThrough Technologies

### What This Means

✅ **Use in Any Application**: Your app can use any license (including proprietary)  
✅ **Commercial Use**: No restrictions for commercial applications  
✅ **Dynamic Linking**: No license requirements on your application code  
✅ **Modification Allowed**: You can modify the library for your needs  
⚠️ **Share Improvements**: Modifications to the library itself must remain LGPL  
⚠️ **Static Linking**: Requires your app to be LGPL compatible  

### Why LGPL-3.0?

1. **Maximum Adoption**: Allows proprietary applications to use the mesh networking
2. **Community Growth**: Ensures improvements to the library benefit everyone  
3. **Upstream Compatibility**: Maintains same license as original project

### Questions & Support

- **Fork Issues**: [GitHub Issues](https://github.com/dreadstar/orbot-abhaya-android/issues)
- **Upstream Issues**: [UstadMobile Issues](https://github.com/UstadMobile/Meshrabiya/issues)  
- **License Questions**: See [THIRD_PARTY_LICENSES.md](https://github.com/dreadstar/orbot-abhaya-android/blob/master/THIRD_PARTY_LICENSES.md)
- **Integration Help**: [Project Discussions](https://github.com/dreadstar/orbot-abhaya-android/discussions)

---

**Original Meshrabiya** Copyright © 2023 UstadMobile FZ-LLC  
**Fork Enhancements** Copyright © 2025 Tyrone Thomas/BreakThrough Technologies  
Licensed under LGPL-3.0 - see [LICENSE](./LICENSE) and [NOTICE](./NOTICE) for details.


