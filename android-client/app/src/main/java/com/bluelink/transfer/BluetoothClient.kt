package com.bluelink.transfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
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
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2000L
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
        private const val BACKGROUND_RECONNECT_INTERVAL_MS = 5000L
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    var toast: ((String) -> Unit)? = null

    private var lastConnectedDevice: BluetoothDevice? = null
    var onDisconnected: (() -> Unit)? = null
    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onReconnected: (() -> Unit)? = null

    private var heartBeatJob: kotlinx.coroutines.Job? = null
    private var backgroundReconnectJob: kotlinx.coroutines.Job? = null
    private var isManuallyDisconnected = false

    private var _isReconnecting = false
    override val isReconnecting: Boolean get() = _isReconnecting

    override val isConnected: Boolean get() = socket?.isConnected == true && !_isReconnecting

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
        val device = lastConnectedDevice
        if (device == null) {
            Log.d(TAG, "autoReconnect: no last connected device")
            return@withContext Result.failure(Exception("无上次连接设备"))
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
                startHeartBeat()
                onReconnected?.invoke()
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "autoReconnect: attempt $attempt failed: ${e.message}")
                toast?.invoke("重连失败 (${attempt}/$MAX_RECONNECT_ATTEMPTS)")

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
        if (isManuallyDisconnected || lastConnectedDevice == null) return

        Log.d(TAG, "startBackgroundReconnect: starting periodic reconnect")
        backgroundReconnectJob = coroutineScope.launch {
            while (!isManuallyDisconnected && !isConnected) {
                delay(BACKGROUND_RECONNECT_INTERVAL_MS)
                if (isManuallyDisconnected || isConnected) break

                val device = lastConnectedDevice ?: break
                Log.d(TAG, "backgroundReconnect: attempting reconnect...")
                _isReconnecting = true
                onReconnecting?.invoke(0)

                try {
                    socket?.close()
                    socket = null
                    inputStream = null
                    outputStream = null

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
        // 取消所有协程
        coroutineScope.cancel()
        _isReconnecting = false
        try {
            inputStream?.close()
            outputStream?.close()
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

    private fun startHeartBeat() {
        stopHeartBeat()
        heartBeatJob = coroutineScope.launch {
            while (isConnected && !isManuallyDisconnected) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)
                    Log.d(TAG, "sending heartbeat...")
                    writeRaw(byteArrayOf(0x00))
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
            val header = ByteArray(5)
            Log.d(TAG, "[${System.currentTimeMillis()%100000}] readPacket: waiting for header...")
            val headerRead = inputStream?.read(header) ?: run {
                handleReadError()
                return@withContext null
            }
            Log.d(TAG, "[${System.currentTimeMillis()%100000}] readPacket: headerRead=$headerRead")
            if (headerRead != 5) {
                handleReadError()
                return@withContext null
            }

            val length = ((header[1].toInt() and 0xFF) shl 24) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 8) or
                        (header[4].toInt() and 0xFF)

            Log.d(TAG, "[${System.currentTimeMillis()%100000}] readPacket: cmd=${header[0]}, len=$length")
            if (length <= 0) return@withContext header

            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = inputStream?.read(data, offset, length - offset) ?: 0
                if (read <= 0) {
                    Log.w(TAG, "read returned $read, connection may be closed")
                    handleReadError()
                    return@withContext null
                }
                offset += read
            }

            Log.d(TAG, "readPacket: cmd=${header[0]}, len=$length, totalRead=${5+length}")
            header + data
        } catch (e: Exception) {
            Log.e(TAG, "readPacket error: ${e.javaClass.simpleName}: ${e.message}")
            toast?.invoke("读取异常: ${e.message}")
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
