package de.astral.smbbackup.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import de.astral.smbbackup.core.HostProbe
import de.astral.smbbackup.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class WifiState(val network: Network, val ssid: String?)

/** Ergebnis der Vorbedingungsprüfung (Heim-WLAN + NAS erreichbar). */
sealed interface GateResult {
    data class Ready(val network: Network, val ssid: String) : GateResult

    /** Handy ist nicht im Heim-WLAN – kein Fehler, nur übersprungen. */
    data class NotHome(val reason: String) : GateResult

    /** Im Heim-WLAN, aber es klappt trotzdem nicht – wird als Fehler gemeldet. */
    data class Problem(val reason: String) : GateResult
}

class NetworkGate(private val context: Context) {

    /** Aktuelles WLAN (auch wenn es nicht das Standardnetz ist) oder `null`. */
    suspend fun currentWifi(): WifiState? = withTimeoutOrNull(3_000) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ssid = (caps.transportInfo as? WifiInfo)?.ssid
                        ?.removeSurrounding("\"")
                        ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
                    trySend(WifiState(network, ssid))
                }
            }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            cm.registerNetworkCallback(request, callback)
            awaitClose { cm.unregisterNetworkCallback(callback) }
        }.first()
    }

    suspend fun check(settings: AppSettings): GateResult {
        val wifi = currentWifi() ?: return GateResult.NotHome("Nicht mit einem WLAN verbunden")
        val ssid = wifi.ssid ?: return GateResult.Problem(ssidUnavailableReason())
        if (settings.homeSsids.none { it == ssid }) return GateResult.NotHome("Nicht im Heim-WLAN (verbunden mit \"$ssid\")")
        val reachable = withContext(Dispatchers.IO) {
            // Verbindung ausdrücklich über das WLAN, nie über Mobilfunk.
            HostProbe.isReachable(settings.host, settings.port, socketFactory = wifi.network.socketFactory)
        }
        if (!reachable) return GateResult.Problem("NAS ${settings.host} im WLAN \"$ssid\" nicht erreichbar")
        return GateResult.Ready(wifi.network, ssid)
    }

    private fun ssidUnavailableReason(): String {
        val pm = context.packageManager
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val locationOn = context.getSystemService(LocationManager::class.java).isLocationEnabled
        return when {
            !fine -> "WLAN-Name nicht lesbar: Standortberechtigung fehlt"
            !background -> "WLAN-Name nicht lesbar: Standort muss auf \"Immer erlauben\" stehen"
            !locationOn -> "WLAN-Name nicht lesbar: Standort ist in den Schnelleinstellungen ausgeschaltet"
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI) -> "WLAN-Name konnte nicht ermittelt werden"
            else -> "Gerät hat kein WLAN"
        }
    }
}
