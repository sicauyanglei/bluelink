package com.bluelink.transfer

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * P2-5/P2-6: 合并 NetworkScanner + HotspotDevices
 *
 * 统一网络发现与扫描入口，使用 Semaphore 限制并发连接数（默认 20），
 * 避免 254 个 Socket 同时连接导致 fd 耗尽 / 设备过载。
 */
object NetworkScanner {
    private const val TCP_PORT = 9000
    private const val TIMEOUT_MS = 500

    /** P2-5: 最大并发 Socket 连接数 */
    private val scanSemaphore = Semaphore(20)

    data class DiscoveredServer(
        val ip: String,
        val name: String = "PC服务器"
    )

    data class Device(
        val ip: String,
        val mac: String = "Unknown",
        val hostname: String = "",
        val isReachable: Boolean = false
    )

    // 常见的热点网段
    private val hotspotSubnets = listOf(
        "192.168.43", "192.168.44", "192.168.48", "192.168.49",
        "192.168.1", "192.168.0", "192.168.2", "192.168.5",
        "192.168.3", "192.168.4", "192.168.6", "192.168.7",
        "192.168.8", "192.168.9", "192.168.10",
        "10.0.0", "10.0.1", "10.0.2", "10.1.0", "10.2.0",
        "10.42.0", "10.89.247", "172.20.10", "172.16.0",
        "172.17.0", "172.18.0", "172.19.0", "172.20.0",
        "192.168.100", "192.168.137"
    )

    // 调试日志回调
    var debugLog: ((String) -> Unit)? = null

    private var androidContext: Context? = null

    fun setContext(context: Context) {
        androidContext = context
    }

    // ==================== IP 获取 ====================

    /** 获取当前设备的 IPv4 地址 */
    fun getLocalIP(): String? {
        debugLog?.invoke(">>> getLocalIP() 被调用")
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
            debugLog?.invoke("获取本地IP失败: ${e.message}")
        }
        return null
    }

    /** 获取所有本地 IPv4 地址 */
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
            debugLog?.invoke("getAllLocalIPs 失败: ${e.message}")
        }
        return ips
    }

    /** 获取本地子网（前三段） */
    fun getLocalSubnet(): String? {
        val localIP = getLocalIP() ?: return null
        val parts = localIP.split(".")
        return if (parts.size == 4) parts.take(3).joinToString(".") else null
    }

    /**
     * 使用 Android ConnectivityManager 获取热点 IP
     */
    fun getHotspotIP(): String? {
        val context = androidContext ?: return getLocalIPFallback()
        debugLog?.invoke("=== 使用Android API检测热点IP ===")

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 活动网络
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val lp = cm.getLinkProperties(activeNetwork)
                if (lp != null) {
                    for (addr in lp.linkAddresses) {
                        val inet = addr.address
                        if (inet is Inet4Address && !inet.isLoopbackAddress) {
                            val ip = inet.hostAddress
                            if (ip != null && hotspotSubnets.any { ip.startsWith("$it.") }) {
                                debugLog?.invoke(">>> 热点IP匹配: $ip")
                                return ip
                            }
                        }
                    }
                }
            }

            // 所有网络
            for (network in cm.allNetworks) {
                val props = cm.getLinkProperties(network) ?: continue
                for (addr in props.linkAddresses) {
                    val inet = addr.address
                    if (inet is Inet4Address && !inet.isLoopbackAddress) {
                        val ip = inet.hostAddress ?: continue
                        if (hotspotSubnets.any { ip.startsWith("$it.") }) {
                            debugLog?.invoke(">>> 找到热点IP: $ip")
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            debugLog?.invoke("Android API获取热点IP失败: ${e.message}")
        }

        return getLocalIPFallback()
    }

    /** 获取热点网关 IP */
    fun getGatewayIP(): String? {
        val hotspotIP = getHotspotIP() ?: return null
        val parts = hotspotIP.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else null
    }

    private fun getLocalIPFallback(): String? {
        debugLog?.invoke("=== 使用备用方法获取IP ===")
        var firstIPv4: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (firstIPv4 == null) firstIPv4 = ip
                        if (hotspotSubnets.any { ip.startsWith("$it.") }) {
                            debugLog?.invoke(">>> 匹配热点网段: $ip")
                            return ip
                        }
                    }
                }
            }
            debugLog?.invoke("未匹配热点网段，返回首个IPv4: $firstIPv4")
        } catch (e: Exception) {
            debugLog?.invoke("备用方法失败: ${e.message}")
        }
        return firstIPv4
    }

    // ==================== 子网发现 ====================

    /**
     * 尝试通过扫描已知网关 IP 来发现热点网络
     */
    suspend fun discoverHotspotSubnet(): String? = withContext(Dispatchers.IO) {
        debugLog?.invoke("=== 开始扫描发现热点子网 ===")
        val currentSubnet = getLocalSubnet()
        val subnetsToScan = if (currentSubnet != null && currentSubnet !in hotspotSubnets) {
            hotspotSubnets + currentSubnet
        } else {
            hotspotSubnets
        }

        // 先检查各子网网关
        for (subnet in subnetsToScan) {
            if (isPortOpen("$subnet.1", TCP_PORT, 100)) {
                debugLog?.invoke(">>> 发现热点! 网关 $subnet.1 端口开放")
                return@withContext subnet
            }
        }

        // 再扫描各子网前 20 个 IP
        for (subnet in subnetsToScan) {
            for (i in 2..20) {
                if (isPortOpen("$subnet.$i", TCP_PORT, 50)) {
                    debugLog?.invoke(">>> 发现活跃主机: $subnet.$i")
                    return@withContext subnet
                }
            }
        }

        debugLog?.invoke("未发现热点子网")
        null
    }

    // ==================== 扫描 ====================

    /**
     * 全子网扫描（.1 ~ .254），P2-5 限制并发到 20
     */
    suspend fun scanNetwork(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredServer>()
        val localSubnet = getLocalSubnet() ?: return@withContext discovered

        val subnets = mutableListOf(localSubnet)
        val localIP = getLocalIP() ?: ""
        when {
            localIP.startsWith("192.168.43.") || localIP.startsWith("192.168.44.") -> {
                subnets.add("192.168.43"); subnets.add("192.168.44")
            }
            localIP.startsWith("10.89.247.") -> subnets.add("10.89.247")
            localIP.startsWith("192.168.1.") -> subnets.add("192.168.1")
            localIP.startsWith("192.168.0.") -> subnets.add("192.168.0")
        }

        for (subnet in subnets.distinct()) {
            discovered.addAll(scanSubnetConcurrent(subnet) { ip ->
                DiscoveredServer(ip, "PC服务器")
            })
        }
        discovered
    }

    /**
     * 快速扫描 - 仅检查常见 IP，P2-5 限制并发
     */
    suspend fun quickScan(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredServer>()
        val localSubnet = getLocalSubnet() ?: return@withContext discovered

        val targets = mutableListOf<String>()
        // 常见网关 / 服务器 IP
        listOf(1, 254, 100, 101, 102, 200, 201, 50, 51, 52, 78, 79, 80, 88, 10, 11, 12, 20, 30)
            .forEach { targets.add("$localSubnet.$it") }
        // 子网两端
        (1..20).forEach { targets.add("$localSubnet.$it") }
        (200..254).forEach { targets.add("$localSubnet.$it") }

        // 热点子网
        val hotspotSubnetsQuick = listOf("192.168.43", "192.168.44", "10.89.247")
        for (subnet in hotspotSubnetsQuick) {
            if (subnet != localSubnet) {
                listOf(1, 2, 10, 50, 100, 254).forEach { targets.add("$subnet.$it") }
            }
        }

        val localIP = getLocalIP()
        val uniqueTargets = targets.distinct().filter { it != localIP }

        discovered.addAll(scanSubnetConcurrent(uniqueTargets) { ip ->
            DiscoveredServer(ip, "PC服务器")
        })
        discovered
    }

    /**
     * 扫描指定子网内的 IP（对外接口），P2-5 限制并发
     */
    suspend fun scanSubnet(subnet: String): List<Device> = withContext(Dispatchers.IO) {
        debugLog?.invoke("=== 开始扫描子网: $subnet.x ===")
        val ips = (1..254).map { "$subnet.$it" }
        val devices = scanSubnetConcurrent(ips) { ip ->
            debugLog?.invoke("扫描发现设备: $ip (端口开放)")
            Device(ip = ip, mac = "Unknown", isReachable = true)
        }
        debugLog?.invoke("扫描完成，发现 ${devices.size} 个设备")
        devices
    }

    /**
     * P2-5: 通用并发扫描，使用 Semaphore 限制并发连接数
     * @param ips 要扫描的 IP 列表
     * @param mapper 命中后转换为结果类型的函数
     */
    private suspend fun <T> scanSubnetConcurrent(
        ips: List<String>,
        mapper: (String) -> T
    ): List<T> = coroutineScope {
        val results = mutableListOf<T>()
        val deferreds = ips.map { ip ->
            async(Dispatchers.IO) {
                scanSemaphore.withPermit {
                    if (isPortOpen(ip, TCP_PORT, TIMEOUT_MS)) mapper(ip) else null
                }
            }
        }
        deferreds.forEach { d ->
            try { d.await()?.let { results.add(it) } } catch (_: Exception) {}
        }
        results
    }

    /** 重载：扫描整个 /24 子网 */
    private suspend fun <T> scanSubnetConcurrent(
        subnet: String,
        mapper: (String) -> T
    ): List<T> = scanSubnetConcurrent((1..254).map { "$subnet.$it" }, mapper)

    // ==================== ARP 表 ====================

    /** 读取 /proc/net/arp 获取 ARP 缓存表 */
    suspend fun getArpTable(): List<Device> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<Device>()
        val localIP = getLocalIP()
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine() // 跳过表头
                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val mac = parts[3]
                            if (mac != "00:00:00:00:00:00" && mac != ".." && ip != localIP) {
                                devices.add(Device(ip = ip, mac = mac))
                                debugLog?.invoke("ARP发现设备: $ip ($mac)")
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            debugLog?.invoke("读取ARP失败: ${e.message}")
        }
        devices
    }

    /**
     * 获取所有连接到热点的设备 IP
     */
    suspend fun getConnectedDevices(): List<Device> = withContext(Dispatchers.IO) {
        val allDevices = mutableListOf<Device>()
        val localIP = getLocalIP()
        val currentSubnet = getLocalSubnet()

        debugLog?.invoke("=== 开始获取连接设备 ===")
        debugLog?.invoke("本地IP: $localIP, 子网: $currentSubnet")

        // 1. ARP 表
        allDevices.addAll(getArpTable())

        // 2. 扫描子网
        val subnetsToScan = if (currentSubnet != null && currentSubnet !in hotspotSubnets) {
            hotspotSubnets + currentSubnet
        } else {
            hotspotSubnets
        }

        for (subnet in subnetsToScan) {
            val scanned = scanSubnet(subnet)
            for (device in scanned) {
                if (allDevices.none { it.ip == device.ip }) {
                    allDevices.add(device)
                }
            }
        }

        val result = allDevices.filter { it.ip != localIP }.distinctBy { it.ip }
        debugLog?.invoke("=== 最终结果: ${result.size} 个设备 ===")
        result
    }

    // ==================== 端口检测 ====================

    /** 检查 TCP 端口是否开放 */
    fun isPortOpen(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeout
                socket.connect(InetSocketAddress(ip, port), timeout)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 兼容旧接口：检查服务器是否可达 */
    fun isServerReachable(ip: String): Boolean = isPortOpen(ip, TCP_PORT, TIMEOUT_MS)
}
