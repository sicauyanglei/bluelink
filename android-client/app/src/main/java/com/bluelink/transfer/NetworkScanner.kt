package com.bluelink.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

object NetworkScanner {
    private const val TCP_PORT = 9000
    private const val TIMEOUT_MS = 500

    data class DiscoveredServer(
        val ip: String,
        val name: String = "PC服务器"
    )

    // Get the current device's IP and subnet
    fun getLocalIP(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // Get the local subnet
    fun getLocalSubnet(): String? {
        val localIP = getLocalIP() ?: return null
        val parts = localIP.split(".")
        if (parts.size == 4) {
            return parts.take(3).joinToString(".")
        }
        return null
    }

    // Get all local IPs
    fun getAllLocalIPs(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ips
    }

    // Scan the local subnet for our server - full scan
    suspend fun scanNetwork(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredServer>()
        val localSubnet = getLocalSubnet() ?: return@withContext discovered

        // Common hotspot subnets to scan
        val subnets = mutableListOf(localSubnet)

        // Add common hotspot subnets if we're on a hotspot
        val localIP = getLocalIP() ?: ""
        when {
            localIP.startsWith("192.168.43.") -> {
                subnets.add("192.168.43")
                subnets.add("192.168.44")
            }
            localIP.startsWith("192.168.44.") -> {
                subnets.add("192.168.43")
                subnets.add("192.168.44")
            }
            localIP.startsWith("10.89.247.") -> {
                subnets.add("10.89.247")
            }
            localIP.startsWith("192.168.1.") -> {
                subnets.add("192.168.1")
            }
            localIP.startsWith("192.168.0.") -> {
                subnets.add("192.168.0")
            }
        }

        for (subnet in subnets.distinct()) {
            // Scan IPs .1 to .254
            for (i in 1..254) {
                val ip = "$subnet.$i"
                if (isServerReachable(ip)) {
                    discovered.add(DiscoveredServer(ip, "PC服务器"))
                }
            }
        }

        discovered
    }

    // Quick scan - only common gateway and server IPs
    suspend fun quickScan(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredServer>()
        val localSubnet = getLocalSubnet() ?: return@withContext discovered

        // Common IPs to check based on subnet
        val commonIPs = mutableListOf<String>()

        // Add gateway IPs
        commonIPs.addAll(listOf(
            "$localSubnet.1",    // Common gateway
            "$localSubnet.254",  // Another common gateway
            "$localSubnet.100",  // Common dynamic allocation start
            "$localSubnet.101",
            "$localSubnet.102",
            "$localSubnet.200",
            "$localSubnet.201",
            // Common server IPs
            "$localSubnet.50",
            "$localSubnet.51",
            "$localSubnet.52",
            // PC common IPs
            "$localSubnet.78",
            "$localSubnet.79",
            "$localSubnet.80",
            "$localSubnet.88",
            // Additional PC IPs
            "$localSubnet.10",
            "$localSubnet.11",
            "$localSubnet.12",
            "$localSubnet.20",
            "$localSubnet.30"
        ))

        // Add subnet extremes
        for (i in 1..20) {
            commonIPs.add("$localSubnet.$i")
        }
        for (i in 200..254) {
            commonIPs.add("$localSubnet.$i")
        }

        // Also scan Android hotspot subnets
        val hotspotSubnets = listOf("192.168.43", "192.168.44", "10.89.247")
        for (subnet in hotspotSubnets) {
            if (subnet != localSubnet) {
                for (i in listOf(1, 2, 10, 50, 100, 254)) {
                    commonIPs.add("$subnet.$i")
                }
                // Full scan of hotspot subnets for thoroughness
                for (i in 1..254) {
                    val ip = "$subnet.$i"
                    if (isServerReachable(ip)) {
                        discovered.add(DiscoveredServer(ip, "PC服务器"))
                    }
                }
            }
        }

        // Remove duplicates and local IP
        val localIP = getLocalIP()
        val targets = commonIPs.distinct().filter { it != localIP }

        for (ip in targets) {
            if (isServerReachable(ip)) {
                discovered.add(DiscoveredServer(ip, "PC服务器"))
            }
        }

        discovered
    }

    // Check if TCP port is open on target IP
    private fun isServerReachable(ip: String): Boolean {
        return try {
            val socket = Socket()
            socket.soTimeout = TIMEOUT_MS
            socket.connect(java.net.InetSocketAddress(ip, TCP_PORT))
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
