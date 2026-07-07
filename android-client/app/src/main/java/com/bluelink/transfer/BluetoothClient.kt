package com.bluelink.transfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothClient(private val adapter: BluetoothAdapter) : TransferClient {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BluetoothClient"
        private const val CONNECT_TIMEOUT_MS = 30000
        private const val READ_TIMEOUT_MS = 30000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val HEARTBEAT_INTERVAL_MS = 8000L
        private const val HEARTBEAT_TIMEOUT_MS = 8000L
        private const val BACKGROUND_RECONNECT_INTERVAL_MS = 3000L
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    var toast: ((String) -> Unit)? = null

    var lastConnectedDevice: BluetoothDevice? = null
        private set
    var onDisconnected: (() -> Unit)? = null
    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onReconnected: (() -> Unit)? = null

    private var heartBeatJob: kotlinx.coroutines.Job? = null
    private var backgroundReconnectJob: kotlinx.coroutines.Job? = null
    private var isManuallyDisconnected = false
    private val reconnectMutex = Mutex()

    private var _isReconnecting = false
    override val isReconnecting: Boolean get() = _isReconnecting

    // BluetoothSocket.isConnected 反映实际连接状态，但仍需结合 outputStream 判断
    override val isConnected: Boolean get() =
        socket?.isConnected == true && outputStream != null && !_isReconnecting

    override val hasConnectionInfo: Boolean get() = lastConnectedDevice != null

    // 协程作用域 - 关联到特定的连接
    private val coroutineScope = MainScope()

    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket?.close()
            Log.d(TAG, "connecting to: ${device.name} (${device.address})")

            @Suppress("UNCHECKED_CAST")
            val createSocket = device.javaClass.getMethod(
                "createRfcommSocketToServiceRecord",
                UUID::class.java
            )
            socket = createSocket.invoke(device, SERVICE_UUID) as BluetoothSocket

            socket?.connect()
            inputStream = socket?.inputStream
            outputStream = socket?.outputStream

            lastConnectedDevice = device
            isManuallyDisconnected = false

            Log.d(TAG, "connected successfully")
            startHeartBeat()
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "IO error: ${e.javaClass.simpleName}: ${e.message}")
            toast?.invoke("IO错误: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "error: ${e.javaClass.simpleName}: ${e.message}")
            toast?.invoke("错误: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override suspend fun autoReconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        // 防止多个autoReconnect同时运行
        if (!reconnectMutex.tryLock()) {
            Log.d(TAG, "autoReconnect: another reconnection in progress, skipping")
            return@withContext Result.failure(Exception("已有重连进行中"))
        }

        val result: Result<Unit>
        try {
            val device = lastConnectedDevice
            if (device == null) {
                Log.d(TAG, "autoReconnect: no last connected device")
                return@withContext Result.failure(Exception("无上次连接设备"))
            }

            if (isManuallyDisconnected) {
                Log.d(TAG, "autoReconnect: manually disconnected, abort")
                return@withContext Result.failure(Exception("已手动断开"))
            }

            // 停止后台重连，避免冲突
            stopBackgroundReconnect()

            Log.d(TAG, "autoReconnect: starting")
            _isReconnecting = true
            onReconnecting?.invoke(1)  // 只通知一次UI
            var reconnected = false
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                Log.d(TAG, "autoReconnect: attempt $attempt of $MAX_RECONNECT_ATTEMPTS")

                try {
                    cleanupForReconnect()

                    @Suppress("UNCHECKED_CAST")
                    val createSocket = device.javaClass.getMethod(
                        "createRfcommSocketToServiceRecord",
                        UUID::class.java
                    )
                    socket = createSocket.invoke(device, SERVICE_UUID) as BluetoothSocket

                    socket?.connect()
                    inputStream = socket?.inputStream
                    outputStream = socket?.outputStream

                    Log.d(TAG, "autoReconnect: success on attempt $attempt")
                    _isReconnecting = false
                    reconnected = true
                    startHeartBeat()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "autoReconnect: attempt $attempt failed: ${e.message}")

                    if (attempt < MAX_RECONNECT_ATTEMPTS) {
                        // 首次失败立即重试，后续递增延迟: 0, 500, 1000, 1500ms
                        val delayMs = (attempt - 1) * 500L
                        if (delayMs > 0) delay(delayMs)
                    }
                }
            }

            if (reconnected) {
                result = Result.success(Unit)
            } else {
                Log.d(TAG, "autoReconnect: all attempts exhausted, starting background reconnect")
                _isReconnecting = false
                onDisconnected?.invoke()
                startBackgroundReconnect()
                result = Result.failure(Exception("重连失败，后台继续尝试"))
            }
        } finally {
            reconnectMutex.unlock()
        }

        // onReconnected 在 mutex 释放后调用，避免回调异常阻塞重连
        if (result.isSuccess) {
            onReconnected?.invoke()
        }
        result
    }

    private fun startBackgroundReconnect() {
        stopBackgroundReconnect()
        if (isManuallyDisconnected || lastConnectedDevice == null) return

        Log.d(TAG, "startBackgroundReconnect: starting periodic reconnect")
        backgroundReconnectJob = coroutineScope.launch {
            _isReconnecting = true
            onReconnecting?.invoke(0)
            while (!isManuallyDisconnected && !isConnected) {
                val device = lastConnectedDevice ?: break
                Log.d(TAG, "backgroundReconnect: attempting reconnect...")

                try {
                    cleanupForReconnect()

                    withContext(Dispatchers.IO) {
                        @Suppress("UNCHECKED_CAST")
                        val createSocket = device.javaClass.getMethod(
                            "createRfcommSocketToServiceRecord",
                            UUID::class.java
                        )
                        socket = createSocket.invoke(device, SERVICE_UUID) as BluetoothSocket
                        socket?.connect()
                        inputStream = socket?.inputStream
                        outputStream = socket?.outputStream
                    }

                    Log.d(TAG, "backgroundReconnect: success!")
                    _isReconnecting = false
                    startHeartBeat()
                    onReconnected?.invoke()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "backgroundReconnect: failed: ${e.message}")
                }

                // 失败后才 delay，下次再尝试
                delay(BACKGROUND_RECONNECT_INTERVAL_MS)
            }
            _isReconnecting = false
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
        // 先关闭流，再关闭 socket，避免资源泄漏
        try {
            outputStream?.close()
        } catch (e: Exception) { }
        try {
            inputStream?.close()
        } catch (e: Exception) { }
        try {
            socket?.close()
            Log.d(TAG, "disconnected")
        } catch (e: IOException) {
            Log.w(TAG, "disconnect error: ${e.message}")
        }
        socket = null
        inputStream = null
        outputStream = null
        lastConnectedDevice = null
    }

    /** 重连前清理旧资源（不清除 lastConnectedDevice） */
    private fun cleanupForReconnect() {
        try { outputStream?.close() } catch (e: Exception) { }
        try { inputStream?.close() } catch (e: Exception) { }
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        inputStream = null
        outputStream = null
    }

    private fun startHeartBeat() {
        stopHeartBeat()
        heartBeatJob = coroutineScope.launch {
            while (isConnected && !isManuallyDisconnected) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)
                    Log.d(TAG, "sending heartbeat...")
                    // 心跳包：5字节header（命令0x00 + 长度0），符合协议格式
                    writeRaw(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00))
                } catch (e: Exception) {
                    Log.e(TAG, "heartbeat error: ${e.message}")
                    if (!isManuallyDisconnected) {
                        Log.d(TAG, "heartbeat failed, triggering reconnection...")
                        launch {
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
            val input = inputStream
            if (input == null) {
                handleReadError()
                return@withContext null
            }

            // 循环读取完整的5字节header（蓝牙也是流式传输，可能一次读不完）
            val header = ByteArray(5)
            var headerOffset = 0
            while (headerOffset < 5) {
                val read = input.read(header, headerOffset, 5 - headerOffset)
                if (read <= 0) {
                    Log.e(TAG, "readPacket: header read failed at offset=$headerOffset")
                    handleReadError()
                    return@withContext null
                }
                headerOffset += read
            }

            val length = ((header[1].toInt() and 0xFF) shl 24) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 8) or
                        (header[4].toInt() and 0xFF)

            if (length <= 0) return@withContext header

            // 循环读取完整的data部分
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(data, offset, length - offset)
                if (read <= 0) {
                    Log.w(TAG, "read returned $read at offset=$offset, connection may be closed")
                    handleReadError()
                    return@withContext null
                }
                offset += read
            }

            header + data
        } catch (e: Exception) {
            Log.e(TAG, "readPacket error: ${e.javaClass.simpleName}: ${e.message}")
            handleReadError()
            null
        }
    }

    private fun handleReadError() {
        if (!isManuallyDisconnected) {
            Log.d(TAG, "read failed, triggering reconnection...")
            coroutineScope.launch {
                autoReconnect()
            }
        }
    }

    override suspend fun writePacket(command: Byte, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (outputStream == null) {
            Log.e(TAG, "writePacket: outputStream is null!")
            handleWriteError()
            return@withContext false
        }
        try {
            val length = data.size
            val header = byteArrayOf(command) + intToBytes(length)
            Log.d(TAG, "[${System.currentTimeMillis()%100000}] writePacket: cmd=$command, len=$length, headerBytes=${header.size}")
            outputStream?.write(header)
            outputStream?.write(data)
            outputStream?.flush()
            Log.d(TAG, "[${System.currentTimeMillis()%100000}] writePacket: completed, totalBytes=${header.size + data.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writePacket error: ${e.javaClass.simpleName}: ${e.message}")
            try {
                kotlinx.coroutines.delay(100)
                val length = data.size
                val header = byteArrayOf(command) + intToBytes(length)
                outputStream?.write(header)
                outputStream?.write(data)
                outputStream?.flush()
                Log.d(TAG, "writePacket: retry succeeded")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "writePacket retry failed: ${e2.javaClass.simpleName}: ${e2.message}")
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
                kotlinx.coroutines.delay(50)
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
            coroutineScope.launch {
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
