package dev.red74.prismplayer.pairing

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
    /** The TV's private IPv4 address on Wi-Fi/Ethernet, or null when it is not on a local network. */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
