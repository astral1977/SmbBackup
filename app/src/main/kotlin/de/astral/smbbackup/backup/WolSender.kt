package de.astral.smbbackup.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import de.astral.smbbackup.core.SmbBackupException
import de.astral.smbbackup.core.WakeOnLan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

/** Sendet Wake-on-LAN an das NAS, ausschließlich über das WLAN. */
class WolSender(private val context: Context) {

    /** @return lesbare Beschreibung, wohin gesendet wurde */
    suspend fun wake(macText: String, network: Network? = null): String {
        val mac = WakeOnLan.parseMac(macText)
            ?: throw SmbBackupException("Ungültige MAC-Adresse \"$macText\" (Format z. B. 00:11:32:AB:CD:EF)")
        val wifi = network ?: NetworkGate(context).currentWifi()?.network
            ?: throw SmbBackupException("Nicht mit einem WLAN verbunden")
        val targets = broadcastTargets(wifi)
        val sent = withContext(Dispatchers.IO) {
            WakeOnLan.send(mac, targets) { socket -> wifi.bindSocket(socket) }
        }
        return "Wake-on-LAN gesendet an ${sent.joinToString { it.hostAddress.orEmpty() }}"
    }

    /** Broadcast-Adresse des WLANs (z. B. 192.168.178.255) und allgemeiner Broadcast. */
    private fun broadcastTargets(network: Network): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val directed = cm.getLinkProperties(network)?.linkAddresses.orEmpty()
            .mapNotNull { link ->
                (link.address as? Inet4Address)?.let { WakeOnLan.directedBroadcast(it, link.prefixLength) }
            }
        return directed + InetAddress.getByName("255.255.255.255")
    }
}
