package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.test.TestLogger
import com.ustadmobile.meshrabiya.test.assertByteArrayEquals
import com.ustadmobile.meshrabiya.test.contentRangeEqual
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import java.net.DatagramPacket
import java.net.InetAddress
import kotlin.random.Random

class VirtualDatagramSocketTest {

    @Test(timeout = 5000)
    fun givenOnIncomingPacketCalled_whenReceiveCalled_thenContentAndAddressShouldMatch(){
        val mockRouter = mock<VirtualRouter> {
            on { allocatePortOrThrow(any(), any()) }.thenAnswer {
                Random.nextInt(0, UShort.MAX_VALUE.toInt())
            }
        }

        val localVirtualAddress = 42
        val fromVirtualAddress = 43
        val fromVirtualPort = Random.nextInt(0, UShort.MAX_VALUE.toInt())

        val virtualSocket = VirtualDatagramSocket(
            port = 0,
            localVirtualAddress = localVirtualAddress,
            router = mockRouter,
            logger = TestLogger(),
        )

        val payloadSize = 1000
        val virtualPacketBuffer = Random.nextBytes(payloadSize + VirtualPacketHeader.HEADER_SIZE)
        val incomingPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = VirtualPacketHeader(
                toAddr = localVirtualAddress,
                toPort = virtualSocket.localPort,
                fromAddr = fromVirtualAddress,
                fromPort = fromVirtualPort,
                lastHopAddr = fromVirtualAddress,
                hopCount = 0,
                maxHops = 5,
                payloadSize = payloadSize
            ),
            data = virtualPacketBuffer,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE,
        )

        virtualSocket.onIncomingPacket(incomingPacket)


        val datagramBuffer = ByteArray(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
        val datagramPacket = DatagramPacket(datagramBuffer, datagramBuffer.size)
        virtualSocket.receive(datagramPacket)

        Assert.assertEquals(fromVirtualAddress, datagramPacket.address.requireAddressAsInt())
        Assert.assertEquals(fromVirtualPort, datagramPacket.port)
        Assert.assertEquals(incomingPacket.header.payloadSize, datagramPacket.length)
        assertByteArrayEquals(incomingPacket.data, incomingPacket.payloadOffset,
            datagramPacket.data, datagramPacket.offset, datagramPacket.length)
    }

    @Test(timeout = 5000)
    fun givenVirtualSocket_whenOutgoingDatagramSent_thenShouldCallRouteWithValidVirtualPacket() {
        val mockRouter = mock<VirtualRouter> {
            on { allocatePortOrThrow(any(), any()) }.thenAnswer {
                Random.nextInt(0, UShort.MAX_VALUE.toInt())
            }
        }

        val localVirtualAddress = 42
        val toVirtualAddress = 43
        val toPort = Random.nextInt(0, Short.MAX_VALUE.toInt())

        val virtualSocket = VirtualDatagramSocket(
            port = 0,
            localVirtualAddress = localVirtualAddress,
            router = mockRouter,
            logger = TestLogger()
        )

        val payloadSize = 1000
        val buffer = Random.nextBytes(payloadSize)
        val datagramPacket = DatagramPacket(buffer, 0, payloadSize)
        datagramPacket.address = InetAddress.getByAddress(toVirtualAddress.addressToByteArray())
        datagramPacket.port = toPort

        virtualSocket.send(datagramPacket)
        verify(mockRouter).route(argWhere {
            it.header.toAddr == toVirtualAddress &&
                    it.header.toPort == toPort &&
                    it.header.fromAddr == localVirtualAddress &&
                    it.header.fromPort == virtualSocket.localPort &&
                    datagramPacket.data.contentRangeEqual(datagramPacket.offset, it.data,
                        it.payloadOffset, datagramPacket.length)
        })
    }

    @Test(timeout = 5000)
    fun givenDatagramSentFromOneSocket_whenReceivedByOtherSocket_thenShouldMatch() {
        val mockRouter = mock<VirtualRouter> {
            on { allocatePortOrThrow(any(), any()) }.thenAnswer {
                Random.nextInt(0, UShort.MAX_VALUE.toInt())
            }
        }

        val addr1 = 42
        val addr2 = 43
        val logger = TestLogger()

        val socket1 = VirtualDatagramSocket(
            port = 0,
            localVirtualAddress = addr1,
            router = mockRouter,
            logger = logger,
        )

        val socket2 = VirtualDatagramSocket(
            port = 0,
            localVirtualAddress = addr2,
            router = mockRouter,
            logger = logger,
        )

        mockRouter.stub {
            on { route(any()) }.thenAnswer {
                val packet = it.arguments.first() as VirtualPacket
                if(packet.header.toPort == socket1.localPort && packet.header.toAddr == addr1)
                    socket1.onIncomingPacket(packet)
                else if(packet.header.toPort == socket2.localPort && packet.header.toAddr == addr2)
                    socket2.onIncomingPacket(packet)

                Unit
            }
        }

        val bufferOut = Random.nextBytes(1000)
        val datagramOut = DatagramPacket(bufferOut, 0, bufferOut.size)
        datagramOut.address = InetAddress.getByAddress(addr2.addressToByteArray())
        datagramOut.port = socket2.localPort

        socket1.send(datagramOut)

        val bufferIn = ByteArray(1000)
        val datagramIn = DatagramPacket(bufferIn, 0, bufferIn.size)
        socket2.receive(datagramIn)

        Assert.assertEquals(addr1, datagramIn.address.requireAddressAsInt())
        Assert.assertEquals(socket1.localPort, datagramIn.port)
        Assert.assertEquals(datagramOut.length, datagramIn.length)
        assertByteArrayEquals(
            expected = datagramOut.data,
            expectedOffset = datagramOut.offset,
            actual = datagramIn.data,
            actualOffset = datagramIn.offset,
            length = datagramOut.length
        )
    }



}