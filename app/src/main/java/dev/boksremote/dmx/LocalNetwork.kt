package dev.boksremote.dmx

import java.net.Inet4Address
import java.net.NetworkInterface

/** One IPv4 address the phone can receive DMX on, with its subnet and interface. */
data class LocalAddress(val ip: Inet4Address, val prefixLength: Int, val interfaceName: String) {
    val text: String get() = ip.hostAddress ?: ""
}

/**
 * The phone's LAN-facing IPv4 addresses. Read from the interfaces rather than ConnectivityManager:
 * a lighting network usually has no internet, so Android keeps cellular as the default network,
 * and a hotspot or USB-tether interface the desk joins isn't an Android "Network" at all.
 */
object LocalNetwork {
    // Cellular and VPN interfaces; DMX never arrives there.
    private val EXCLUDED_PREFIXES = listOf("rmnet", "ccmni", "v4-", "clat", "dummy", "tun", "ipsec", "ppp")

    fun addresses(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && EXCLUDED_PREFIXES.none { p -> it.name.startsWith(p) } }
            .flatMap { i ->
                i.interfaceAddresses.mapNotNull { a -> (a.address as? Inet4Address)?.let { LocalAddress(it, a.networkPrefixLength.toInt(), i.name) } }
            }
            .sortedBy { if (it.interfaceName.startsWith("wlan")) 0 else 1 } // Wi-Fi client first, the common setup
    } catch (e: Exception) {
        emptyList()
    }

    /** The local address on the same subnet as [peer] (the one that peer can reach), else the first. */
    fun facing(peer: Inet4Address, candidates: List<LocalAddress>): LocalAddress? =
        candidates.firstOrNull { sameSubnet(it.ip, peer, it.prefixLength) } ?: candidates.firstOrNull()

    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 0..32) return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    private fun toInt(a: Inet4Address) = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
}
