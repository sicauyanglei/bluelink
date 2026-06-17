package com.bluelink.transfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothClient(private val adapter: BluetoothAdapter) : BaseTransferClient() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BluetoothClient"
        private const val CONNECT_TIMEOUT_MS = 30000
        private const val READ_TIMEOUT_MS = 30000
    }

    private var socket: BluetoothSocket? = null
    private var lastConnectedDevice: BluetoothDevice? = null

    override val hasLastConnectionInfo: Boolean get() = lastConnectedDevice != null

    override fun isSocketConnected(): Boolean = socket?.isConnected == true

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
            setSocketTimeout(CONNECT_TIMEOUT_MS)
            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            setSocketTimeout(READ_TIMEOUT_MS)

            lastConnectedDevice = device
            onConnectedSuccessfully()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "connect error: ${e.javaClass.simpleName}: ${e.message}")
            toast?.invoke("连接错误: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun doConnect(): Result<Unit> {
        val device = lastConnectedDevice ?: return Result.failure(Exception("无上次连接设备"))
        return try {
            @Suppress("UNCHECKED_CAST")
            val createSocket = device.javaClass.getMethod(
                "createRfcommSocketToServiceRecord",
                UUID::class.java
            )
            socket = createSocket.invoke(device, SERVICE_UUID) as BluetoothSocket
            socket?.connect()
            setSocketTimeout(CONNECT_TIMEOUT_MS)
            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            setSocketTimeout(READ_TIMEOUT_MS)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "doConnect failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * P1-13: 移除依赖私有字段的反射实现，仅尝试公开 API
     */
    override fun setSocketTimeout(timeoutMs: Int) {
        try {
            val method = socket?.javaClass?.getMethod("setSocketTimeout", Int::class.java)
            method?.invoke(socket, timeoutMs)
        } catch (e: Exception) {
            Log.d(TAG, "setSocketTimeout not supported: ${e.message}")
        }
    }

    override fun closeSocket() {
        try { socket?.close() } catch (e: Exception) {
            Log.w(TAG, "closeSocket error: ${e.message}")
        }
        socket = null
    }

    override fun disconnect() {
        super.disconnect()
        lastConnectedDevice = null
    }
}
