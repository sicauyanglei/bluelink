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
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // Auto-reconnect support
    private var lastHost: String? = null
    private var lastPort: Int? = null
    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket?.close()
            Log.d(TAG, "connecting to: $host:$port")
            socket = Socket(host, port)
            socket?.soTimeout = 30000 // 30 second timeout
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()

            // Remember for auto-reconnect
            lastHost = host
            lastPort = port

            Log.d(TAG, "connected successfully")
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "connect error: ${e.message}")
            Result.failure(e)
        }
    }

    // Auto-reconnect to the last connected host
    override suspend fun autoReconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        val host = lastHost
        val port = lastPort
        if (host == null || port == null) {
            Log.d(TAG, "autoReconnect: no last host/port")
            return@withContext Result.failure(Exception("无上次连接信息"))
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
                socket = Socket(host, port)
                socket?.soTimeout = 30000
                inputStream = socket?.getInputStream()
                outputStream = socket?.getOutputStream()

                Log.d(TAG, "autoReconnect: success on attempt $attempt")
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "autoReconnect: attempt $attempt failed: ${e.message}")

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
            socket?.close()
        } catch (e: IOException) { }
        socket = null
        inputStream = null
        outputStream = null
        lastHost = null
        lastPort = null
    }

    override suspend fun readPacket(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Read 5-byte header
            val header = ByteArray(5)
            val headerRead = inputStream?.read(header) ?: return@withContext null
            if (headerRead != 5) return@withContext null

            val length = ((header[1].toInt() and 0xFF) shl 24) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 8) or
                        (header[4].toInt() and 0xFF)

            if (length <= 0) return@withContext header

            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = inputStream?.read(data, offset, length - offset) ?: 0
                if (read <= 0) return@withContext null
                offset += read
            }

            header + data
        } catch (e: Exception) {
            Log.e(TAG, "readPacket error: ${e.message}")
            null
        }
    }

    override suspend fun writePacket(command: Byte, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val length = data.size
            val header = byteArrayOf(command) + intToBytes(length)
            android.util.Log.d(TAG, "writePacket: command=$command, length=$length, total=${header.size + data.size}")
            if (outputStream == null) {
                android.util.Log.e(TAG, "writePacket: outputStream is null!")
                return@withContext false
            }
            outputStream?.write(header)
            outputStream?.write(data)
            outputStream?.flush()
            android.util.Log.d(TAG, "writePacket: completed successfully")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "writePacket error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override suspend fun writeRaw(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            outputStream?.write(data)
            outputStream?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeRaw error: ${e.message}")
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
