package com.bluelink.transfer

import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * P1-1: 客户端公共基类，消除 BluetoothClient/TcpClient ~90% 重复代码
 * 同时修复：
 * - P0-2: readPacket header 循环读取直到读满 5 字节
 * - P0-3: 心跳与业务数据并发写竞争（writeLock 互斥锁）
 * - P1-3: 替换 GlobalScope 为可控的 clientScope
 * - P1-11: 心跳协议化（CMD_HEARTBEAT）
 */
abstract class BaseTransferClient : TransferClient {

    companion object {
        private const val TAG = "BaseTransferClient"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2000L
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val BACKGROUND_RECONNECT_INTERVAL_MS = 5000L
        private const val MAX_BACKGROUND_RECONNECT_ATTEMPTS = 60 // 5 分钟后放弃
    }

    protected var inputStream: InputStream? = null
    protected var outputStream: OutputStream? = null

    // P0-3: 写锁，保护 outputStream 并发写
    private val writeLock = Any()

    // P1-3: 客户端可控的协程作用域，替代 GlobalScope
    protected val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var toast: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onReconnected: (() -> Unit)? = null

    private var heartBeatJob: Job? = null
    private var backgroundReconnectJob: Job? = null
    private var backgroundReconnectAttempts = 0
    protected var isManuallyDisconnected = false

    private var _isReconnecting = false
    override val isReconnecting: Boolean get() = _isReconnecting

    override val isConnected: Boolean
        get() = isSocketConnected() && !_isReconnecting

    protected abstract fun isSocketConnected(): Boolean
    protected abstract val hasLastConnectionInfo: Boolean

    override val hasConnectionInfo: Boolean get() = hasLastConnectionInfo

    /** 子类实现具体的连接逻辑 */
    protected abstract suspend fun doConnect(): Result<Unit>

    /** 子类实现具体的断开 socket 逻辑 */
    protected abstract fun closeSocket()

    /** 子类实现 socket 超时设置（如支持） */
    protected open fun setSocketTimeout(timeoutMs: Int) {}

    /**
     * P0-2: 修复 readPacket header 读取不完整 bug
     * 循环读取直到读满 5 字节或返回 -1
     */
    override suspend fun readPacket(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val header = ByteArray(5)
            val input = inputStream ?: run {
                handleReadError()
                return@withContext null
            }

            // 循环读取 header 直到读满 5 字节
            var headerRead = 0
            while (headerRead < 5) {
                val read = input.read(header, headerRead, 5 - headerRead)
                if (read <= 0) {
                    Log.w(TAG, "readPacket header EOF, read=$read")
                    handleReadError()
                    return@withContext null
                }
                headerRead += read
            }

            val command = header[0]
            val length = ((header[1].toInt() and 0xFF) shl 24) or
                    ((header[2].toInt() and 0xFF) shl 16) or
                    ((header[3].toInt() and 0xFF) shl 8) or
                    (header[4].toInt() and 0xFF)

            if (length <= 0) return@withContext header

            // 防御：长度异常
            if (length > 512 * 1024 * 1024) {
                Log.e(TAG, "readPacket: illegal length $length")
                handleReadError()
                return@withContext null
            }

            // 循环读取 data 直到读满
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(data, offset, length - offset)
                if (read <= 0) {
                    Log.w(TAG, "readPacket data EOF at offset=$offset")
                    handleReadError()
                    return@withContext null
                }
                offset += read
            }

            header + data
        } catch (e: Exception) {
            Log.e(TAG, "readPacket error: ${e.javaClass.simpleName}: ${e.message}")
            toast?.invoke("读取异常: ${e.message}")
            handleReadError()
            null
        }
    }

    /**
     * P0-3: writePacket 加同步锁，避免与心跳并发写
     */
    override suspend fun writePacket(command: Byte, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val out = outputStream ?: run {
            Log.e(TAG, "writePacket: outputStream is null!")
            handleWriteError()
            return@withContext false
        }
        synchronized(writeLock) {
            try {
                val length = data.size
                val header = byteArrayOf(command) + intToBytes(length)
                out.write(header)
                out.write(data)
                out.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "writePacket error: ${e.javaClass.simpleName}: ${e.message}")
                try {
                    delay(100)
                    val length = data.size
                    val header = byteArrayOf(command) + intToBytes(length)
                    out.write(header)
                    out.write(data)
                    out.flush()
                    true
                } catch (e2: Exception) {
                    Log.e(TAG, "writePacket retry failed: ${e2.message}")
                    handleWriteError()
                    false
                }
            }
        }
    }

    /**
     * P0-3: writeRaw 同样加锁
     */
    override suspend fun writeRaw(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val out = outputStream ?: run {
            Log.e(TAG, "writeRaw: outputStream is null!")
            handleWriteError()
            return@withContext false
        }
        synchronized(writeLock) {
            try {
                out.write(data)
                out.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "writeRaw error: ${e.message}")
                try {
                    delay(50)
                    out.write(data)
                    out.flush()
                    true
                } catch (e2: Exception) {
                    Log.e(TAG, "writeRaw retry failed: ${e2.message}")
                    handleWriteError()
                    false
                }
            }
        }
    }

    override suspend fun autoReconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasLastConnectionInfo) {
            return@withContext Result.failure(Exception("无上次连接信息"))
        }

        _isReconnecting = true
        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "autoReconnect: attempt $attempt/$MAX_RECONNECT_ATTEMPTS")
            onReconnecting?.invoke(attempt)

            try {
                closeSocket()
                val result = doConnect()
                if (result.isSuccess) {
                    Log.d(TAG, "autoReconnect: success on attempt $attempt")
                    _isReconnecting = false
                    backgroundReconnectAttempts = 0
                    startHeartBeat()
                    onReconnected?.invoke()
                    return@withContext Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "autoReconnect attempt $attempt failed: ${e.message}")
            }

            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                delay(RECONNECT_DELAY_MS)
            }
        }

        Log.d(TAG, "autoReconnect: exhausted, starting background reconnect")
        _isReconnecting = false
        onDisconnected?.invoke()
        startBackgroundReconnect()
        Result.failure(Exception("重连失败，后台继续尝试"))
    }

    /**
     * P1-3: 后台重连用 clientScope 而非 GlobalScope，且限制最大尝试次数
     */
    private fun startBackgroundReconnect() {
        stopBackgroundReconnect()
        if (isManuallyDisconnected || !hasLastConnectionInfo) return

        Log.d(TAG, "startBackgroundReconnect: starting")
        backgroundReconnectJob = clientScope.launch {
            while (!isManuallyDisconnected && !isConnected) {
                if (backgroundReconnectAttempts >= MAX_BACKGROUND_RECONNECT_ATTEMPTS) {
                    Log.w(TAG, "backgroundReconnect: max attempts reached, giving up")
                    _isReconnecting = false
                    break
                }

                delay(BACKGROUND_RECONNECT_INTERVAL_MS)
                if (isManuallyDisconnected || isConnected) break

                backgroundReconnectAttempts++
                Log.d(TAG, "backgroundReconnect: attempt $backgroundReconnectAttempts")
                _isReconnecting = true
                onReconnecting?.invoke(0)

                try {
                    closeSocket()
                    val result = doConnect()
                    if (result.isSuccess) {
                        Log.d(TAG, "backgroundReconnect: success!")
                        _isReconnecting = false
                        backgroundReconnectAttempts = 0
                        startHeartBeat()
                        onReconnected?.invoke()
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "backgroundReconnect failed: ${e.message}")
                }
                _isReconnecting = false
            }
        }
    }

    private fun stopBackgroundReconnect() {
        backgroundReconnectJob?.cancel()
        backgroundReconnectJob = null
    }

    override fun disconnect() {
        isManuallyDisconnected = true
        stopHeartBeat()
        stopBackgroundReconnect()
        _isReconnecting = false
        try {
            outputStream?.close()
            inputStream?.close()
            closeSocket()
            Log.d(TAG, "disconnected")
        } catch (e: IOException) {
            Log.w(TAG, "disconnect error: ${e.message}")
        }
        outputStream = null
        inputStream = null
    }

    /**
     * P1-11: 心跳协议化，发送 CMD_HEARTBEAT 而非裸 0x00
     * P0-3: 通过 writePacket 走同步锁，避免与业务数据竞争
     * P1-3: 用 clientScope 替代 GlobalScope
     */
    private fun startHeartBeat() {
        stopHeartBeat()
        heartBeatJob = clientScope.launch {
            while (isConnected && !isManuallyDisconnected) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (!isConnected || isManuallyDisconnected) break
                    Log.d(TAG, "sending heartbeat...")
                    // P1-11: 用协议包发送心跳
                    val packet = FileTransferProtocol.createPacket(
                        FileTransferProtocol.CMD_HEARTBEAT,
                        byteArrayOf()
                    )
                    if (!writeRaw(packet)) {
                        Log.e(TAG, "heartbeat failed, triggering reconnection...")
                        if (!isManuallyDisconnected) {
                            clientScope.launch { autoReconnect() }
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "heartbeat error: ${e.message}")
                    if (!isManuallyDisconnected) {
                        clientScope.launch { autoReconnect() }
                    }
                    break
                }
            }
        }
    }

    private fun stopHeartBeat() {
        heartBeatJob?.cancel()
        heartBeatJob = null
    }

    protected fun handleReadError() {
        if (!isManuallyDisconnected) {
            Log.d(TAG, "read failed, triggering reconnection...")
            clientScope.launch { autoReconnect() }
        }
    }

    protected fun handleWriteError() {
        if (!isManuallyDisconnected) {
            Log.d(TAG, "write failed, triggering reconnection...")
            clientScope.launch { autoReconnect() }
        }
    }

    /** 子类连接成功后调用，启动心跳 */
    protected fun onConnectedSuccessfully() {
        isManuallyDisconnected = false
        backgroundReconnectAttempts = 0
        startHeartBeat()
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    /** 销毁客户端，取消所有协程 */
    fun destroy() {
        disconnect()
        clientScope.cancel()
    }
}
