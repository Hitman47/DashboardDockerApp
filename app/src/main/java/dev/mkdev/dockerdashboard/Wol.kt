package dev.mkdev.dockerdashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

/**
 * Wake-on-LAN d'un dashboard éteint. Deux chemins, cumulés :
 *  - en Wi-Fi, le paquet magique part directement du téléphone (broadcast du
 *    sous-réseau courant + 255.255.255.255) ;
 *  - chaque autre dashboard mémorisé où l'on est connecté reçoit
 *    `POST /api/wol` : c'est lui, sur le LAN, qui broadcaste — ce qui marche
 *    aussi à distance (4G, VPN).
 */
object Wol {
    private val MAC = Regex("^([0-9a-fA-F]{2})[:\\-.]?([0-9a-fA-F]{2})[:\\-.]?([0-9a-fA-F]{2})[:\\-.]?([0-9a-fA-F]{2})[:\\-.]?([0-9a-fA-F]{2})[:\\-.]?([0-9a-fA-F]{2})$")

    fun isValidMac(mac: String) = MAC.matches(mac.trim())
    fun normalize(mac: String): String = MAC.find(mac.trim())?.groupValues?.drop(1)?.joinToString(":") { it.lowercase() } ?: mac

    private fun magicPacket(mac: String): ByteArray {
        val m = MAC.find(mac.trim())?.groupValues?.drop(1)?.map { it.toInt(16).toByte() } ?: error("MAC invalide")
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
    }

    /** Broadcast IPv4 du réseau Wi-Fi courant, ou null hors Wi-Fi. */
    fun wifiBroadcast(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val link: LinkAddress = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = link.address.address.map { it.toInt() and 0xFF }
        val prefix = link.prefixLength
        return ip.mapIndexed { i, o ->
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            o or (mask.inv() and 0xFF)
        }.joinToString(".")
    }

    /** Envoi direct depuis le téléphone. → nombre de paquets partis. */
    fun sendLocal(mac: String, broadcast: String?): Int {
        val packet = magicPacket(mac)
        val targets = listOfNotNull("255.255.255.255", broadcast).distinct()
        var sent = 0
        DatagramSocket().use { sock ->
            sock.broadcast = true
            repeat(3) {
                for (t in targets) {
                    runCatching { sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), 9)); sent++ }
                }
                Thread.sleep(150)
            }
        }
        return sent
    }

    /** Demande à un autre dashboard (où l'on a une session) de réveiller la machine. → message, ou null si pas connecté / injoignable. */
    fun sendVia(serverUrl: String, mac: String, name: String): String? = try {
        val cookie = CookieManager.getInstance().getCookie(serverUrl)
        if (cookie.isNullOrBlank() || !cookie.contains("dd_session=")) null else {
            val conn = (URL("$serverUrl/api/wol").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 5000; readTimeout = 15000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Cookie", cookie)
            }
            conn.outputStream.use { it.write(JSONObject().put("mac", mac).put("name", name).toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val j = JSONObject(conn.inputStream.bufferedReader().readText())
                "${j.optInt("sent")} paquet(s)"
            } else null
        }
    } catch (_: Exception) { null }
}
