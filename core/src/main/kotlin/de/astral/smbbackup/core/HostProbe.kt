package de.astral.smbbackup.core

import java.net.InetSocketAddress
import javax.net.SocketFactory

object HostProbe {
    /** Prüft per TCP-Verbindungsaufbau, ob der SMB-Dienst des Hosts antwortet. */
    fun isReachable(
        host: String,
        port: Int = 445,
        timeoutMillis: Int = 3_000,
        socketFactory: SocketFactory = SocketFactory.getDefault(),
    ): Boolean = try {
        socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            true
        }
    } catch (_: Exception) {
        false
    }
}
