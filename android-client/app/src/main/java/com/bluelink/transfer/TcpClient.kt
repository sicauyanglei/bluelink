package com.bluelink.transfer

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class TcpClient : TransferClient {
    companion object {
        private const val TAG = "TcpClient"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2000L
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
        private const val BACKGROUND_RECONNECT_INTERVAL_MS = 5000L
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var lastHost: String? = null
    private var lastPort: Int? = null
    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReconnected: (() -> Unit)? = null

    private var heartBeatJob: kotlinx.coroutines.Job? = null
    private var backgroundReconnectJob: kotlinx.coroutines.Job? = null
    private var isManuallyDisconnected = false

    private var _isReconnecting = false
    override val isReconnecting: Boolean get() = _isReconnecting

    override val isConnected: Boolean get() = socket?.isConnected == true && !_isReconnecting

    override val hasConnectionInfo: Boolean get() = lastHost != null && lastPort != null

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket?.close()
            Log.d(TAG, "connecting to: $host:$port")
            socket = Socket()

            socket?.apply {
                tcpNoDelay = true
                soTimeout = 60000
                sendBufferSize = 524288
                receiveBufferSize = 524288
                keepAlive = true
            }

            socket?.connect(java.net.InetSocketAddress(host, port), 10000)

            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()

            lastHost = host
            lastPort = port
            isManuallyDisconnected = false

            Log.d(TAG, "connected successfully with optimized parameters")
            startHeartBeat()
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "connect error: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun autoReconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        val host = lastHost
        val port = lastPort
        if (host == null || port == null) {
            Log.d(TAG, "autoReconnect: no last host/port")
            return@withContext Result.failure(Exception("无上次连接信息"))
        }

        _isReconnecting = true
        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "autoReconnect: attempt $attempt of $MAX_RECONNECT_ATTEMPTS")
            onReconnecting?.invoke(attempt)

            try {
                socket?.close()
                socket = null
                inputStream = null
                outputStream = null

                socket = Socket()
                socket?.apply {
                    tcpNoDelay = true
                    soTimeout = 60000
                    sendBufferSize = 524288
                    receiveBufferSize = 524288
                    keepAlive = true
                }
                socket?.connect(java.net.InetSocketAddress(host, port), 10000)

                inputStream = socket?.getInputStream()
                outputStream = socket?.getOutputStream()

                Log.d(TAG, "autoReconnect: success on attempt $attempt")
                _isReconnecting = false
                startHeartBeat()
                onReconnected?.invoke()
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "autoReconnect: attempt $attempt failed: ${e.message}")

                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }

        Log.d(TAG, "autoReconnect: all attempts exhausted, starting background reconnect")
        _isReconnecting = false
        onDisconnected?.invoke()
        startBackgroundReconnect()
        Result.failure(Exception("重连失败，后台继续尝试"))
    }

    private fun startBackgroundReconnect() {
        stopBackgroundReconnect()
        if (isManuallyDisconnected || lastHost == null || lastPort == null) return

        Log.d(TAG, "startBackgroundReconnect: starting periodic reconnect")
        backgroundReconnectJob = kotlinx.coroutines.GlobalScope.launch {
            while (!isManuallyDisconnected && !isConnected) {
                delay(BACKGROUND_RECONNECT_INTERVAL_MS)
                if (isManuallyDisconnected || isConnected) break

                Log.d(TAG, "backgroundReconnect: attempting reconnect...")
                _isReconnecting = true
                onReconnecting?.invoke(0)

                try {
                    val host = lastHost ?: break
                    val port = lastPort ?: break

                    socket?.close()
                    socket = null
                    inputStream = null
                    outputStream = null

                    withContext(Dispatchers.IO) {
                        socket = Socket()
                        socket?.apply {
                            tcpNoDelay = true
                            soTimeout = 60000
                            sendBufferSize = 524288
                            receiveBufferSize = 524288
                            keepAlive = true
                        }
                        socket?.connect(java.net.InetSocketAddress(host, port), 10000)
                        inputStream = socket?.getInputStream()
                        outputStream = socket?.getOutputStream()
                    }

                    Log.d(TAG, "backgroundReconnect: success!")
                    _isReconnecting = false
                    startHeartBeat()
                    onReconnected?.invoke()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "backgroundReconnect: failed: ${e.message}")
                    _isReconnecting = false
                }
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
            socket?.close()
        } catch (e: IOException) { }
        socket = null
        inputStream = null
        outputStream = null
        lastHost = null
        lastPort = null
    }

    private fun startHeartBeat() {
        stopHeartBeat()
        heartBeatJob = kotlinx.coroutines.GlobalScope.launch {
            while (isConnected && !isManuallyDisconnected) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)
                    Log.d(TAG, "sending heartbeat...")
                    writeRaw(byteArrayOf(0x00))
                } catch (e: Exception) {
                    Log.e(TAG, "heartbeat error: ${e.message}")
                    if (!isManuallyDisconnected) {
                        Log.d(TAG, "heartbeat failed, triggering reconnection...")
                        kotlinx.coroutines.GlobalScope.launch {
                            autoReconnect()
                        }
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

    override suspend fun readPacket(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val header = ByteArray(5)
            val headerRead = inputStream?.read(header) ?: run {
                handleReadError()
                return@withContext null
            }
            if (headerRead != 5) {
                handleReadError()
                return@withContext null
            }

            val length = ((header[1].toInt() and 0xFF) shl 24) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 8) or
                        (header[4].toInt() and 0xFF)

            if (length <= 0) return@withContext header

            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = inputStream?.read(data, offset, length - offset) ?: 0
                if (read <= 0) {
                    handleReadError()
                    return@withContext null
                }
                offset += read
            }

            header + data
        } catch (e: Exception) {
            Log.e(TAG, "readPacket error: ${e.message}")
            handleReadError()
            null
        }
    }

    private fun handleReadError() {
        if (!isManuallyDisconnected) {
            Log.d(TAG, "read failed, triggering reconnection...")
            kotlinx.coroutines.GlobalScope.launch {
                autoReconnect()
            }
        }
    }

    override suspend fun writePacket(command: Byte, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (outputStream == null) {
            android.util.Log.e(TAG, "writePacket: outputStream is null!")
            handleWriteError()
            return@withContext false
        }
        try {
            val length = data.size
            val header = byteArrayOf(command) + intToBytes(length)
            android.util.Log.d(TAG, "writePacket: command=$command, length=$length, total=${header.size + data.size}")
            outputStream?.write(header)
            outputStream?.write(data)
            outputStream?.flush()
            android.util.Log.d(TAG, "writePacket: completed successfully")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "writePacket error: ${e.javaClass.simpleName}: ${e.message}")
            try {
                kotlinx.coroutines.delay(100)
                val length = data.size
                val header = byteArrayOf(command) + intToBytes(length)
                outputStream?.write(header)
                outputStream?.write(data)
                outputStream?.flush()
                android.util.Log.d(TAG, "writePacket: retry succeeded")
                true
            } catch (e2: Exception) {
                android.util.Log.e(TAG, "writePacket retry failed: ${e2.javaClass.simpleName}: ${e2.message}")
                handleWriteError()
                false
            }
        }
    }

    override suspend fun writeRaw(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (outputStream == null) {
            Log.e(TAG, "writeRaw: outputStream is null!")
            handleWriteError()
            return@withContext false
        }
        try {
            outputStream?.write(data)
            outputStream?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeRaw error: ${e.javaClass.simpleName}: ${e.message}")
            try {
                kotlinx.coroutines.delay(100)
                outputStream?.write(data)
                outputStream?.flush()
                Log.d(TAG, "writeRaw: retry succeeded")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "writeRaw retry failed: ${e2.javaClass.simpleName}: ${e2.message}")
                handleWriteError()
                false
            }
        }
    }

    private fun handleWriteError() {
        if (!isManuallyDisconnected) {
            Log.d(TAG, "write failed, triggering reconnection...")
            kotlinx.coroutines.GlobalScope.launch {
                autoReconnect()
            }
        }
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
}
