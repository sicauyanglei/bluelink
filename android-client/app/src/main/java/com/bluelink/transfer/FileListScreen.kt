package com.bluelink.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.InputStream

// Helper functions (accessible from both FileListScreen and FileItemCard)
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val sizes = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var order = 0
    while (size >= 1024 && order < sizes.size - 1) {
        order++
        size /= 1024
    }
    return "%.2f %s".format(size, sizes[order])
}

fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "0 B/s"
    val sizes = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var speed = bytesPerSecond.toDouble()
    var order = 0
    while (speed >= 1024 && order < sizes.size - 1) {
        order++
        speed /= 1024
    }
    return "%.2f %s".format(speed, sizes[order])
}

// 通过MediaStore查找已下载文件的ContentUri (Android 10+)
fun findDownloadedFileUri(context: Context, fileName: String): Uri? {
    val contentResolver = context.contentResolver
    val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
    // 匹配文件名和Downloads目录
    val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(fileName, "Download/")
    val sortOrder = "${android.provider.MediaStore.Downloads.DATE_ADDED} DESC"

    contentResolver.query(
        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
            val id = cursor.getLong(idColumn)
            return Uri.withAppendedPath(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
        }
    }
    return null
}

// 查找APK文件的URI，支持带时间戳的文件名
fun findApkFileUri(context: Context, originalFileName: String): Uri? {
    val contentResolver = context.contentResolver

    // 首先尝试精确匹配
    var uri = findDownloadedFileUri(context, originalFileName)
    if (uri != null) {
        android.util.Log.d("FileListScreen", ">>> findApkFileUri: exact match found for $originalFileName")
        return uri
    }

    // 精确匹配没找到，尝试模糊匹配（查找以原始文件名去掉扩展名开头的APK）
    val baseName = originalFileName.substringBeforeLast(".")
    val projection = arrayOf(
        android.provider.MediaStore.Downloads._ID,
        android.provider.MediaStore.Downloads.DISPLAY_NAME
    )
    // 只用文件名匹配，不过滤路径
    val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
    val selectionArgs = arrayOf("$baseName%.apk")
    val sortOrder = "${android.provider.MediaStore.Downloads.DATE_ADDED} DESC"

    android.util.Log.d("FileListScreen", ">>> findApkFileUri: searching with baseName=$baseName")
    contentResolver.query(
        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndex(android.provider.MediaStore.Downloads.DISPLAY_NAME)
            val id = cursor.getLong(idColumn)
            val foundName = if (nameColumn >= 0) cursor.getString(nameColumn) else "unknown"
            android.util.Log.d("FileListScreen", ">>> findApkFileUri: fuzzy match found $foundName")
            return Uri.withAppendedPath(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
        }
    }

    android.util.Log.d("FileListScreen", ">>> findApkFileUri: no match found for $originalFileName")
    return null
}

// 删除下载的文件，支持带时间戳的文件名
fun deleteDownloadedFile(context: Context, fileName: String): Boolean {
    val contentResolver = context.contentResolver

    // 首先尝试精确匹配
    val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
    val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(fileName, "Download/")

    android.util.Log.d("FileListScreen", ">>> deleteDownloadedFile: trying exact match for $fileName")
    contentResolver.query(
        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
            val id = cursor.getLong(idColumn)
            val uri = android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            val deleted = contentResolver.delete(uri, null, null) > 0
            android.util.Log.d("FileListScreen", ">>> deleteDownloadedFile: exact match deleted=$deleted")
            if (deleted) return true
        }
    }

    // 精确匹配没找到，尝试模糊匹配（用于带时间戳的文件名）
    val baseName = fileName.substringBeforeLast(".")
    val ext = fileName.substringAfterLast(".", "")
    val likePattern = if (ext.isNotEmpty()) "$baseName%.$ext" else "$baseName%"

    android.util.Log.d("FileListScreen", ">>> deleteDownloadedFile: trying fuzzy match for $likePattern")
    val fuzzySelection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?"
    val fuzzyArgs = arrayOf(likePattern, "Download/")

    contentResolver.query(
        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        fuzzySelection,
        fuzzyArgs,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndex(android.provider.MediaStore.Downloads.DISPLAY_NAME)
            val id = cursor.getLong(idColumn)
            val foundName = if (nameColumn >= 0) cursor.getString(nameColumn) else "unknown"
            val uri = android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            val deleted = contentResolver.delete(uri, null, null) > 0
            android.util.Log.d("FileListScreen", ">>> deleteDownloadedFile: fuzzy match deleted=$deleted for $foundName")
            return deleted
        }
    }

    android.util.Log.d("FileListScreen", ">>> deleteDownloadedFile: no match found for $fileName")
    return false
}

fun saveToDownloads(context: Context, fileName: String, data: ByteArray): Boolean {
    return try {
        // Android 10+ 使用MediaStore API
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }

        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return false

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(data)
            outputStream.flush()
        }

        // 标记下载完成
        val updateValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, updateValues, null, null)

        android.util.Log.d("FileListScreen", "File saved via MediaStore: $fileName, size=${data.size}")
        true
    } catch (e: Exception) {
        android.util.Log.e("FileListScreen", "Failed to save file $fileName: ${e.message}")
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    client: TransferClient? = null,
    transferService: FileTransferService? = null,
    triggerRefresh: Int = 0,
    pathChangedTrigger: Int = 0,
    connectionType: String? = null,  // "蓝牙" or "TCP"
    onConnectionLost: (() -> Unit)? = null
) {
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var currentPath by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<String?>(null) }
    var lastDownloadedFile by remember { mutableStateOf<String?>(null) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadFileName by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableStateOf(0f) }
    var uploadSpeed by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Transfer mode: "download" or "upload"
    var transferMode by remember { mutableStateOf("download") }

    // LazyColumn滚动状态
    val listState = rememberLazyListState()

    // 滚动到顶部的触发器 - 只有明确操作时才触发
    var scrollToTopTrigger by remember { mutableIntStateOf(0) }

    // 监听滚动触发器，只有明确操作时才滚动到顶部
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0 && files.isNotEmpty()) {
            // 只有用户明确操作时才滚动，不打断用户的手动滚动
            listState.animateScrollToItem(0)
        }
    }

    // Sort options
    var sortOption by remember { mutableStateOf("名称") }
    var sortAscending by remember { mutableStateOf(true) }
    val sortOptions = listOf("名称", "大小", "时间")

    // Quick directories - last 5 directories where files were downloaded (persisted)
    var quickDirs by remember { mutableStateOf<List<String>>(emptyList()) }

    // Delete APK after install option (persisted)
    var deleteAfterInstall by remember { mutableStateOf(false) }

    // Pending delete file after install completes (when user returns from install screen)
    var pendingDeleteFile by remember { mutableStateOf<String?>(null) }

    // Load persisted preferences on composition
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
        val savedDirs = prefs.getString("quick_dirs", "") ?: ""
        if (savedDirs.isNotEmpty()) {
            quickDirs = savedDirs.split(",").filter { it.isNotEmpty() }
        }
        deleteAfterInstall = prefs.getBoolean("delete_after_install", false)
    }

    // File picker for upload
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            // Extract filename from URI
            val fileName = it.lastPathSegment?.substringAfterLast("/") ?: "unknown_file"
            // 替换文件名中的非法字符（冒号等Windows不支持的字符）
            val safeFileName = fileName.replace(":", "_").replace("*", "_").replace("?", "_").replace("\"", "_").replace("<", "_").replace(">", "_").replace("|", "_")
            uploadFileName = safeFileName
            showUploadDialog = true
        }
    }

    // Auto-install APK when download completes
    fun installApk(context: Context, fileName: String) {
        val isApk = fileName.lowercase().endsWith(".apk")
        if (!isApk) return

        android.util.Log.d("FileListScreen", ">>> installApk: fileName=$fileName")

        // 通过MediaStore查找文件（支持带时间戳的文件名）
        val fileUri = findApkFileUri(context, fileName)
        android.util.Log.d("FileListScreen", ">>> installApk: fileUri=$fileUri")

        if (fileUri != null) {
            try {
                // 检查是否有安装未知来源应用的权限
                if (context.packageManager.canRequestPackageInstalls()) {
                    val mimeType = "application/vnd.android.package-archive"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mimeType)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                    android.util.Log.d("FileListScreen", ">>> installApk: started activity")
                } else {
                    // 请求安装未知来源的权限
                    android.util.Log.d("FileListScreen", ">>> installApk: requesting permission")
                    val settingsIntent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(settingsIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("FileListScreen", "Cannot install APK: ${e.message}", e)
                android.widget.Toast.makeText(context, "安装失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            // 尝试直接用文件名在Downloads目录查找
            android.util.Log.d("FileListScreen", ">>> installApk: MediaStore找不到，尝试直接扫描Downloads目录")
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                android.util.Log.d("FileListScreen", ">>> installApk: Downloads目录=${downloadsDir.absolutePath}")
                android.util.Log.d("FileListScreen", ">>> installApk: 目录存在=${downloadsDir.exists()}, 是目录=${downloadsDir.isDirectory}")

                val baseName = fileName.substringBeforeLast(".")
                android.util.Log.d("FileListScreen", ">>> installApk: 搜索文件名以 $baseName 开头的apk")

                // 查找匹配的文件
                val apkFiles = downloadsDir.listFiles { file ->
                    file.name.endsWith(".apk", ignoreCase = true) &&
                    (file.name == fileName || file.name.startsWith("$baseName"))
                }

                android.util.Log.d("FileListScreen", ">>> installApk: 找到 ${apkFiles?.size ?: 0} 个匹配文件")

                if (apkFiles != null && apkFiles.isNotEmpty()) {
                    // 取最新修改的文件
                    val latestFile = apkFiles.maxByOrNull { it.lastModified() }
                    if (latestFile != null) {
                        android.util.Log.d("FileListScreen", ">>> installApk: 找到文件: ${latestFile.absolutePath}, 大小: ${latestFile.length()}")
                        android.widget.Toast.makeText(context, "找到APK: ${latestFile.absolutePath}", android.widget.Toast.LENGTH_LONG).show()

                        val apkUri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            latestFile
                        )
                        android.util.Log.d("FileListScreen", ">>> installApk: FileProvider URI: $apkUri")

                        if (context.packageManager.canRequestPackageInstalls()) {
                            val mimeType = "application/vnd.android.package-archive"
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(apkUri, mimeType)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            android.util.Log.d("FileListScreen", ">>> installApk: 启动安装界面...")
                            context.startActivity(intent)
                        } else {
                            android.util.Log.d("FileListScreen", ">>> installApk: 需要安装权限")
                            val settingsIntent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(settingsIntent)
                        }
                        return
                    }
                }
                android.util.Log.d("FileListScreen", ">>> installApk: 目录下没有找到APK文件")
                android.widget.Toast.makeText(context, "找不到APK文件: $fileName", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("FileListScreen", ">>> installApk: 异常: ${e.message}", e)
                android.widget.Toast.makeText(context, "安装失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Sort files based on current sort option
    fun sortFiles(fileList: List<FileItem>): List<FileItem> {
        val directories = fileList.filter { it.isDirectory }
        val regularFiles = fileList.filter { !it.isDirectory }

        val sortedDirs = when (sortOption) {
            "名称" -> if (sortAscending) directories.sortedBy { it.name.lowercase() } else directories.sortedByDescending { it.name.lowercase() }
            "大小" -> if (sortAscending) directories.sortedBy { it.name.lowercase() } else directories.sortedByDescending { it.name.lowercase() }
            "时间" -> if (sortAscending) directories.sortedByDescending { it.modifiedTime } else directories.sortedBy { it.modifiedTime }
            else -> directories
        }

        val sortedFiles = when (sortOption) {
            "名称" -> if (sortAscending) regularFiles.sortedBy { it.name.lowercase() } else regularFiles.sortedByDescending { it.name.lowercase() }
            "大小" -> if (sortAscending) regularFiles.sortedBy { it.size } else regularFiles.sortedByDescending { it.size }
            "时间" -> if (sortAscending) regularFiles.sortedByDescending { it.modifiedTime } else regularFiles.sortedBy { it.modifiedTime }
            else -> regularFiles
        }

        return sortedDirs + sortedFiles
    }

    // Track download progress for resume
    var downloadProgress by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    // Check for pending delete file when composition happens (e.g., after returning from install screen)
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
        val pendingFile = prefs.getString("pending_delete_file", null)
        if (pendingFile != null && pendingFile.isNotEmpty()) {
            // Delete the file that was marked for deletion after install
            val deleted = deleteDownloadedFile(context, pendingFile)
            if (deleted) {
                // Clear download progress
                val ext = pendingFile.substringAfterLast(".")
                val nameWithoutTimestamp = pendingFile.substringBeforeLast("_")
                val originalFileName = "$nameWithoutTimestamp.$ext"
                val dlKeyToRemove = "$currentPath/$originalFileName"
                downloadProgress = downloadProgress - dlKeyToRemove
                lastDownloadedFile = null
            }
            prefs.edit().remove("pending_delete_file").apply()
            pendingDeleteFile = null
        }
    }

    // Download progress tracking
    var downloadingFileName by remember { mutableStateOf<String?>(null) }
    var downloadingProgress by remember { mutableStateOf(0f) }  // 0.0 to 1.0
    var downloadSpeed by remember { mutableStateOf("") }
    var lastDownloadUpdateTime by remember { mutableStateOf(0L) }
    var lastDownloadUpdateBytes by remember { mutableStateOf(0L) }

    // Multi-select download state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDownloadingBatch by remember { mutableStateOf(false) }
    var batchDownloadProgress by remember { mutableStateOf(0f) }
    var batchDownloadCurrent by remember { mutableStateOf("") }
    var batchTotalFiles by remember { mutableStateOf(0) }
    var batchCompletedFiles by remember { mutableStateOf(0) }

    // Load initial file list and refresh when pathChangedTrigger changes
    LaunchedEffect(transferService, triggerRefresh, pathChangedTrigger) {
        android.util.Log.d("FileListScreen", ">>> LaunchedEffect: triggerRefresh=$triggerRefresh, transferService=${transferService != null}, client=${client != null}, isConnected=${client?.isConnected}")
        if (transferService != null && client?.isConnected == true) {
            android.util.Log.d("FileListScreen", ">>> LaunchedEffect: 开始调用 getFileList()")
            isRefreshing = true
            val result = transferService.getFileList()
            android.util.Log.d("FileListScreen", ">>> LaunchedEffect: getFileList 返回, isSuccess=${result.isSuccess}")
            if (result.isSuccess) {
                val fileListResult = result.getOrNull()
                files = fileListResult?.items ?: emptyList()
                currentPath = fileListResult?.currentPath ?: ""
                            } else {
                files = emptyList()
                currentPath = ""
                val errorMsg = result.exceptionOrNull()?.message ?: ""
                statusMessage = "加载失败: $errorMsg"
                // Check if it's a connection error
                if (errorMsg.contains("连接") || errorMsg.contains("Connection") || errorMsg.contains("断开")) {
                    android.util.Log.d("FileListScreen", ">>> LaunchedEffect: 检测到连接断开，调用 onConnectionLost")
                    onConnectionLost?.invoke()
                }
            }
            android.util.Log.d("FileListScreen", ">>> LaunchedEffect: files.size=${files.size}")
            isRefreshing = false
        } else {
            android.util.Log.d("FileListScreen", ">>> LaunchedEffect: 跳过 - transferService=${transferService != null}, client?.isConnected=${client?.isConnected}")
        }
    }

    // Clear lastDownloadedFile when currentPath changes (switching directories)
    LaunchedEffect(currentPath) {
        lastDownloadedFile = null
        deleteAfterInstall = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with title and connection badge (fixed, not scrolling)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "远程文件",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "从PC下载文件到手机",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Connection type badge
                if (connectionType != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (connectionType == "蓝牙") Icons.Default.Bluetooth else Icons.Default.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = connectionType,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // Transfer mode radio buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = transferMode == "download",
                onClick = { transferMode = "download" }
            )
            Text(
                text = "下载",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 16.dp)
            )
            RadioButton(
                selected = transferMode == "upload",
                onClick = { transferMode = "upload" }
            )
            Text(
                text = "上传",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Content based on transfer mode
        if (transferMode == "upload") {
            // Upload screen
            UploadScreen(
                transferService = transferService,
                connectionType = connectionType
            )
        } else {
            // Download content - Path navigation bar (fixed, always visible)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button - 48dp touch target
                IconButton(
                    onClick = {
                        scope.launch {
                            statusMessage = "正在返回..."
                            val result = transferService?.goBack()
                            if (result != null && result.isSuccess) {
                                val fileListResult = result.getOrNull()
                                files = fileListResult?.items ?: emptyList()
                                currentPath = fileListResult?.currentPath ?: ""
                                                                scrollToTopTrigger++ // 返回上级时滚动到顶部
                            } else {
                                statusMessage = "返回失败: ${result?.exceptionOrNull()?.message}"
                            }
                        }
                    },
                    enabled = currentPath.isNotEmpty() && transferService != null,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回上级",
                        tint = if (currentPath.isNotEmpty())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }

                // Current path display
                Text(
                    text = if (currentPath.isEmpty()) "/" else "/$currentPath",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )

                // New folder button - 48dp touch target
                IconButton(
                    onClick = { showNewFolderDialog = true },
                    enabled = transferService != null,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = "新建文件夹",
                        tint = if (transferService != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }

                // Upload button - 48dp touch target
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = transferService != null && !isUploading,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "上传文件",
                        tint = if (transferService != null && !isUploading)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }

                // Sort button - 48dp touch target
                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        enabled = transferService != null,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = "排序",
                            tint = if (transferService != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        sortOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(option)
                                        if (sortOption == option) {
                                            Icon(
                                                imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    if (sortOption == option) {
                                        sortAscending = !sortAscending
                                    } else {
                                        sortOption = option
                                        sortAscending = true
                                    }
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }

                // Refresh button - 48dp touch target
                IconButton(
                    onClick = {
                        isRefreshing = true
                        scope.launch {
                            if (transferService != null) {
                                val result = transferService.getFileList()
                                if (result.isSuccess) {
                                    val fileListResult = result.getOrNull()
                                    files = fileListResult?.items ?: emptyList()
                                    currentPath = fileListResult?.currentPath ?: ""
                                    statusMessage = "刷新成功 (${files.size} 个项目)"
                                } else {
                                    statusMessage = "刷新失败: ${result.exceptionOrNull()?.message}"
                                }
                            }
                            isRefreshing = false
                        }
                    },
                    enabled = !isRefreshing && transferService != null,
                    modifier = Modifier.size(44.dp)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Multi-select button - 48dp touch target
                IconButton(
                    onClick = {
                        if (isMultiSelectMode) {
                            isMultiSelectMode = false
                            selectedFiles = emptySet()
                        } else {
                            isMultiSelectMode = true
                            selectedFiles = emptySet()
                        }
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isMultiSelectMode) Icons.Default.Close else Icons.Default.Checklist,
                        contentDescription = "多选",
                        tint = if (isMultiSelectMode)
                            MaterialTheme.colorScheme.error
                        else if (selectedFiles.isNotEmpty())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Quick directories - horizontal scrolling with swipe to delete
        if (quickDirs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                quickDirs.forEachIndexed { index, dir ->
                    // Extract display path: root name / last directory name
                    // Handle both Unix (/) and Windows (\) path separators
                    val parts = dir.replace("\\", "/").split("/").filter { it.isNotEmpty() }
                    val displayName = if (parts.size >= 2) {
                        "${parts.first()}/${parts.last()}"
                    } else {
                        parts.firstOrNull() ?: dir
                    }
                    // Navigate to the last folder name (navigateToFolder expects single folder name)
                    val folderName = parts.lastOrNull() ?: dir

                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                // Swipe left to delete directly
                                val newList = quickDirs.toMutableList().apply { removeAt(index) }
                                quickDirs = newList
                                val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
                                prefs.edit().putString("quick_dirs", newList.joinToString(",")).apply()
                            }
                            false
                        },
                        positionalThreshold = { totalDistance -> totalDistance * 0.3f }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            )
                        },
                        content = {
                            Surface(
                                onClick = {
                                    scope.launch {
                                        // Navigate step by step from root to target directory
                                        val parts = dir.replace("\\", "/").split("/").filter { it.isNotEmpty() }
                                        statusMessage = dir.replace("/", "\\")

                                        // First go back to root
                                        while (currentPath.isNotEmpty()) {
                                            val backResult = transferService?.goBack()
                                            if (backResult != null && backResult.isSuccess) {
                                                val fileListResult = backResult.getOrNull()
                                                files = fileListResult?.items ?: emptyList()
                                                currentPath = fileListResult?.currentPath ?: ""
                                            } else {
                                                break
                                            }
                                        }

                                        // Then navigate into each folder
                                        for (folderName in parts) {
                                            val result = transferService?.navigateToFolder(folderName)
                                            if (result != null && result.isSuccess) {
                                                val fileListResult = result.getOrNull()
                                                files = fileListResult?.items ?: emptyList()
                                                currentPath = fileListResult?.currentPath ?: ""
                                            } else {
                                                statusMessage = "进入失败: ${result?.exceptionOrNull()?.message}"
                                                break
                                            }
                                        }
                                        scrollToTopTrigger++
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(3.dp)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
        }

        // Batch download bar (shown when files are selected)
        if (isMultiSelectMode && selectedFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已选择 ${selectedFiles.size} 个文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row {
                        TextButton(
                            onClick = {
                                isMultiSelectMode = false
                                selectedFiles = emptySet()
                            }
                        ) {
                            Text("取消", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                batchTotalFiles = selectedFiles.size
                                batchCompletedFiles = 0
                                isDownloadingBatch = true
                                isMultiSelectMode = false
                                scope.launch {
                                    val selectedList = selectedFiles.toList()
                                    selectedFiles = emptySet()
                                    for (fileName in selectedList) {
                                        batchDownloadCurrent = fileName
                                        val file = files.find { it.name == fileName }
                                        if (file != null && !file.isDirectory) {
                                            statusMessage = "正在下载 ($batchCompletedFiles/$batchTotalFiles): $fileName"
                                            val dlKey = "$currentPath/$fileName"
                                            val localProgress = downloadProgress[dlKey] ?: 0L
                                            // 使用流式下载直接写入文件
                                            val result = transferService?.downloadFileToFile(context, fileName, localProgress)
                                            if (result?.isSuccess == true) {
                                                val downloadResult = result.getOrNull()
                                                val totalBytes = downloadResult?.bytesDownloaded ?: 0L
                                                // 使用实际保存的文件名（带时间戳）
                                                lastDownloadedFile = downloadResult?.actualFileName ?: fileName
                                                downloadProgress = downloadProgress + (dlKey to (localProgress + totalBytes))
                                                batchCompletedFiles++
                                                // Record quick directory after first successful batch download
                                                if (batchCompletedFiles == 1 && currentPath.isNotEmpty() && currentPath !in quickDirs) {
                                                    quickDirs = (listOf(currentPath) + quickDirs).take(5)
                                                    val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
                                                    prefs.edit().putString("quick_dirs", quickDirs.joinToString(",")).apply()
                                                }
                                            }
                                        }
                                    }
                                    isDownloadingBatch = false
                                    statusMessage = "批量下载完成 ($batchCompletedFiles/$batchTotalFiles)"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载全部")
                        }
                    }
                }
            }
        }

        // Batch download progress (shown during batch download)
        if (isDownloadingBatch) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "批量下载中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$batchCompletedFiles / $batchTotalFiles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = if (batchTotalFiles > 0) batchCompletedFiles.toFloat() / batchTotalFiles else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "正在下载: $batchDownloadCurrent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Loading Progress - 只在刷新列表时显示
        AnimatedVisibility(
            visible = isRefreshing && downloadingFileName == null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status Message
        AnimatedVisibility(
            visible = statusMessage.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (statusMessage.contains("完成") || statusMessage.contains("成功"))
                            MaterialTheme.colorScheme.primaryContainer
                        else if (statusMessage.contains("失败"))
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Show save path after download complete - clickable to open file
                AnimatedVisibility(
                    visible = lastDownloadedFile != null && statusMessage.contains("完成"),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    var showDelete by remember { mutableStateOf(false) }
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                // 向左滑，显示删除按钮（但不自动删除）
                                showDelete = true
                            } else if (dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                                // 向右滑，隐藏删除按钮
                                showDelete = false
                            }
                            false // 不自动删除
                        },
                        positionalThreshold = { totalDistance -> totalDistance * 0.3f }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = true,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
                                // 滑动时的背景（仅颜色变化，无文字）
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                                                MaterialTheme.colorScheme.errorContainer
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                ) { }
                            },
                            content = {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (!showDelete) {
                                                // Open the downloaded file
                                                val fileName = lastDownloadedFile ?: return@clickable
                                                val isApk = fileName.lowercase().endsWith(".apk")

                                                // 通过MediaStore查找文件（支持带时间戳的文件名）
                                                val fileUri = if (isApk) findApkFileUri(context, fileName) else findDownloadedFileUri(context, fileName)
                                                if (fileUri != null) {
                                                    val mimeType = if (isApk) "application/vnd.android.package-archive" else "*/*"

                                                    if (isApk) {
                                                        // APK安装
                                                        if (context.packageManager.canRequestPackageInstalls()) {
                                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                                setDataAndType(fileUri, mimeType)
                                                                flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                            }
                                                            try {
                                                                context.startActivity(intent)
                                                                // If "delete after install" is enabled, mark file for deletion after install completes
                                                                if (deleteAfterInstall) {
                                                                    pendingDeleteFile = fileName
                                                                    val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
                                                                    prefs.edit().putString("pending_delete_file", fileName).apply()
                                                                    // Don't delete immediately, will be deleted when user returns
                                                                }
                                                            } catch (e: Exception) {
                                                                android.util.Log.e("FileListScreen", "Cannot install APK: ${e.message}")
                                                            }
                                                        } else {
                                                            // 需要安装权限
                                                            val settingsIntent = android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                                                data = Uri.parse("package:${context.packageName}")
                                                            }
                                                            context.startActivity(settingsIntent)
                                                        }
                                                    } else {
                                                        // 其他文件直接打开
                                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                            setDataAndType(fileUri, mimeType)
                                                            flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        }
                                                        try {
                                                            context.startActivity(intent)
                                                        } catch (e: Exception) {
                                                            android.util.Log.e("FileListScreen", "Cannot open file: ${e.message}")
                                                        }
                                                    }
                                                } else {
                                                    android.util.Log.e("FileListScreen", "File not found in MediaStore: $fileName")
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = lastDownloadedFile ?: "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            // Delete after install option (only for APK files)
                                            if (lastDownloadedFile?.lowercase()?.endsWith(".apk") == true) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                ) {
                                                    Switch(
                                                        checked = deleteAfterInstall,
                                                        onCheckedChange = {
                                                            deleteAfterInstall = it
                                                            val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
                                                            prefs.edit().putBoolean("delete_after_install", it).apply()
                                                        },
                                                        modifier = Modifier.height(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "安装后删除",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        if (showDelete) {
                                            // 显示删除按钮
                                            IconButton(
                                                onClick = {
                                                    val fileName = lastDownloadedFile
                                                    if (fileName != null) {
                                                        scope.launch {
                                                            val deleted = deleteDownloadedFile(context, fileName)
                                                            if (deleted) {
                                                                // 找到原始文件名：去掉时间戳后缀（_120131），保留扩展名
                                                                val ext = fileName.substringAfterLast(".")
                                                                val nameWithoutTimestamp = fileName.substringBeforeLast("_")
                                                                val originalFileName = "$nameWithoutTimestamp.$ext"
                                                                val dlKeyToRemove = "$currentPath/$originalFileName"
                                                                downloadProgress = downloadProgress - dlKeyToRemove
                                                                lastDownloadedFile = null
                                                            } else {
                                                                statusMessage = "删除失败"
                                                            }
                                                            showDelete = false
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "删除",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = if (lastDownloadedFile?.lowercase()?.endsWith(".apk") == true)
                                                    Icons.Default.Android else Icons.Default.OpenInNew,
                                                contentDescription = "打开",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // File List
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    // Empty folder icon with background circle
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (client?.isConnected == true) "当前目录为空" else "请先连接PC服务端",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (client?.isConnected == true) "点击上方按钮刷新列表" else "通过蓝牙或TCP连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            // File count info
            // Apply sorting to files
            val sortedFiles = remember(files, sortOption, sortAscending) { sortFiles(files) }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f)
            ) {
                // Show directories first
                items(sortedFiles.filter { it.isDirectory }) { item ->
                    DirectoryItemCard(
                        item = item,
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = item.name in selectedFiles,
                        onSelectToggle = {
                            if (isMultiSelectMode) {
                                selectedFiles = if (item.name in selectedFiles) {
                                    selectedFiles - item.name
                                } else {
                                    selectedFiles + item.name
                                }
                            }
                        },
                        onClick = {
                            if (isMultiSelectMode) {
                                // Toggle selection in multi-select mode
                                selectedFiles = if (item.name in selectedFiles) {
                                    selectedFiles - item.name
                                } else {
                                    selectedFiles + item.name
                                }
                            } else {
                                // Navigate to folder
                                scope.launch {
                                    val targetPath = if (currentPath.isNotEmpty()) "$currentPath\\${item.name}" else item.name
                                    statusMessage = targetPath
                                    val result = transferService?.navigateToFolder(item.name)
                                    if (result != null && result.isSuccess) {
                                        val fileListResult = result.getOrNull()
                                        files = fileListResult?.items ?: emptyList()
                                        currentPath = fileListResult?.currentPath ?: ""
                                                                                scrollToTopTrigger++ // 进入文件夹时滚动到顶部
                                    } else {
                                        statusMessage = "进入失败: ${result?.exceptionOrNull()?.message}"
                                    }
                                }
                            }
                        }
                    )
                }

                // Then show files
                items(sortedFiles.filter { !it.isDirectory }) { file ->
                    val dlKey = "$currentPath/${file.name}"
                    val localProgress = downloadProgress[dlKey] ?: 0L
                    val isDownloading = downloadingFileName == file.name
                    // File is complete if: (1) size > 0 and fully downloaded, OR (2) size = 0 but was downloaded successfully
                    val isComplete = if (file.size > 0) {
                        localProgress >= file.size
                    } else {
                        // Compare base names (ignoring timestamp suffix like _120131)
                        val baseName = file.name.substringBeforeLast(".")
                        val ext = file.name.substringAfterLast(".", "")
                        lastDownloadedFile?.startsWith(baseName) == true && lastDownloadedFile?.endsWith(".$ext") == true
                    }
                    val progress = if (file.size > 0) localProgress.toFloat() / file.size else 0f

                    FileItemCard(
                        file = file,
                        localProgress = localProgress,
                        progress = if (isDownloading) downloadingProgress else progress,
                        isDownloading = isDownloading,
                        isComplete = isComplete,
                        downloadSpeed = if (isDownloading) downloadSpeed else "",
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = file.name in selectedFiles,
                        onSelectToggle = {
                            if (isMultiSelectMode) {
                                selectedFiles = if (file.name in selectedFiles) {
                                    selectedFiles - file.name
                                } else {
                                    selectedFiles + file.name
                                }
                            }
                        },
                        onDownload = {
                            if (isMultiSelectMode) {
                                // Toggle selection in multi-select mode
                                selectedFiles = if (file.name in selectedFiles) {
                                    selectedFiles - file.name
                                } else {
                                    selectedFiles + file.name
                                }
                            } else {
                                scope.launch {
                                    downloadingFileName = file.name
                                    downloadingProgress = 0f
                                    downloadSpeed = ""
                                    lastDownloadUpdateTime = System.currentTimeMillis()
                                    lastDownloadUpdateBytes = localProgress
                                    statusMessage = "正在下载: ${file.name}"
                                    if (transferService != null) {
                                        val fileSize = file.size
                                        val startTime = System.currentTimeMillis()
                                        android.util.Log.d("FileListScreen", ">>> 开始下载: ${file.name}, size=$fileSize")

                                        // 使用流式下载直接写入文件，避免大文件占用过多内存
                                        val result = transferService.downloadFileToFile(
                                            context = context,
                                            fileName = file.name,
                                            offset = localProgress,
                                            onProgress = { received ->
                                                // Use scope.launch to trigger recomposition
                                                scope.launch {
                                                    android.util.Log.d("FileListScreen", ">>> 进度回调: received=$received, fileSize=$fileSize")
                                                    if (fileSize > 0) {
                                                        val progress = (received.toFloat() / fileSize).coerceAtMost(1f)
                                                        downloadingProgress = progress

                                                        // Calculate real-time speed
                                                        val currentTime = System.currentTimeMillis()
                                                        val timeDiff = currentTime - lastDownloadUpdateTime
                                                        if (timeDiff >= 500) { // Update speed every 500ms
                                                            val bytesDiff = received - lastDownloadUpdateBytes
                                                            val speed = if (timeDiff > 0) (bytesDiff * 1000L / timeDiff) else 0L
                                                            downloadSpeed = formatSpeed(speed)
                                                            lastDownloadUpdateTime = currentTime
                                                            lastDownloadUpdateBytes = received
                                                        }
                                                        android.util.Log.d("FileListScreen", ">>> 进度: $progress, 速度: $downloadSpeed")
                                                    } else {
                                                        // If fileSize is 0, we can't show progress
                                                        downloadingProgress = 0f
                                                    }
                                                }
                                            }
                                        )
                                        android.util.Log.d("FileListScreen", ">>> 下载完成, result.isSuccess=${result.isSuccess}")
                                        val endTime = System.currentTimeMillis()
                                        if (result.isSuccess) {
                                            val downloadResult = result.getOrNull()
                                            val totalBytes = downloadResult?.bytesDownloaded ?: 0L
                                            // Calculate download speed
                                            val elapsedMs = endTime - startTime
                                            val speed = if (elapsedMs > 0) (totalBytes * 1000L / elapsedMs) else 0L
                                            downloadSpeed = formatSpeed(speed)

                                            // Update progress and mark as complete - 使用实际保存的文件名（带时间戳）
                                            val dlKey = "$currentPath/${file.name}"
                                            downloadProgress = downloadProgress + (dlKey to (localProgress + totalBytes))
                                            lastDownloadedFile = downloadResult?.actualFileName ?: file.name
                                            statusMessage = "下载完成: ${downloadResult?.actualFileName ?: file.name}"
                                            // Default to delete APK after install
                                            val downloadedFileName = downloadResult?.actualFileName ?: file.name
                                            val isApkFile = downloadedFileName.lowercase().endsWith(".apk")
                                            deleteAfterInstall = isApkFile
                                            if (isApkFile) {
                                                val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
                                                prefs.edit().putBoolean("delete_after_install", true).apply()
                                            }
                                            // Record quick directory (only if downloading a file, not resuming)
                                            if (localProgress == 0L && currentPath.isNotEmpty()) {
                                                if (currentPath !in quickDirs) {
                                                    quickDirs = (listOf(currentPath) + quickDirs).take(5)
                                                    // Persist quick directories
                                                    val prefs = context.getSharedPreferences("file_transfer_prefs", android.content.Context.MODE_PRIVATE)
                                                    prefs.edit().putString("quick_dirs", quickDirs.joinToString(",")).apply()
                                                }
                                            }
                                        } else {
                                            val errorMsg = result.exceptionOrNull()?.message ?: ""
                                            statusMessage = "下载失败: $errorMsg"
                                            // Check if it's a connection error
                                            if (errorMsg.contains("连接") || errorMsg.contains("Connection") ||
                                                errorMsg.contains("timeout") || errorMsg.contains("断开")) {
                                                android.util.Log.d("FileListScreen", ">>> 连接丢失，触发重连")
                                                onConnectionLost?.invoke()
                                            }
                                        }
                                    }
                                    downloadingFileName = null
                                    downloadingProgress = 0f
                                    downloadSpeed = ""
                                }
                            }
                        },
                        onDelete = if (transferService != null && !isMultiSelectMode) {
                            {
                                fileToDelete = file.name
                                showDeleteDialog = true
                            }
                        } else null
                    )
                }
            }
        }
        } // End of else branch for download content
    }

    // New folder dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            scope.launch {
                                val result = transferService?.createFolder(folderName.trim())
                                if (result != null && result.isSuccess) {
                                    val fileListResult = result.getOrNull()
                                    files = fileListResult?.items ?: emptyList()
                                    currentPath = fileListResult?.currentPath ?: ""
                                    statusMessage = "已创建文件夹: $folderName"
                                } else {
                                    statusMessage = "创建失败: ${result?.exceptionOrNull()?.message}"
                                }
                                showNewFolderDialog = false
                                folderName = ""
                            }
                        }
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除本地文件") },
            text = {
                Text("确定要删除本地已下载的 \"$fileToDelete\" 吗？此操作无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = fileToDelete
                        scope.launch {
                            // Delete from local Downloads folder (not from PC)
                            val deleted = deleteDownloadedFile(context, name!!)
                            if (deleted) {
                                statusMessage = "已删除本地文件: $name"
                                // Clear lastDownloadedFile if it matches (comparing base names)
                                if (lastDownloadedFile != null) {
                                    val baseName = name.substringBeforeLast(".")
                                    val ext = name.substringAfterLast(".", "")
                                    if (lastDownloadedFile!!.startsWith(baseName) && lastDownloadedFile!!.endsWith(".$ext")) {
                                        lastDownloadedFile = null
                                    }
                                }
                                // Clear download progress for this file
                                val dlKey = "$currentPath/$name"
                                downloadProgress = downloadProgress - dlKey
                            } else {
                                statusMessage = "删除失败: 文件未找到"
                            }
                            showDeleteDialog = false
                            fileToDelete = null
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Upload dialog
    if (showUploadDialog && selectedFileUri != null) {
        // Get file size for display
        var fileSizeText by remember { mutableStateOf("") }
        LaunchedEffect(selectedFileUri) {
            try {
                context.contentResolver.openFileDescriptor(selectedFileUri!!, "r")?.use { pfd ->
                    fileSizeText = formatSize(pfd.statSize)
                }
            } catch (e: Exception) {
                fileSizeText = ""
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isUploading) { showUploadDialog = false; selectedFileUri = null } },
            title = { Text("上传文件") },
            text = {
                Column {
                    Text(
                        text = uploadFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (fileSizeText.isNotEmpty()) {
                        Text(
                            text = "大小: $fileSizeText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isUploading) {
                        LinearProgressIndicator(
                            progress = uploadProgress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${(uploadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            if (uploadSpeed.isNotEmpty()) {
                                Text(uploadSpeed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = selectedFileUri
                        if (uri != null && transferService != null) {
                            isUploading = true
                            statusMessage = "正在上传: $uploadFileName"
                            scope.launch {
                                try {
                                    // Use chunked upload to avoid OOM for large files
                                    val result = transferService.uploadFileChunked(
                                        context = context,
                                        uri = uri,
                                        fileName = uploadFileName,
                                        onProgress = { sent, total ->
                                            if (total > 0) {
                                                uploadProgress = sent.toFloat() / total.toFloat()
                                            }
                                        }
                                    )
                                    if (result.isSuccess) {
                                        statusMessage = "上传完成: $uploadFileName"
                                        val listResult = transferService.getFileList()
                                        if (listResult.isSuccess) {
                                            files = listResult.getOrNull()?.items ?: emptyList()
                                        }
                                    } else {
                                        statusMessage = "上传失败: ${result.exceptionOrNull()?.message}"
                                    }
                                } catch (e: Exception) {
                                    statusMessage = "上传失败: ${e.message}"
                                }
                                isUploading = false
                                showUploadDialog = false
                                selectedFileUri = null
                                uploadProgress = 0f
                                uploadSpeed = ""
                            }
                        }
                    },
                    enabled = !isUploading
                ) {
                    Text(if (isUploading) "上传中..." else "上传")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUploadDialog = false
                        selectedFileUri = null
                        uploadProgress = 0f
                    },
                    enabled = !isUploading
                ) {
                    Text("取消")
                }
            }
        )
    }
}

// Upload screen composable
@Composable
fun UploadScreen(
    transferService: FileTransferService?,
    connectionType: String?
) {
    var statusMessage by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadFileName by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableStateOf(0f) }
    var uploadSpeed by remember { mutableStateOf("") }
    var showUploadDialog by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            val fileName = it.lastPathSegment?.substringAfterLast("/") ?: "unknown_file"
            // 替换文件名中的非法字符（冒号等Windows不支持的字符）
            val safeFileName = fileName.replace(":", "_").replace("*", "_").replace("?", "_").replace("\"", "_").replace("<", "_").replace(">", "_").replace("|", "_")
            uploadFileName = safeFileName
            showUploadDialog = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "上传文件",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "从手机上选择文件上传到PC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Connection type badge
                if (connectionType != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (connectionType == "蓝牙") Icons.Default.Bluetooth else Icons.Default.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = connectionType,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Select file button
        Button(
            onClick = { filePickerLauncher.launch("*/*") },
            enabled = transferService != null && !isUploading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("选择文件上传")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status message
        AnimatedVisibility(
            visible = statusMessage.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (statusMessage.contains("完成") || statusMessage.contains("成功"))
                        MaterialTheme.colorScheme.primaryContainer
                    else if (statusMessage.contains("失败"))
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info text
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "上传说明:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "• 点击上方按钮选择要上传的文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "• 文件将上传到PC的上传目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "• 支持断点续传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Upload dialog
    if (showUploadDialog && selectedFileUri != null) {
        var fileSizeText by remember { mutableStateOf("") }
        LaunchedEffect(selectedFileUri) {
            try {
                context.contentResolver.openFileDescriptor(selectedFileUri!!, "r")?.use { pfd ->
                    fileSizeText = formatSize(pfd.statSize)
                }
            } catch (e: Exception) {
                fileSizeText = ""
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isUploading) { showUploadDialog = false; selectedFileUri = null } },
            title = { Text("上传文件") },
            text = {
                Column {
                    Text(
                        text = uploadFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (fileSizeText.isNotEmpty()) {
                        Text(
                            text = "大小: $fileSizeText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isUploading) {
                        LinearProgressIndicator(
                            progress = uploadProgress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${(uploadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            if (uploadSpeed.isNotEmpty()) {
                                Text(uploadSpeed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = selectedFileUri
                        if (uri != null && transferService != null) {
                            isUploading = true
                            statusMessage = "正在上传: $uploadFileName"
                            scope.launch {
                                try {
                                    val result = transferService.uploadFileChunked(
                                        context = context,
                                        uri = uri,
                                        fileName = uploadFileName,
                                        onProgress = { sent, total ->
                                            if (total > 0) {
                                                uploadProgress = sent.toFloat() / total.toFloat()
                                            }
                                        }
                                    )
                                    if (result.isSuccess) {
                                        statusMessage = "上传完成: $uploadFileName"
                                    } else {
                                        statusMessage = "上传失败: ${result.exceptionOrNull()?.message}"
                                    }
                                } catch (e: Exception) {
                                    statusMessage = "上传失败: ${e.message}"
                                }
                                isUploading = false
                                showUploadDialog = false
                                selectedFileUri = null
                                uploadProgress = 0f
                                uploadSpeed = ""
                            }
                        }
                    },
                    enabled = !isUploading
                ) {
                    Text(if (isUploading) "上传中..." else "上传")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUploadDialog = false
                        selectedFileUri = null
                        uploadProgress = 0f
                    },
                    enabled = !isUploading
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun DirectoryItemCard(
    item: FileItem,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            }
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when {
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Checkbox in multi-select mode
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectToggle?.invoke() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (item.isParentDirectory) ".." else item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (item.isParentDirectory) {
                        Text(
                            "返回上级目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isMultiSelectMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                    contentDescription = if (isSelected) "已选择" else "进入",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "进入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun FileItemCard(
    file: FileItem,
    localProgress: Long = 0,
    progress: Float = 0f,
    isDownloading: Boolean = false,
    isComplete: Boolean = false,
    downloadSpeed: String = "",
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (() -> Unit)? = null,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    FileItemCardContent(
        file = file,
        localProgress = localProgress,
        progress = progress,
        isDownloading = isDownloading,
        isComplete = isComplete,
        downloadSpeed = downloadSpeed,
        isMultiSelectMode = isMultiSelectMode,
        isSelected = isSelected,
        onSelectToggle = onSelectToggle,
        onDownload = onDownload,
        onDelete = onDelete
    )
}

@Composable
private fun FileItemCardContent(
    file: FileItem,
    localProgress: Long = 0,
    progress: Float = 0f,
    isDownloading: Boolean = false,
    isComplete: Boolean = false,
    downloadSpeed: String = "",
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (() -> Unit)? = null,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDownloading -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                isComplete -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox in multi-select mode
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectToggle?.invoke() },
                        modifier = Modifier.size(40.dp).padding(0.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // File icon with background
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            isComplete -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            isDownloading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when {
                                    isComplete -> Icons.Default.CheckCircle
                                    isDownloading -> Icons.Default.Downloading
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = when {
                                    isComplete || isDownloading -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        // File name with progress when downloading
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (isDownloading) {
                                // Progress bar background
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                                        .height(20.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                                )
                                            )
                                        )
                                )
                            }
                            Text(
                                text = if (isDownloading) "${(progress * 100).toInt()}% $file.name" else file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected || isDownloading) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                color = when {
                                    isSelected || isDownloading -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = file.formattedSize,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (file.modifiedTime.isNotEmpty()) {
                                Text(
                                    text = " • ${file.modifiedTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isDownloading && downloadSpeed.isNotEmpty()) {
                                Text(
                                    text = " • $downloadSpeed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (localProgress > 0 && !isComplete && !isDownloading) {
                                Text(
                                    text = " • ${formatSize(localProgress)} 已下载",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (isComplete) {
                                Text(
                                    text = " • 已下载",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Action buttons - 48dp touch targets
                if (isMultiSelectMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "已选择" else "未选择",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (isDownloading) {
                    // Circular progress with percentage
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                        CircularProgressIndicator(
                            progress = progress,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${(progress * 100).toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (isComplete) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已下载",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    FilledTonalIconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            imageVector = if (localProgress > 0) Icons.Default.Download else Icons.Default.Download,
                            contentDescription = if (localProgress > 0) "续传" else "下载",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
