package de.astral.smbbackup.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

class WakeOnLanTest {
    private val mac = byteArrayOf(0x00, 0x11, 0x32, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())

    @Test
    fun parsesCommonMacFormats() {
        assertArrayEquals(mac, WakeOnLan.parseMac("00:11:32:AB:CD:EF"))
        assertArrayEquals(mac, WakeOnLan.parseMac("00-11-32-ab-cd-ef"))
        assertArrayEquals(mac, WakeOnLan.parseMac(" 001132ABCDEF "))
    }

    @Test
    fun rejectsInvalidMac() {
        assertNull(WakeOnLan.parseMac(""))
        assertNull(WakeOnLan.parseMac("00:11:32:AB:CD"))
        assertNull(WakeOnLan.parseMac("00:11:32:AB:CD:EG"))
        assertNull(WakeOnLan.parseMac("192.168.178.20"))
    }

    @Test
    fun magicPacketHasHeaderAndSixteenRepetitions() {
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        for (i in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test
    fun computesDirectedBroadcast() {
        fun bc(ip: String, prefix: Int) =
            WakeOnLan.directedBroadcast(InetAddress.getByName(ip) as Inet4Address, prefix).hostAddress
        assertEquals("192.168.178.255", bc("192.168.178.20", 24))
        assertEquals("10.0.255.255", bc("10.0.3.7", 16))
        assertEquals("192.168.1.127", bc("192.168.1.100", 25))
        assertEquals("255.255.255.255", bc("10.1.2.3", 0))
    }

    @Test
    fun sendsPacketOverUdp() {
        // Empfänger auf localhost statt Broadcast, damit der Test überall läuft.
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2_000
            val sent = WakeOnLan.send(mac, listOf(InetAddress.getLoopbackAddress()), port = receiver.localPort, repeat = 1)
            assertEquals(1, sent.size)
            val buffer = ByteArray(200)
            val received = DatagramPacket(buffer, buffer.size)
            receiver.receive(received)
            assertArrayEquals(WakeOnLan.magicPacket(mac), buffer.copyOf(received.length))
        }
    }
}
