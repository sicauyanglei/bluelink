package com.bluelink.transfer

import kotlinx.coroutines.MainScope

interface TransferClient {
    val isConnected: Boolean
    suspend fun readPacket(): ByteArray?
    suspend fun writePacket(command: Byte, data: ByteArray): Boolean
    // Write raw data without protocol header (for streaming uploads)
    suspend fun writeRaw(data: ByteArray): Boolean
    fun disconnect()

    // Auto-reconnect - returns Result.success if reconnected, Result.failure otherwise
    // Default implementation does nothing (used by TcpClient)
    suspend fun autoReconnect(): Result<Unit> = Result.failure(Exception("Auto-reconnect not supported"))
}
