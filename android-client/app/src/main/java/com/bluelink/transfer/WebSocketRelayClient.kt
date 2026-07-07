package com.bluelink.transfer

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * WebSocket客户端 - 用于通过中继服务器实现内网穿透
 * Android客户端通过WebSocket连接到中继服务器，与PC服务端配对后进行数据透传
 *
 * 工作模式（模仿花生壳）：
 * 1. 中转模式：通过WebSocket中继服务器透传数据（默认）
 * 2. P2P直连模式：配对后获取PC公网IP，尝试TCP直连，成功则走P2P
 */
class WebSocketRelayClient : TransferClient {
    companion object {
        private const val TAG = "WebSocketRelayClient"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2000L
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 3000L  // P2P直连尝试超时
    }

    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private val receiveChannel = Channel<ByteArray>(Channel.BUFFERED)
    private val coroutineScope = MainScope()

    // P2P直连相关
    private var directTcpClient: TcpClient? = null
    private var pcPublicIP: String? = null
    private var pcTcpPort: Int = 9000
    var isDirectConnection: Boolean = false
        private set

    private var relayUrl: String? = null
    private var deviceId: String? = null
    private var isManuallyDisconnected = false
    private var _isReconnecting = false
    private var heartBeatJob: kotlinx.coroutines.Job? = null
    private var backgroundReconnectJob: kotlinx.coroutines.Job? = null
    private val reconnectMutex = Mutex()

    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReconnected: (() -> Unit)? = null
    var onPaired: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** 连接方式变化通知（"P2P直连" / "中转"） */
    var onConnectionModeChanged: ((String) -> Unit)? = null

    override val isReconnecting: Boolean get() = _isReconnecting
    override val isConnected: Boolean get() = if (isDirectConnection) {
        directTcpClient?.isConnected == true
    } else {
        webSocket != null && !_isReconnecting
    }
    override val hasConnectionInfo: Boolean get() = relayUrl != null && deviceId != null

    /** 当前连接方式描述 */
    val connectionMode: String get() = if (isDirectConnection) "P2P直连" else "中转"

    /**
     * 连接到中继服务器并配对
     */
    suspend fun connect(url: String, deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        this@WebSocketRelayClient.relayUrl = url
        this@WebSocketRelayClient.deviceId = deviceId
        isManuallyDisconnected = false

        try {
            okHttpClient = OkHttpClient.Builder()
                .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(url).build()
            webSocket = okHttpClient!!.newWebSocket(request, RelayWebSocketListener())

            // 等待连接建立
            delay(1000)

            // 发送注册消息
            val registerMsg = """{"type":"register_android","deviceId":"$deviceId"}"""
            webSocket?.send(registerMsg)

            Log.d(TAG, "WebSocket连接成功，发送注册消息...")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "连接中继服务器失败: ${e.message}")
            Result.failure(e)
        }
    }

    inner class RelayWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket已连接")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "收到控制消息: $text")

            try {
                val json = org.json.JSONObject(text)
                when (json.optString("type")) {
                    "welcome" -> Log.d(TAG, "收到欢迎消息")
                    "registered" -> Log.d(TAG, "注册成功")
                    "paired" -> {
                        // 解析PC公网IP和端口，用于P2P直连尝试
                        pcPublicIP = if (json.has("pcPublicIP") && !json.isNull("pcPublicIP"))
                            json.getString("pcPublicIP") else null
                        pcTcpPort = json.optInt("pcTcpPort", 9000)
                        val deviceName = json.optString("deviceName", "PC")
                        Log.d(TAG, "配对成功: PC=$deviceName, publicIP=$pcPublicIP:$pcTcpPort")

                        // 先尝试P2P直连，失败则用中转
                        tryDirectConnectThenNotify()
                    }
                    "pc_disconnected" -> {
                        Log.d(TAG, "PC服务端已断开")
                        _isReconnecting = false
                        cleanupDirectConnection()
                        onDisconnected?.invoke()
                        startBackgroundReconnect()
                    }
                    "error" -> {
                        val msg = json.optString("message", "未知错误")
                        Log.e(TAG, "服务器错误: $msg")
                        onError?.invoke(msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析控制消息失败: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // 二进制数据，放入接收通道
            val data = bytes.toByteArray()
            try {
                runBlocking { receiveChannel.send(data) }
            } catch (e: Exception) {
                Log.e(TAG, "接收数据放入通道失败: ${e.message}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket关闭: $code $reason")
            if (!isManuallyDisconnected && !isDirectConnection) {
                onDisconnected?.invoke()
                startBackgroundReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket错误: ${t.message}")
            if (!isManuallyDisconnected && !isDirectConnection) {
                onError?.invoke(t.message ?: "连接错误")
                onDisconnected?.invoke()
                startBackgroundReconnect()
            }
        }
    }

    /**
     * 尝试P2P直连PC公网IP，成功则切换到直连模式，失败则保持中转模式
     */
    private fun tryDirectConnectThenNotify() {
        val ip = pcPublicIP
        if (ip.isNullOrBlank() || ip == "127.0.0.1" || ip.startsWith("192.168.") ||
            ip.startsWith("10.") || ip.startsWith("172.16.")) {
            // PC无公网IP或也是内网IP，跳过直连尝试
            Log.d(TAG, "PC无公网IP（$ip），使用中转模式")
            isDirectConnection = false
            onConnectionModeChanged?.invoke(connectionMode)
            onPaired?.invoke()
            onReconnected?.invoke()
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            val success = tryTcpDirectConnect(ip, pcTcpPort)
            if (success) {
                Log.d(TAG, "P2P直连成功: $ip:$pcTcpPort")
                isDirectConnection = true
                onConnectionModeChanged?.invoke(connectionMode)
                // 直连成功后可关闭WebSocket（保留备用，不主动关）
                onPaired?.invoke()
                onReconnected?.invoke()
            } else {
                Log.d(TAG, "P2P直连失败，使用中转模式")
                isDirectConnection = false
                onConnectionModeChanged?.invoke(connectionMode)
                startHeartBeat()  // 中转模式需要心跳
                onPaired?.invoke()
                onReconnected?.invoke()
            }
        }
    }

    /**
     * 尝试TCP直连PC公网IP
     */
    private suspend fun tryTcpDirectConnect(ip: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            // 先用原生Socket探测连通性（短超时）
            val socketReachable = withTimeoutOrNull(DIRECT_CONNECT_TIMEOUT_MS) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), DIRECT_CONNECT_TIMEOUT_MS.toInt())
                    socket.close()
                    true
                } catch (e: Exception) {
                    Log.d(TAG, "TCP探测失败: ${e.message}")
                    false
                }
            } ?: false

            if (!socketReachable) return@withContext false

            // 用TcpClient建立正式连接
            try {
                val tcpClient = TcpClient()
                val result = tcpClient.connect(ip, port)
                if (result.isSuccess) {
                    directTcpClient = tcpClient
                    true
                } else {
                    Log.d(TAG, "TcpClient连接失败: ${result.exceptionOrNull()?.message}")
                    false
                }
            } catch (e: Exception) {
                Log.d(TAG, "TcpClient异常: ${e.message}")
                false
            }
        }
    }

    private fun cleanupDirectConnection() {
        try {
            directTcpClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "清理直连失败: ${e.message}")
        }
        directTcpClient = null
        isDirectConnection = false
    }

    override suspend fun readPacket(): ByteArray? = withContext(Dispatchers.IO) {
        // P2P直连模式：委托给TcpClient
        if (isDirectConnection) {
            return@withContext directTcpClient?.readPacket()
        }
        // 中转模式：从WebSocket通道读取
        try {
            val data = receiveChannel.receive()
            if (data.size >= 5) {
                val length = ((data[1].toInt() and 0xFF) shl 24) or
                             ((data[2].toInt() and 0xFF) shl 16) or
                             ((data[3].toInt() and 0xFF) shl 8) or
                             (data[4].toInt() and 0xFF)

                if (data.size >= 5 + length) {
                    return@withContext data.copyOfRange(0, 5 + length)
                }
            }
            data
        } catch (e: Exception) {
            Log.e(TAG, "readPacket error: ${e.message}")
            null
        }
    }

    override suspend fun writePacket(command: Byte, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (isDirectConnection) {
            return@withContext directTcpClient?.writePacket(command, data) ?: false
        }
        val packet = ByteArray(5 + data.size)
        packet[0] = command
        val length = data.size
        packet[1] = ((length shr 24) and 0xFF).toByte()
        packet[2] = ((length shr 16) and 0xFF).toByte()
        packet[3] = ((length shr 8) and 0xFF).toByte()
        packet[4] = (length and 0xFF).toByte()
        System.arraycopy(data, 0, packet, 5, data.size)
        writeRaw(packet)
    }

    override suspend fun writeRaw(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (isDirectConnection) {
            return@withContext directTcpClient?.writeRaw(data) ?: false
        }
        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "writeRaw: WebSocket is null")
            return@withContext false
        }
        try {
            ws.send(ByteString.of(*data))
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeRaw error: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        isManuallyDisconnected = true
        stopHeartBeat()
        stopBackgroundReconnect()
        cleanupDirectConnection()
        try {
            webSocket?.close(1000, "disconnect")
        } catch (e: Exception) {
            Log.e(TAG, "disconnect error: ${e.message}")
        }
        _isReconnecting = false
        coroutineScope.cancel()
    }

    private fun startHeartBeat() {
        stopHeartBeat()
        heartBeatJob = coroutineScope.launch {
            while (isConnected && !isManuallyDisconnected) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)
                    writeRaw(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00))
                } catch (e: Exception) {
                    Log.e(TAG, "heartbeat error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun stopHeartBeat() {
        heartBeatJob?.cancel()
        heartBeatJob = null
    }

    override suspend fun autoReconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!reconnectMutex.tryLock()) {
            return@withContext Result.failure(Exception("已有重连进行中"))
        }

        try {
            val url = relayUrl ?: return@withContext Result.failure(Exception("无连接信息"))
            val id = deviceId ?: return@withContext Result.failure(Exception("无设备ID"))

            if (isManuallyDisconnected) {
                return@withContext Result.failure(Exception("已手动断开"))
            }

            stopBackgroundReconnect()
            _isReconnecting = true

            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                Log.d(TAG, "autoReconnect: attempt $attempt")
                onReconnecting?.invoke(attempt)

                try {
                    okHttpClient = OkHttpClient.Builder()
                        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder().url(url).build()
                    webSocket = okHttpClient!!.newWebSocket(request, RelayWebSocketListener())

                    delay(1000)

                    val registerMsg = """{"type":"register_android","deviceId":"$id"}"""
                    webSocket?.send(registerMsg)

                    Log.d(TAG, "autoReconnect: success")
                    _isReconnecting = false
                    // 注意：心跳和onReconnected由 paired 消息回调统一触发
                    return@withContext Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "autoReconnect: attempt $attempt failed: ${e.message}")
                    if (attempt < MAX_RECONNECT_ATTEMPTS) {
                        delay(RECONNECT_DELAY_MS)
                    }
                }
            }

            _isReconnecting = false
            onDisconnected?.invoke()
            startBackgroundReconnect()
            Result.failure(Exception("重连失败"))
        } finally {
            reconnectMutex.unlock()
        }
    }

    private fun startBackgroundReconnect() {
        stopBackgroundReconnect()
        if (isManuallyDisconnected || relayUrl == null) return

        backgroundReconnectJob = coroutineScope.launch {
            while (!isManuallyDisconnected && !isConnected) {
                delay(5000)
                if (isManuallyDisconnected || isConnected) break

                _isReconnecting = true
                onReconnecting?.invoke(0)

                try {
                    val url = relayUrl ?: break
                    val id = deviceId ?: break

                    okHttpClient = OkHttpClient.Builder()
                        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder().url(url).build()
                    webSocket = okHttpClient!!.newWebSocket(request, RelayWebSocketListener())

                    delay(1000)

                    val registerMsg = """{"type":"register_android","deviceId":"$id"}"""
                    webSocket?.send(registerMsg)

                    _isReconnecting = false
                    // 注意：onReconnected由 paired 消息回调统一触发
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "backgroundReconnect failed: ${e.message}")
                    _isReconnecting = false
                }
            }
        }
    }

    private fun stopBackgroundReconnect() {
        backgroundReconnectJob?.cancel()
        backgroundReconnectJob = null
    }

    fun checkConnection(): Boolean {
        if (isDirectConnection) {
            return directTcpClient?.isConnected == true
        }
        return webSocket != null && !_isReconnecting
    }
}
