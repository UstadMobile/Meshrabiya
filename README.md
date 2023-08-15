# Meshrabiya

Meshrabiya is a virtual mesh network for Android that operates over WiFi. It allows applications
to seamlessly communicate over multiple hops and multiple WiFi direct group networks using a 
"virtual" IP address (typically a random auto-generated address e.g. 169.254.x.y). It uses normal
runtime permissions that can be granted by the user and does not require root permissions.

It is intended for use in situations where multiple Android devices need to communicate with each 
other and a WiFi access point is not available e.g. schools and health clinics without WiFi 
infrastructure, when hiking, etc. WiFi enables high-speed connections with tests obtaining 300Mbps+.
Using multiple hops over multiple WiFi direct groups enables more devices to connect than is possible
using a single device hotspot.

Meshrabiya provides socket factories (for both TCP and UDP) that can create route data over multiple
hops as if they were directly connected.

How it works:

* Node A creates a hotspot [Wifi Direct Group](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager#createGroup(android.net.wifi.p2p.WifiP2pManager.Channel,%20android.net.wifi.p2p.WifiP2pManager.ActionListener)). 
  It generates a "connect link" that includes the hotspot SSID, passphrase, ipv6 link local address, 
  and the service port number.
* Node B obtains the connect link by scanning a QR code (this could also potentially be sent over
  Bluetooth Low Energy and/or Wifi Direct Service Discovery). Node B connects to the hotspot of 
  Node A using the [Wifi Bootstrap API](https://developer.android.com/guide/topics/connectivity/wifi-bootstrap).
  Node B sends a UDP packet to the ipv6 address / service port of Node A to enable Node A to discover
  Node B. Node A and Node B can now communicate.
* Node B creates its own hotspot. Node C (and so forth) can connect. All nodes periodically broadcast
  originator messages that include their virtual IP address and connect link. The propogation of
  originator messages is subject to limits on the maximum number of hops. When a node receives an
  originator message it knows the other node, and it knows the next hop if it wants to send traffic
  to that node. This is based on the [BATMAN Originator Message concept](https://www.open-mesh.org/doc/batman-adv/OGM.html).
* Each node can simultaneously operate both a hotspot for incoming connections and make one outgoing
  connection via its WiFi station (client). IPv6 link local addresses are used to avoid an IP conflict
  due to the fact that Android assigns the IP address 192.168.49.1 to all nodes that operate as a 
  WiFi direct group owner.

Want to try it yourself? Download the test app APK from [releases](releases/).

![Diagram](doc/android-wifi-networking.svg)

## Getting started

Add the dependency
```

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

//Optional - but recommended - use CompanionDeviceManager to associate the app with the hotspot
// to avoid dialogs when reconnecting

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

### Exchange data using UDP

Create a DatagramSocket with a given port (or use 0 to get a random port assignment)
```
val datagramSocket = myNode.createBoundDatagramSocket(port)
```

The socket can be used the same as a normal DatagramSocket (e.g. by using send/receive), but it will 
send/receive ONLY over the virtual network.

### Known issues

Instrumented test debug: You must go to test settings, debug tab, and change to "java only" 
debugger type. Thank you, Google.
