package com.bluelink.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 获取连接到手机热点的设备列表
 */
object HotspotDevices {

    data class Device(
        val ip: String,
        val mac: String = "Unknown",
        val hostname: String = "",
        val isReachable: Boolean = false
    )

    // 常见的热点网段
    private val hotspotSubnets = listOf(
        "192.168.43", "192.168.44", "192.168.48", "192.168.49",
        "192.168.1", "192.168.0", "192.168.2", "192.168.5", "192.168.3", "192.168.4", "192.168.6", "192.168.7", "192.168.8", "192.168.9", "192.168.10",
        "10.0.0", "10.0.1", "10.0.2", "10.1.0", "10.2.0", "10.42.0", "10.89.247", "172.20.10", "172.16.0", "172.17.0", "172.18.0", "172.19.0", "172.20.0",
        "192.168.100", "192.168.137", "192.168.137"
    )

    // 调试日志回调
    var debugLog: ((String) -> Unit)? = null

    // Android Context for system services
    private var androidContext: Context? = null

    fun setContext(context: Context) {
        androidContext = context
    }

    /**
     * 获取当前设备的IP地址
     */
    fun getLocalIP(): String? {
        debugLog?.invoke(">>> getLocalIP() 被调用")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var count = 0
            while (interfaces.hasMoreElements()) {
                count++
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        debugLog?.invoke("getLocalIP: 找到 IPv4 $ip (接口: ${iface.name})")
                        return ip
                    }
                }
            }
            debugLog?.invoke("getLocalIP: 遍历了 $count 个接口，未找到 IPv4 地址")
        } catch (e: Exception) {
            debugLog?.invoke("获取本地IP失败: ${e.message}")
        }
        return null
    }

    /**
     * 获取热点网关IP
     */
    fun getGatewayIP(): String? {
        val hotspotIP = getHotspotIP() ?: return null
        val parts = hotspotIP.split(".")
        if (parts.size == 4) {
            return "${parts[0]}.${parts[1]}.${parts[2]}.1"
        }
        return null
    }

    /**
     * 获取当前IP所在的子网（从任何活动接口获取）
     */
    private fun getSubnet(): String? {
        debugLog?.invoke("=== getSubnet() 开始 ===")

        // 首先尝试从热点IP获取
        val hotspotIP = getHotspotIP()
        debugLog?.invoke("getSubnet: hotspotIP = $hotspotIP")

        if (hotspotIP != null) {
            val parts = hotspotIP.split(".")
            if (parts.size == 4) {
                val subnet = "${parts[0]}.${parts[1]}.${parts[2]}"
                debugLog?.invoke("getSubnet: 从热点IP返回子网 $subnet")
                return subnet
            }
        }

        // 如果不在热点网段，从任何活动的IPv4接口获取子网
        debugLog?.invoke("getSubnet: 尝试 getLocalIP() 作为备选")
        val localIP = getLocalIP()
        debugLog?.invoke("getSubnet: getLocalIP() = $localIP")

        return localIP?.let { ip ->
            val parts = ip.split(".")
            if (parts.size == 4) {
                val subnet = "${parts[0]}.${parts[1]}.${parts[2]}"
                debugLog?.invoke("getSubnet: 返回子网 $subnet (从本地IP)")
                subnet
            } else {
                debugLog?.invoke("getSubnet: IP格式无效")
                null
            }
        } ?: run {
            debugLog?.invoke("getSubnet: getLocalIP() 返回 null")
            null
        }
    }

    /**
     * 尝试通过扫描已知网关IP来发现热点网络
     */
    suspend fun discoverHotspotSubnet(): String? = withContext(Dispatchers.IO) {
        debugLog?.invoke("=== 开始扫描发现热点子网 ===")

        // 首先获取当前IP所在的子网（如果不在已知热点网段中）
        val currentSubnet = getSubnet()
        debugLog?.invoke("当前子网: $currentSubnet")

        // 合并扫描列表：热点网段 + 当前子网（避免重复）
        val subnetsToScan = if (currentSubnet != null && !hotspotSubnets.contains(currentSubnet)) {
            debugLog?.invoke("当前子网不在热点列表中，将一起扫描")
            hotspotSubnets + currentSubnet
        } else {
            hotspotSubnets
        }

        for (subnet in subnetsToScan) {
            val gatewayIP = "$subnet.1"
            debugLog?.invoke("检查网关: $gatewayIP")

            if (isPortOpen(gatewayIP, 9000, 100)) {
                debugLog?.invoke(">>> 发现热点! 网关 $gatewayIP 端口9000开放")
                return@withContext subnet
            }
        }

        // 如果标准网关没响应，扫描整个子网找活跃主机
        for (subnet in subnetsToScan) {
            debugLog?.invoke("扫描子网: $subnet.x 寻找活跃主机...")
            for (i in 2..20) {
                val ip = "$subnet.$i"
                if (isPortOpen(ip, 9000, 50)) {
                    debugLog?.invoke(">>> 发现活跃主机: $ip")
                    return@withContext subnet
                }
            }
        }

        debugLog?.invoke("未发现热点子网")
        return@withContext null
    }

    /**
     * 使用Android ConnectivityManager获取当前活动网络的热点IP
     * 这是Android系统获取网络信息最准确的方式
     */
    fun getHotspotIP(): String? {
        val context = androidContext ?: return getLocalIPFallback()
        debugLog?.invoke("=== 使用Android API检测热点IP ===")

        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 获取当前活动的网络
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                debugLog?.invoke("活动网络: $activeNetwork")

                if (linkProperties != null) {
                    val addresses = linkProperties.linkAddresses
                    debugLog?.invoke("地址数量: ${addresses.size}")

                    for (addr in addresses) {
                        val inetAddress = addr.address
                        if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                            val ip = inetAddress.hostAddress
                            debugLog?.invoke("活动网络IP: $ip")

                            // 检查是否是热点网段
                            for (subnet in hotspotSubnets) {
                                if (ip?.startsWith("$subnet.") == true) {
                                    debugLog?.invoke(">>> 热点IP匹配: $ip")
                                    return ip
                                }
                            }
                        }
                    }
                }
            }

            // 遍历所有网络
            val networks = connectivityManager.allNetworks
            debugLog?.invoke("所有网络数量: ${networks.size}")

            for (network in networks) {
                val props = connectivityManager.getLinkProperties(network)
                if (props != null) {
                    for (addr in props.linkAddresses) {
                        val inetAddress = addr.address
                        if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                            val ip = inetAddress.hostAddress
                            debugLog?.invoke("  检查IP: $ip")
                            debugLog?.invoke("  热点网段数: ${hotspotSubnets.size}")

                            for (subnet in hotspotSubnets) {
                                val prefix = "$subnet."
                                debugLog?.invoke("    比对: $ip 是否以 $prefix 开头: ${ip?.startsWith(prefix)}")
                                if (ip != null && ip.startsWith(prefix)) {
                                    debugLog?.invoke(">>> 找到热点IP: $ip (匹配网段: $subnet)")
                                    return ip
                                }
                            }
                        }
                    }
                }
            }

            // 读取系统属性
            val hotspotIP = getHotspotIPFromSystem()
            if (hotspotIP != null) {
                debugLog?.invoke("从系统属性获取IP: $hotspotIP")
                // 验证是否在热点网段
                for (subnet in hotspotSubnets) {
                    if (hotspotIP.startsWith("$subnet.")) {
                        debugLog?.invoke(">>> 系统属性IP匹配热点网段: $hotspotIP")
                        return hotspotIP
                    }
                }
                debugLog?.invoke("系统属性IP不在热点网段: $hotspotIP")
            }

        } catch (e: Exception) {
            debugLog?.invoke("Android API获取热点IP失败: ${e.message}")
        }

        return getLocalIPFallback()
    }

    private fun getLocalIPFallback(): String? {
        debugLog?.invoke("=== 使用备用方法获取IP ===")
        debugLog?.invoke("热点网段列表: $hotspotSubnets")
        val allFoundIPs = mutableListOf<String>()
        var firstIPv4: String? = null
        var interfaceCount = 0

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            debugLog?.invoke("开始枚举网络接口")
            while (interfaces.hasMoreElements()) {
                interfaceCount++
                val iface = interfaces.nextElement()
                val ifaceName = iface.name
                debugLog?.invoke("找到接口#$interfaceCount: $ifaceName isUp=${iface.isUp} isLoopback=${iface.isLoopback}")

                if (iface.isLoopback || !iface.isUp) {
                    debugLog?.invoke("  跳过接口 (loopback=${iface.isLoopback} up=${iface.isUp})")
                    continue
                }

                val addrs = iface.inetAddresses
                var addrCount = 0
                while (addrs.hasMoreElements()) {
                    addrCount++
                    val addr = addrs.nextElement()
                    debugLog?.invoke("  地址#$addrCount: $addr (type=${addr.javaClass.simpleName})")

                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (firstIPv4 == null) firstIPv4 = ip
                        debugLog?.invoke("    检查IPv4: $ip")
                        allFoundIPs.add("$ip ($ifaceName)")

                        for (subnet in hotspotSubnets) {
                            val prefix = "$subnet."
                            val match = ip.startsWith(prefix)
                            debugLog?.invoke("    比对: '$ip' startsWith '$prefix' = $match")
                            if (match) {
                                debugLog?.invoke(">>> 匹配热点网段成功: $ip")
                                return ip
                            }
                        }
                    }
                }
                if (addrCount == 0) {
                    debugLog?.invoke("  接口 $ifaceName 没有地址")
                }
            }
            debugLog?.invoke("枚举完成，共找到 $interfaceCount 个接口")
            debugLog?.invoke("所有发现IP: $allFoundIPs")
            debugLog?.invoke("未匹配到任何热点网段")

            // 如果找不到热点网段，返回第一个找到的 IPv4 地址
            if (firstIPv4 != null) {
                debugLog?.invoke(">>> 返回第一个IPv4地址(可能是热点): $firstIPv4")
                return firstIPv4
            }
        } catch (e: Exception) {
            debugLog?.invoke("备用方法失败: ${e.message}")
            e.printStackTrace()
        }
        return null
    }

    /**
     * 从系统属性获取热点IP（通过读取系统配置）
     */
    private fun getHotspotIPFromSystem(): String? {
        // 尝试多种系统属性读取方式
        val props = listOf(
            "dhcp.wlan0.ipaddr",        // Android热点IP
            "net.wlan0.ip",             // WLAN IP
            "dhcp.eth0.ipaddr",          // Ethernet IP
            "net.gprs.local-ip",        // GPRS IP
            "ril.ppp0.local-ip"         // PPP IP
        )

        for (prop in props) {
            try {
                val process = Runtime.getRuntime().exec("getprop $prop")
                val reader = BufferedReader(java.io.InputStreamReader(process.inputStream))
                val ip = reader.readLine()?.trim()
                reader.close()

                if (!ip.isNullOrEmpty() && ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                    debugLog?.invoke("系统属性 $prop = $ip")
                    // 验证是否是热点相关的IP
                    for (subnet in hotspotSubnets) {
                        if (ip!!.startsWith("$subnet.")) {
                            return ip
                        }
                    }
                }
            } catch (e: Exception) {
                debugLog?.invoke("读取系统属性 $prop 失败: ${e.message}")
            }
        }

        // 尝试读取AP配置文件
        try {
            val process = Runtime.getRuntime().exec("cat /data/misc/wifi/softap/softap.conf")
            val reader = BufferedReader(java.io.InputStreamReader(process.inputStream))
            val content = reader.readText()
            reader.close()
            debugLog?.invoke("softap.conf: $content")
        } catch (e: Exception) {
            debugLog?.invoke("读取softap.conf失败: ${e.message}")
        }

        return null
    }

    /**
     * 检查主机端口是否开放
     */
    private fun isPortOpen(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.soTimeout = timeout
            socket.connect(java.net.InetSocketAddress(ip, port), timeout)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 读取 /proc/net/arp 获取ARP缓存表
     */
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
     * 扫描子网内的IP - 并行快速扫描
     */
    private suspend fun scanSubnetFast(subnet: String): List<Device> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<Device>()
        val port = 9000

        debugLog?.invoke("开始扫描子网: $subnet.x")

        val jobs = mutableListOf<Deferred<Device?>>()

        for (i in 1..254) {
            val ip = "$subnet.$i"
            jobs.add(async(Dispatchers.IO) {
                try {
                    val socket = java.net.Socket()
                    socket.soTimeout = 150
                    socket.connect(java.net.InetSocketAddress(ip, port), 150)
                    socket.close()
                    debugLog?.invoke("扫描发现设备: $ip (端口开放)")
                    Device(ip = ip, mac = "Unknown", isReachable = true)
                } catch (e: Exception) {
                    null
                }
            })
        }

        jobs.forEach { job ->
            try {
                val device = job.await()
                if (device != null) {
                    devices.add(device)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        debugLog?.invoke("扫描完成，发现 ${devices.size} 个设备")
        devices
    }

    /**
     * 扫描指定子网内的IP - 并行快速扫描
     */
    suspend fun scanSubnet(subnet: String): List<Device> = withContext(Dispatchers.IO) {
        debugLog?.invoke("=== 开始扫描子网: $subnet.x ===")
        val devices = mutableListOf<Device>()
        val port = 9000

        val jobs = mutableListOf<Deferred<Device?>>()

        for (i in 1..254) {
            val ip = "$subnet.$i"
            jobs.add(async(Dispatchers.IO) {
                try {
                    val socket = java.net.Socket()
                    socket.soTimeout = 150
                    socket.connect(java.net.InetSocketAddress(ip, port), 150)
                    socket.close()
                    debugLog?.invoke("扫描发现设备: $ip (端口开放)")
                    Device(ip = ip, mac = "Unknown", isReachable = true)
                } catch (e: Exception) {
                    null
                }
            })
        }

        jobs.forEach { job ->
            try {
                val device = job.await()
                if (device != null) {
                    devices.add(device)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        debugLog?.invoke("扫描完成，发现 ${devices.size} 个设备")
        devices
    }

    /**
     * 获取所有连接到热点的设备IP
     */
    suspend fun getConnectedDevices(): List<Device> = withContext(Dispatchers.IO) {
        val allDevices = mutableListOf<Device>()

        // 确保Context已设置
        if (androidContext == null) {
            debugLog?.invoke("警告: androidContext 未设置，某些扫描功能可能受限")
        }

        val localIP = getLocalIP()
        val currentSubnet = getSubnet()

        debugLog?.invoke("=== 开始获取连接设备 ===")
        debugLog?.invoke("本地IP: $localIP")
        debugLog?.invoke("当前子网: $currentSubnet")
        debugLog?.invoke("热点网段列表: $hotspotSubnets")

        // 1. 先从ARP表获取
        val arpDevices = getArpTable()
        allDevices.addAll(arpDevices)

        // 2. 扫描子网（当前子网 + 热点子网）
        val subnetsToScan = if (currentSubnet != null && !hotspotSubnets.contains(currentSubnet)) {
            debugLog?.invoke("当前子网不在热点列表中，将一起扫描")
            hotspotSubnets + currentSubnet
        } else {
            hotspotSubnets
        }

        for (subnet in subnetsToScan) {
            val scannedDevices = scanSubnetFast(subnet)
            for (device in scannedDevices) {
                if (!allDevices.any { it.ip == device.ip }) {
                    allDevices.add(device)
                }
            }
        }

        // 3. 检查网关（所有子网的网关）
        for (subnet in subnetsToScan) {
            val gatewayIP = "$subnet.1"
            debugLog?.invoke("检查网关IP: $gatewayIP")

            val commonPCIPs = listOf(
                "$subnet.2", "$subnet.10", "$subnet.50",
                "$subnet.100", "$subnet.101", "$subnet.102",
                "$subnet.200", "$subnet.201"
            )

            commonPCIPs.forEach { ip ->
                if (ip != localIP && isPortOpen(ip, 9000, 100)) {
                    if (!allDevices.any { it.ip == ip }) {
                        debugLog?.invoke("常见IP发现设备: $ip")
                        allDevices.add(Device(ip = ip, isReachable = true))
                    }
                }
            }
        }

        val result = allDevices.filter { it.ip != localIP }.distinctBy { it.ip }
        debugLog?.invoke("=== 最终结果: ${result.size} 个设备 ===")

        result
    }
}
