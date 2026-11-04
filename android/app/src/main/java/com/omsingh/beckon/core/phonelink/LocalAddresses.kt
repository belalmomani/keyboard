package com.omsingh.beckon.core.phonelink

import com.omsingh.beckon.core.wifi.LanInterfaces
import java.net.Inet4Address
import java.net.NetworkInterface

/** This phone's addresses on the local network, best first, for the code the other phone scans. */
object LocalAddresses {
    fun ipv4(): List<String> = try {
        val up = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val chosen = LanInterfaces.pick(up.map { it.name }).toSet()
        up.filter { it.name in chosen }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }
}
