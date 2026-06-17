package com.bluelink.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class TcpClient : BaseTransferClient() {

    companion object {
        private const val TAG = "TcpClient"
    }

    private var socket: Socket? = null
    private var lastHost: String? = null
    private var lastPort: Int? = null

    override val hasLastConnectionInfo: Boolean get() = lastHost != null && lastPort != null

    override fun isSocketConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket?.close()
            Log.d(TAG, "connecting to: $host:$port")
            socket = createConfiguredSocket()
            socket?.connect(InetSocketAddress(host, port), 10000)

            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()

            lastHost = host
            lastPort = port
            onConnectedSuccessfully()
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "connect error: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun doConnect(): Result<Unit> {
        val host = lastHost ?: return Result.failure(Exception("无上次 host"))
        val port = lastPort ?: return Result.failure(Exception("无上次 port"))
        return try {
            socket = createConfiguredSocket()
            socket?.connect(InetSocketAddress(host, port), 10000)
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "doConnect failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun createConfiguredSocket(): Socket {
        return Socket().apply {
            tcpNoDelay = true
            soTimeout = 60000
            sendBufferSize = 524288
            receiveBufferSize = 524288
            keepAlive = true
        }
    }

    override fun closeSocket() {
        try { socket?.close() } catch (e: IOException) {
            Log.w(TAG, "closeSocket error: ${e.message}")
        }
        socket = null
    }

    override fun disconnect() {
        super.disconnect()
        lastHost = null
        lastPort = null
    }
}
