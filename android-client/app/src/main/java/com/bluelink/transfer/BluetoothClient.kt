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
        // Standard Serial Port Profile (SPP) UUID - works better with Windows
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BluetoothClient"
        private const val CONNECT_TIMEOUT_MS = 30000  // 30 seconds connection timeout
        private const val READ_TIMEOUT_MS = 30000     // 30 seconds read timeout
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2000L  // 2 seconds between attempts
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    var toast: ((String) -> Unit)? = null

    // Auto-reconnect support
    private var lastConnectedDevice: BluetoothDevice? = null
    var onDisconnected: (() -> Unit)? = null
    var onReconnecting: ((attempt: Int) -> Unit)? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    // Set socket timeout using reflection on the underlying FileDescriptor
    private fun setSocketTimeout(socket: BluetoothSocket?, timeoutMs: Int) {
        try {
            val fileDescriptor = socket?.javaClass?.getField("mSocketHandle")?.let {
                it.isAccessible = true
                val fdField = socket.javaClass.getDeclaredField("mSocketHandle")
                fdField.isAccessible = true
                // Get the file descriptor from the input stream
                val fdObject = inputStream?.javaClass?.getDeclaredField("mFd")
                fdObject?.isAccessible = true
                fdObject?.get(inputStream) as? java.io.FileDescriptor
            }
            if (fileDescriptor != null) {
                fileDescriptor.sync()
                // Use reflection to call setSocketTimeout on the socket
                val method = socket?.javaClass?.getMethod("setSocketTimeout", Int::class.java)
                method?.invoke(socket, timeoutMs)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not set socket timeout via mSocketHandle: ${e.message}")
            // Try alternative approach using reflection on the socket itself
            try {
                val method = socket?.javaClass?.getMethod("setSocketTimeout", Int::class.java)
                method?.invoke(socket, timeoutMs)
            } catch (e2: Exception) {
                Log.d(TAG, "Could not set socket timeout: ${e2.message}")
            }
        }
    }

    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket?.close()
            Log.d(TAG, "connecting to: ${device.name} (${device.address})")

            // Use reflection to create RFCOMM socket
            @Suppress("UNCHECKED_CAST")
            val createSocket = device.javaClass.getMethod(
                "createRfcommSocketToServiceRecord",
                UUID::class.java
            )
            socket = createSocket.invoke(device, SERVICE_UUID) as BluetoothSocket

            socket?.connect()
            // Set connection timeout using reflection
            setSocketTimeout(socket, CONNECT_TIMEOUT_MS)
            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            // Set read timeout after getting streams
            setSocketTimeout(socket, READ_TIMEOUT_MS)

            // Remember this device for auto-reconnect
            lastConnectedDevice = device

            Log.d(TAG, "connected successfully with ${CONNECT_TIMEOUT_MS}ms timeout")
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

    // Auto-reconnect to the last connected device
    override suspend fun autoReconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        val device = lastConnectedDevice
        if (device == null) {
            Log.d(TAG, "autoReconnect: no last connected device")
            return@withContext Result.failure(Exception("无上次连接设备"))
        }

        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "autoReconnect: attempt $attempt of $MAX_RECONNECT_ATTEMPTS")
            onReconnecting?.invoke(attempt)

            try {
                // Clean up old socket
                socket?.close()
                socket = null
                inputStream = null
                outputStream = null

                // Create new socket and connect
                @Suppress("UNCHECKED_CAST")
                val createSocket = device.javaClass.getMethod(
                    "createRfcommSocketToServiceRecord",
                    UUID::class.java
                )
                socket = createSocket.invoke(device, SERVICE_UUID) as BluetoothSocket

                socket?.connect()
                setSocketTimeout(socket, CONNECT_TIMEOUT_MS)
                inputStream = socket?.inputStream
                outputStream = socket?.outputStream
                setSocketTimeout(socket, READ_TIMEOUT_MS)

                Log.d(TAG, "autoReconnect: success on attempt $attempt")
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "autoReconnect: attempt $attempt failed: ${e.message}")
                toast?.invoke("重连失败 (${attempt}/$MAX_RECONNECT_ATTEMPTS)")

                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }

        Log.d(TAG, "autoReconnect: all attempts exhausted")
        onDisconnected?.invoke()
        Result.failure(Exception("重连失败"))
    }

    override fun disconnect() {
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

    override suspend fun readPacket(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Read 5-byte header
            val header = ByteArray(5)
            Log.d(TAG, "[${System.currentTimeMillis()%100000}] readPacket: waiting for header...")
            val headerRead = inputStream?.read(header) ?: return@withContext null
            Log.d(TAG, "[${System.currentTimeMillis()%100000}] readPacket: headerRead=$headerRead")
            if (headerRead != 5) {
                Log.w(TAG, "incomplete header: $headerRead bytes")
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
                    return@withContext null
                }
                offset += read
            }

            Log.d(TAG, "readPacket: cmd=${header[0]}, len=$length, totalRead=${5+length}")
            header + data
        } catch (e: Exception) {
            Log.e(TAG, "readPacket error: ${e.javaClass.simpleName}: ${e.message}")
            toast?.invoke("读取异常: ${e.message}")
            null
        }
    }

    override suspend fun writePacket(command: Byte, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
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
            false
        }
    }

    override suspend fun writeRaw(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            outputStream?.write(data)
            outputStream?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeRaw error: ${e.javaClass.simpleName}: ${e.message}")
            false
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
