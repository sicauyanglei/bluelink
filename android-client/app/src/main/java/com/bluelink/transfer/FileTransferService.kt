package com.bluelink.transfer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*

// Download result with actual filename used
data class DownloadResult(
    val bytesDownloaded: Long,
    val actualFileName: String
)

// Protocol commands - must match PC server
object FileTransferProtocol {
    const val CMD_LIST_REQUEST: Byte = 0x01
    const val CMD_LIST_RESPONSE: Byte = 0x02
    const val CMD_DOWNLOAD_REQUEST: Byte = 0x03
    const val CMD_DOWNLOAD_RESPONSE: Byte = 0x04
    const val CMD_UPLOAD_REQUEST: Byte = 0x05
    const val CMD_UPLOAD_RESPONSE: Byte = 0x06
    const val CMD_DELETE_REQUEST: Byte = 0x07
    const val CMD_TRANSFER_COMPLETE: Byte = 0x08
    const val CMD_NAVIGATE_REQUEST: Byte = 0x09  // Navigate to subdirectory
    const val CMD_BACK_REQUEST: Byte = 0x0A     // Go back to parent
    const val CMD_CREATE_FOLDER_REQUEST: Byte = 0x0B  // Create folder
    const val CMD_SHARE_PATH_CHANGED: Byte = 0x0C  // Share path changed notification
    const val CMD_UPLOAD_CHUNK: Byte = 0x0D  // Upload chunk (for streaming mode)
    const val CMD_SUCCESS: Byte = -2  // 0xFE as signed byte
    const val CMD_ERROR: Byte = -1    // 0xFF as signed byte

    // Protocol header: command(1 byte) + length(4 bytes) + data(N bytes)
    const val HEADER_SIZE = 5

    fun createPacket(command: Byte, data: ByteArray? = null): ByteArray {
        val dataArray = data ?: byteArrayOf()
        val packet = ByteArray(HEADER_SIZE + dataArray.size)
        packet[0] = command
        packet[1] = (dataArray.size shr 24).toByte()
        packet[2] = (dataArray.size shr 16).toByte()
        packet[3] = (dataArray.size shr 8).toByte()
        packet[4] = dataArray.size.toByte()
        dataArray.copyInto(packet, HEADER_SIZE)
        return packet
    }
}

data class FileListResult(
    val currentPath: String,
    val items: List<FileItem>
)

class FileTransferService(private val client: TransferClient) {

    var onPathChanged: (() -> Unit)? = null
    var onDownloadProgress: ((fileName: String, downloaded: Long, total: Long) -> Unit)? = null

    private suspend fun handlePathChangedNotification(data: ByteArray) {
        val newPath = String(data, Charsets.UTF_8)
        Log.d("FileTransferService", "PC share path changed to: $newPath")
        onPathChanged?.invoke()
    }

    suspend fun getFileList(): Result<FileListResult> = withContext(Dispatchers.IO) {
        try {
            client.writePacket(FileTransferProtocol.CMD_LIST_REQUEST, byteArrayOf())
            val response = client.readPacket()
            if (response == null) {
                return@withContext Result.failure(Exception("连接断开"))
            }

            val command = response[0]

            // Handle path changed notification - notify callback and get actual response
            if (command == FileTransferProtocol.CMD_SHARE_PATH_CHANGED) {
                val data = response.copyOfRange(5, response.size)
                handlePathChangedNotification(data)
                // Now get the actual list response
                val listResponse = client.readPacket()
                if (listResponse == null || listResponse[0] != FileTransferProtocol.CMD_LIST_RESPONSE) {
                    return@withContext Result.failure(Exception("获取文件列表失败"))
                }
                val listData = listResponse.copyOfRange(5, listResponse.size)
                val json = String(listData, Charsets.UTF_8)
                val result = parseFileListJson(json)
                return@withContext Result.success(result)
            }

            if (command != FileTransferProtocol.CMD_LIST_RESPONSE) {
                return@withContext Result.failure(Exception("服务器响应错误: cmd=$command"))
            }

            val data = response.copyOfRange(5, response.size)
            val json = String(data, Charsets.UTF_8)
            val result = parseFileListJson(json)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun navigateToFolder(folderName: String): Result<FileListResult> = withContext(Dispatchers.IO) {
        try {
            val folderNameBytes = folderName.toByteArray(Charsets.UTF_8)
            val payload = intToBytes(folderNameBytes.size) + folderNameBytes
            client.writePacket(FileTransferProtocol.CMD_NAVIGATE_REQUEST, payload)

            // Read success response first
            val successResponse = client.readPacket()
            if (successResponse == null || successResponse[0] != FileTransferProtocol.CMD_SUCCESS) {
                // Read error response
                if (successResponse != null && successResponse[0] == FileTransferProtocol.CMD_ERROR) {
                    val errorData = successResponse.copyOfRange(5, successResponse.size)
                    return@withContext Result.failure(Exception(String(errorData, Charsets.UTF_8)))
                }
                return@withContext Result.failure(Exception("导航失败"))
            }

            // Then read the new file list
            val response = client.readPacket()
            if (response == null || response[0] != FileTransferProtocol.CMD_LIST_RESPONSE) {
                return@withContext Result.failure(Exception("获取文件列表失败"))
            }

            val data = response.copyOfRange(5, response.size)
            val json = String(data, Charsets.UTF_8)
            val result = parseFileListJson(json)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun goBack(): Result<FileListResult> = withContext(Dispatchers.IO) {
        try {
            client.writePacket(FileTransferProtocol.CMD_BACK_REQUEST, byteArrayOf())

            // Read success response
            val successResponse = client.readPacket()
            if (successResponse == null || successResponse[0] != FileTransferProtocol.CMD_SUCCESS) {
                if (successResponse != null && successResponse[0] == FileTransferProtocol.CMD_ERROR) {
                    val errorData = successResponse.copyOfRange(5, successResponse.size)
                    return@withContext Result.failure(Exception(String(errorData, Charsets.UTF_8)))
                }
                return@withContext Result.failure(Exception("返回失败"))
            }

            // Then read the new file list
            val response = client.readPacket()
            if (response == null || response[0] != FileTransferProtocol.CMD_LIST_RESPONSE) {
                return@withContext Result.failure(Exception("获取文件列表失败"))
            }

            val data = response.copyOfRange(5, response.size)
            val json = String(data, Charsets.UTF_8)
            val result = parseFileListJson(json)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFolder(folderName: String): Result<FileListResult> = withContext(Dispatchers.IO) {
        try {
            val folderNameBytes = folderName.toByteArray(Charsets.UTF_8)
            val payload = intToBytes(folderNameBytes.size) + folderNameBytes
            client.writePacket(FileTransferProtocol.CMD_CREATE_FOLDER_REQUEST, payload)

            // Read success response
            val successResponse = client.readPacket()
            if (successResponse == null || successResponse[0] != FileTransferProtocol.CMD_SUCCESS) {
                if (successResponse != null && successResponse[0] == FileTransferProtocol.CMD_ERROR) {
                    val errorData = successResponse.copyOfRange(5, successResponse.size)
                    return@withContext Result.failure(Exception(String(errorData, Charsets.UTF_8)))
                }
                return@withContext Result.failure(Exception("创建文件夹失败"))
            }

            // Then read the new file list
            val response = client.readPacket()
            if (response == null || response[0] != FileTransferProtocol.CMD_LIST_RESPONSE) {
                return@withContext Result.failure(Exception("获取文件列表失败"))
            }

            val data = response.copyOfRange(5, response.size)
            val json = String(data, Charsets.UTF_8)
            val result = parseFileListJson(json)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Check if file with same name exists in Downloads
    private fun fileExistsInDownloads(context: Context, fileName: String): Boolean {
        val resolver = context.contentResolver
        val projection = arrayOf(
            android.provider.MediaStore.Downloads._ID,
            android.provider.MediaStore.Downloads.DISPLAY_NAME,
            android.provider.MediaStore.Downloads.RELATIVE_PATH
        )
        // 只用文件名匹配，不过滤路径（有些文件可能路径字段不同）
        val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        val sortOrder = "${android.provider.MediaStore.Downloads.DATE_ADDED} DESC"

        android.util.Log.d("FileTransferService", ">>> fileExistsInDownloads: checking fileName=$fileName")
        resolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            android.util.Log.d("FileTransferService", ">>> fileExistsInDownloads: cursor count=${cursor.count}")
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads.RELATIVE_PATH)
                val name = if (nameCol >= 0) cursor.getString(nameCol) else "N/A"
                val path = if (pathCol >= 0) cursor.getString(pathCol) else "N/A"
                android.util.Log.d("FileTransferService", ">>> fileExistsInDownloads: found name=$name, path=$path")
                return true
            }
        }
        android.util.Log.d("FileTransferService", ">>> fileExistsInDownloads: not found")
        return false
    }

    // Generate file name with timestamp suffix if file exists
    // Format: xxx_时间(小时分钟秒，如120131).扩展名
    private fun generateUniqueFileName(originalName: String): String {
        val lastDot = originalName.lastIndexOf('.')
        if (lastDot > 0) {
            val nameWithoutExt = originalName.substring(0, lastDot)
            val extension = originalName.substring(lastDot)
            val timeSuffix = java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            return "${nameWithoutExt}_$timeSuffix$extension"
        } else {
            val timeSuffix = java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            return "${originalName}_$timeSuffix"
        }
    }

    // Delete existing file with same name in Downloads
    private fun deleteExistingFile(context: Context, fileName: String): Boolean {
        val resolver = context.contentResolver
        val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
        val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, "Download/")
        val sortOrder = "${android.provider.MediaStore.Downloads.DATE_ADDED} DESC"

        resolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                val id = cursor.getLong(idColumn)
                val uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                resolver.delete(uri, null, null)
                return true
            }
        }
        return false
    }

    // Download file directly to Downloads using MediaStore API (Android 10+ compatible)
    suspend fun downloadFileToFile(
        context: Context,
        fileName: String,
        offset: Long = 0,
        onProgress: ((Long) -> Unit)? = null
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        try {
            // Always generate unique filename to avoid overwriting existing files
            // Format: xxx_时间(小时分钟秒).扩展名
            val actualFileName = generateUniqueFileName(fileName)

            // 向PC请求文件时使用原始文件名（PC上文件的实际名字）
            val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
            val offsetBytes = longToBytes(offset)
            val payload = intToBytes(fileNameBytes.size) + fileNameBytes + offsetBytes

            client.writePacket(FileTransferProtocol.CMD_DOWNLOAD_REQUEST, payload)

            // Use MediaStore API for Android 10+ compatibility
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, actualFileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                // 设置相对路径确保文件在Downloads目录
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }

            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("无法创建文件"))

            resolver.openOutputStream(uri)?.use { outputStream ->
                var totalReceived = offset
                val buffer = ByteArray(8192)

                while (true) {
                    val response = client.readPacket() ?: return@withContext Result.failure(Exception("连接断开"))
                    val command = response[0]

                    if (command == FileTransferProtocol.CMD_ERROR) {
                        val data = response.copyOfRange(5, response.size)
                        // Delete the pending file on error
                        resolver.delete(uri, null, null)
                        return@withContext Result.failure(Exception(String(data, Charsets.UTF_8)))
                    }

                    if (command == FileTransferProtocol.CMD_TRANSFER_COMPLETE) {
                        break
                    }

                    if (command != FileTransferProtocol.CMD_DOWNLOAD_RESPONSE) {
                        resolver.delete(uri, null, null)
                        return@withContext Result.failure(Exception("服务器响应错误"))
                    }

                    val data = response.copyOfRange(5, response.size)
                    if (data.isEmpty()) break


                    // Write directly to output stream
                    outputStream.write(data)
                    totalReceived += data.size

                    // Report progress callback
                    onProgress?.invoke(totalReceived)
                }

                outputStream.flush()

                // Mark as complete
                val updateValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)

                // Return both bytes downloaded AND the actual filename used
                Result.success(DownloadResult(totalReceived, actualFileName))
            } ?: run {
                resolver.delete(uri, null, null)
                Result.failure(Exception("无法打开输出流"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Download with resume support - offset is the bytes already downloaded
    suspend fun downloadFile(fileName: String, offset: Long = 0, onProgress: ((Long) -> Unit)? = null): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            // Create request: filename length (4 bytes) + filename + offset (8 bytes)
            val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
            val offsetBytes = longToBytes(offset)
            val payload = intToBytes(fileNameBytes.size) + fileNameBytes + offsetBytes

            client.writePacket(FileTransferProtocol.CMD_DOWNLOAD_REQUEST, payload)

            // Read response - may be multiple chunks
            val chunks = mutableListOf<ByteArray>()
            var totalReceived = offset

            while (true) {
                val response = client.readPacket() ?: return@withContext Result.failure(Exception("连接断开"))
                val command = response[0]

                if (command == FileTransferProtocol.CMD_ERROR) {
                    val data = response.copyOfRange(5, response.size)
                    return@withContext Result.failure(Exception(String(data, Charsets.UTF_8)))
                }

                if (command == FileTransferProtocol.CMD_TRANSFER_COMPLETE) {
                    break // Transfer complete
                }

                if (command != FileTransferProtocol.CMD_DOWNLOAD_RESPONSE) {
                    return@withContext Result.failure(Exception("服务器响应错误"))
                }

                val data = response.copyOfRange(5, response.size)
                if (data.isEmpty()) break // Legacy end signal

                totalReceived += data.size
                chunks.add(data)

                // Report progress callback
                onProgress?.invoke(totalReceived)
            }

            // Combine all chunks
            val result = ByteArrayOutputStream().use { out ->
                chunks.forEach { out.write(it) }
                out.toByteArray()
            }

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Upload with resume support - uses offset for resuming interrupted transfers
    suspend fun uploadFile(fileName: String, data: ByteArray, offset: Long = 0): Result<Unit> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        android.util.Log.d("FileTransferService", "=== [UPLOAD] uploadFile START ===")
        android.util.Log.d("FileTransferService", "=== [UPLOAD] fileName=$fileName")
        android.util.Log.d("FileTransferService", "=== [UPLOAD] dataSize=${data.size} bytes")
        android.util.Log.d("FileTransferService", "=== [UPLOAD] offset=$offset")
        try {
            val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
            val offsetBytes = longToBytes(offset)
            val payload = intToBytes(fileNameBytes.size) + fileNameBytes + offsetBytes + data
            android.util.Log.d("FileTransferService", "=== [UPLOAD] payload constructed: fileNameBytes=${fileNameBytes.size}, payloadSize=${payload.size}")

            android.util.Log.d("FileTransferService", "=== [UPLOAD] calling writePacket with CMD_UPLOAD_REQUEST=${FileTransferProtocol.CMD_UPLOAD_REQUEST}")
            val writeResult = client.writePacket(FileTransferProtocol.CMD_UPLOAD_REQUEST, payload)
            android.util.Log.d("FileTransferService", "=== [UPLOAD] writePacket completed, result=$writeResult")
            if (!writeResult) {
                android.util.Log.e("FileTransferService", "=== [UPLOAD] writePacket FAILED, returning failure")
                return@withContext Result.failure(Exception("发送数据失败"))
            }

            android.util.Log.d("FileTransferService", "=== [UPLOAD] waiting for server response...")
            val response = client.readPacket()
            android.util.Log.d("FileTransferService", "=== [UPLOAD] readPacket returned: ${if (response == null) "null" else "size=${response.size}"}")
            if (response == null) {
                android.util.Log.e("FileTransferService", "=== [UPLOAD] response is null, connection closed")
                return@withContext Result.failure(Exception("连接断开"))
            }
            val command = response[0]
            android.util.Log.d("FileTransferService", "=== [UPLOAD] response command byte=$command (expected CMD_SUCCESS=${FileTransferProtocol.CMD_SUCCESS}, CMD_ERROR=${FileTransferProtocol.CMD_ERROR})")

            if (command == FileTransferProtocol.CMD_ERROR) {
                val errorData = response.copyOfRange(5, response.size)
                val errorMsg = String(errorData, Charsets.UTF_8)
                android.util.Log.e("FileTransferService", "=== [UPLOAD] got CMD_ERROR, message=$errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }

            if (command == FileTransferProtocol.CMD_SUCCESS) {
                val elapsed = System.currentTimeMillis() - startTime
                android.util.Log.d("FileTransferService", "=== [UPLOAD] SUCCESS! elapsed=${elapsed}ms")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("FileTransferService", "=== [UPLOAD] EXCEPTION after ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    // Upload file in chunks to avoid OOM for large files
    // Uses streaming upload: header + raw chunks + completion signal
    // Returns total bytes uploaded
    suspend fun uploadFileChunked(context: Context, uri: android.net.Uri, fileName: String, chunkSize: Int = 32768, onProgress: ((Long, Long) -> Unit)? = null): Result<Long> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] START ===")
        android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] fileName=$fileName")
        android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] chunkSize=$chunkSize")

        // Get file size for progress calculation
        val fileSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> pfd.statSize } ?: -1L
        } catch (e: Exception) {
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] could not get file size: ${e.message}")
            -1L
        }
        android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] fileSize=$fileSize")
        try {
            val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
            val offsetBytes = longToBytes(0L)
            // Header format: filename length (4 bytes) + filename + offset (8 bytes)
            // No initial content - server will wait for streaming chunks
            val headerPayload = intToBytes(fileNameBytes.size) + fileNameBytes + offsetBytes

            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] sending header via writePacket, headerPayload size=${headerPayload.size}")
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] client type=${client.javaClass.simpleName}, isConnected=${client.isConnected}")
            var writeResult = client.writePacket(FileTransferProtocol.CMD_UPLOAD_REQUEST, headerPayload)
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] header writeResult=$writeResult")
            if (!writeResult) {
                android.util.Log.e("FileTransferService", "=== [UPLOAD CHUNKED] header write FAILED")
                return@withContext Result.failure(Exception("发送文件头失败"))
            }
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] header sent successfully")

            // Stream file content in chunks (with protocol header per chunk for streaming mode)
            var totalSent = 0L
            var chunkCount = 0
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // Always copy the data - buffer is reused and must not be modified during send
                    val chunkData = if (bytesRead == chunkSize) buffer.copyOf(chunkSize) else buffer.copyOf(bytesRead)
                    chunkCount++

                    // Send chunk with protocol header (CMD_UPLOAD_CHUNK)
                    val chunkPacket = FileTransferProtocol.createPacket(FileTransferProtocol.CMD_UPLOAD_CHUNK, chunkData)
                    writeResult = client.writeRaw(chunkPacket)
                    if (!writeResult) {
                        android.util.Log.e("FileTransferService", "=== [UPLOAD CHUNKED] chunk $chunkCount write FAILED at totalSent=$totalSent")
                        return@withContext Result.failure(Exception("发送数据失败"))
                    }
                    totalSent += bytesRead
                    // Report progress
                    if (chunkCount % 10 == 0) {
                        onProgress?.invoke(totalSent, fileSize)
                    }
                    if (chunkCount % 50 == 0) {
                        android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] chunk $chunkCount sent, totalSent=$totalSent")
                    }
                }
                android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] all chunks sent, totalChunks=$chunkCount, totalSent=$totalSent")
                onProgress?.invoke(totalSent, fileSize)
            } ?: run {
                android.util.Log.e("FileTransferService", "=== [UPLOAD CHUNKED] cannot open input stream")
                return@withContext Result.failure(Exception("无法打开文件"))
            }

            // Send completion signal
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] sending CMD_TRANSFER_COMPLETE signal")
            val completePacket = FileTransferProtocol.createPacket(FileTransferProtocol.CMD_TRANSFER_COMPLETE)
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] completePacket size=${completePacket.size}")
            client.writeRaw(completePacket)
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] transfer complete signal sent")

            // Wait for server response
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] waiting for server response...")
            val response = client.readPacket()
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] response received: ${if (response == null) "null" else "size=${response.size}"}")
            if (response == null) {
                android.util.Log.e("FileTransferService", "=== [UPLOAD CHUNKED] response is null, connection closed")
                return@withContext Result.failure(Exception("连接断开"))
            }
            val command = response[0]
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] response command=$command")
            if (command == FileTransferProtocol.CMD_ERROR) {
                val errorData = response.copyOfRange(5, response.size)
                val errorMsg = String(errorData, Charsets.UTF_8)
                android.util.Log.e("FileTransferService", "=== [UPLOAD CHUNKED] got CMD_ERROR: $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }

            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.d("FileTransferService", "=== [UPLOAD CHUNKED] SUCCESS! totalSent=$totalSent, elapsed=${elapsed}ms, speed=${if(elapsed>0) totalSent*1000/elapsed else 0} bytes/s")
            Result.success(totalSent)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("FileTransferService", "=== [UPLOAD CHUNKED] EXCEPTION after ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    // Delete file
    suspend fun deleteFile(fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)

            client.writePacket(FileTransferProtocol.CMD_DELETE_REQUEST, fileNameBytes)

            val response = client.readPacket() ?: return@withContext Result.failure(Exception("连接断开"))
            val command = response[0]

            if (command == FileTransferProtocol.CMD_ERROR) {
                val errorData = response.copyOfRange(5, response.size)
                return@withContext Result.failure(Exception(String(errorData, Charsets.UTF_8)))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseFileListJson(json: String): FileListResult {
        val jsonObj = JSONObject(json)
        val currentPath = jsonObj.optString("CurrentPath", "")
        val itemsArray = jsonObj.getJSONArray("Items")
        val items = mutableListOf<FileItem>()
        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.getJSONObject(i)
            items.add(FileItem(
                name = obj.getString("Name"),
                size = obj.getLong("Size"),
                modifiedTime = obj.optString("ModifiedTime", ""),
                isDirectory = obj.getBoolean("IsDirectory"),
                isParentDirectory = obj.optBoolean("IsParentDirectory", false)
            ))
        }
        return FileListResult(currentPath, items)
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value shr 56).toByte(),
            (value shr 48).toByte(),
            (value shr 40).toByte(),
            (value shr 32).toByte(),
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
}
