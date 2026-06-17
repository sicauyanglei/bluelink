package com.bluelink.transfer

interface TransferClient {
    val isConnected: Boolean
    val isReconnecting: Boolean
    val hasConnectionInfo: Boolean
    suspend fun readPacket(): ByteArray?
    suspend fun writePacket(command: Byte, data: ByteArray): Boolean
    // Write raw data without protocol header (for streaming uploads)
    suspend fun writeRaw(data: ByteArray): Boolean
    fun disconnect()

    // Auto-reconnect - returns Result.success if reconnected, Result.failure otherwise
    // Default implementation does nothing (used by clients that don't support reconnect)
    suspend fun autoReconnect(): Result<Unit> = Result.failure(Exception("Auto-reconnect not supported"))
}
