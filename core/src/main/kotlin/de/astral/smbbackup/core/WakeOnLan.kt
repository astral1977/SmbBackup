package de.astral.smbbackup.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Wake-on-LAN: weckt ein Gerät mit einem "Magic Packet" (UDP-Broadcast im lokalen Netz). */
object WakeOnLan {
    const val PORT = 9
    private val macPattern = Regex("""^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$""")

    /** MAC-Adresse wie "00:11:32:AB:CD:EF", "00-11-32-AB-CD-EF" oder "001132ABCDEF"; sonst `null`. */
    fun parseMac(text: String): ByteArray? {
        val trimmed = text.trim()
        if (!macPattern.matches(trimmed)) return null
        val hex = trimmed.filter { it != ':' && it != '-' }
        if (hex.length != 12) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** 6 × 0xFF, danach 16 × die MAC-Adresse. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC-Adresse muss 6 Bytes lang sein" }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
    }

    /** Broadcast-Adresse des Netzes, z. B. 192.168.178.20/24 → 192.168.178.255. */
    fun directedBroadcast(address: Inet4Address, prefixLength: Int): Inet4Address {
        require(prefixLength in 0..32)
        val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val hostMask = if (prefixLength == 0) -1 else (1 shl (32 - prefixLength)) - 1
        val broadcast = ip or hostMask
        val bytes = ByteArray(4) { (broadcast shr (24 - it * 8)).toByte() }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    /**
     * Sendet das Magic Packet an alle [targets].
     * @param prepare z. B. um den Socket an das WLAN zu binden
     * @return Adressen, an die erfolgreich gesendet wurde
     */
    fun send(
        mac: ByteArray,
        targets: Collection<InetAddress>,
        port: Int = PORT,
        repeat: Int = 3,
        prepare: (DatagramSocket) -> Unit = {},
    ): List<InetAddress> {
        val packet = magicPacket(mac)
        val sent = mutableListOf<InetAddress>()
        var lastError: Exception? = null
        DatagramSocket().use { socket ->
            socket.broadcast = true
            prepare(socket)
            for (target in targets.distinct()) {
                try {
                    repeat(repeat) { socket.send(DatagramPacket(packet, packet.size, target, port)) }
                    sent += target
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        if (sent.isEmpty()) {
            throw SmbBackupException("Wake-on-LAN konnte nicht gesendet werden: ${lastError?.let(::describeError) ?: "keine Zieladresse"}", lastError)
        }
        return sent
    }
}
