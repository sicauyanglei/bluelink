package com.bluelink.transfer

interface TransferClient {
    val isConnected: Boolean
    val isReconnecting: Boolean get() = false
    val hasConnectionInfo: Boolean get() = false
    suspend fun readPacket(): ByteArray?
    suspend fun writePacket(command: Byte, data: ByteArray): Boolean
    suspend fun writeRaw(data: ByteArray): Boolean
    fun disconnect()
    suspend fun autoReconnect(): Result<Unit> = Result.failure(Exception("Auto-reconnect not supported"))
}
