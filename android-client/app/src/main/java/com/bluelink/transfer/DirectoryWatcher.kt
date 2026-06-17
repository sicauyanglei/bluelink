package com.bluelink.transfer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DirectoryWatcher(
    private val context: Context,
    private val transferService: FileTransferService,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val SCAN_INTERVAL_MS = 3000L // 3秒扫描一次
        private const val PREFS_NAME = "directory_watcher_prefs"
        private const val KEY_WATCH_DIRECTORY = "watch_directory"
        private const val KEY_WATCH_ENABLED = "watch_enabled"
        private const val KEY_LAST_PROCESSED_TIME = "last_processed_time"

        // 默认监视目录
        fun getDefaultWatchDirectory(context: Context): File {
            val dcimDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            )
            val shangDaRenDir = File(dcimDir, "ShangDaRen")
            if (!shangDaRenDir.exists()) {
                shangDaRenDir.mkdirs()
            }
            return shangDaRenDir
        }

        // 获取文件的 Uri (使用 FileProvider)
        fun getFileUri(context: Context, file: File, onLog: (String) -> Unit = {}): Uri {
            onLog("DirectoryWatcher: getFileUri: file=${file.absolutePath}, exists=${file.exists()}")

            return try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                onLog("DirectoryWatcher: getFileUri: FileProvider uri=$uri")
                uri
            } catch (e: Exception) {
                onLog("DirectoryWatcher: getFileUri: FileProvider failed, trying Uri.fromFile: ${e.message}")
                Uri.fromFile(file)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Use a background HandlerThread for scanning instead of the main looper.
    // Previously the scan runnable ran on the main thread, so listFiles() /
    // file.exists() / file.length() (blocking file I/O) caused ANRs.
    private val scanThread = HandlerThread("DirectoryWatcher").apply { start() }
    private val handler = Handler(scanThread.looper)
    private val isWatching = AtomicBoolean(false)
    private val processedFiles = ConcurrentHashMap.newKeySet<String>()
    private val uploadedFiles = ConcurrentHashMap.newKeySet<String>()
    
    // 回调接口
    interface WatcherListener {
        fun onWatchingStateChanged(isWatching: Boolean)
        fun onFileDetected(fileName: String)
        fun onFileUploaded(fileName: String)
        fun onFileUploadFailed(fileName: String, error: String)
    }
    
    private var listener: WatcherListener? = null
    
    fun setListener(listener: WatcherListener) {
        this.listener = listener
    }

    // 获取当前设置的监视目录
    fun getWatchDirectory(): File {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_WATCH_DIRECTORY, null)
        return if (savedPath != null) {
            val dir = File(savedPath)
            if (dir.exists() && dir.isDirectory) {
                dir
            } else {
                getDefaultWatchDirectory(context)
            }
        } else {
            getDefaultWatchDirectory(context)
        }
    }

    // 设置监视目录
    fun setWatchDirectory(directory: File) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WATCH_DIRECTORY, directory.absolutePath).apply()
    }

    // 获取是否启用监视
    fun isWatchEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WATCH_ENABLED, false)
    }

    // 设置是否启用监视
    fun setWatchEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_WATCH_ENABLED, enabled).apply()
        
        if (enabled && !isWatching.get()) {
            startWatching()
        } else if (!enabled && isWatching.get()) {
            stopWatching()
        }
    }

    // 获取上次处理时间
    private fun getLastProcessedTime(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_PROCESSED_TIME, System.currentTimeMillis())
    }

    // 设置上次处理时间
    private fun setLastProcessedTime(time: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_PROCESSED_TIME, time).apply()
    }

    // 开始监视
    fun startWatching() {
        if (isWatching.getAndSet(true)) {
            onLog("DirectoryWatcher: Already watching, not starting again")
            return
        }
        
        onLog("DirectoryWatcher: Starting directory watcher")
        listener?.onWatchingStateChanged(true)
        
        // 初始化已处理文件集合
        initProcessedFiles()
        
        // 启动扫描任务
        handler.post(scanRunnable)
    }

    // 停止监视
    fun stopWatching() {
        if (!isWatching.getAndSet(false)) {
            onLog("DirectoryWatcher: Not watching, not stopping")
            return
        }
        
        onLog("DirectoryWatcher: Stopping directory watcher")
        handler.removeCallbacks(scanRunnable)
        listener?.onWatchingStateChanged(false)
    }

    // 初始化已处理文件集合
    private fun initProcessedFiles() {
        val directory = getWatchDirectory()
        onLog("DirectoryWatcher: initProcessedFiles: directory=${directory.absolutePath}")
        onLog("DirectoryWatcher: Directory exists: ${directory.exists()}, isDirectory: ${directory.isDirectory}")
        
        if (!directory.exists() || !directory.isDirectory) {
            onLog("DirectoryWatcher: Directory does not exist or is not a directory: ${directory.absolutePath}")
            // 确保目录存在
            if (!directory.exists()) {
                try {
                    val success = directory.mkdirs()
                    onLog("DirectoryWatcher: Created directory: ${directory.absolutePath}, success=$success")
                } catch (e: Exception) {
                    onLog("DirectoryWatcher: Failed to create directory: ${e.message}")
                }
            }
            return
        }
        
        val files = getFilesFromDirectory(directory)
        onLog("DirectoryWatcher: Found ${files.size} items in directory")
        
        // 初始化时，把所有当前文件都标记为已处理，只处理之后新增的文件
        files.forEach { file ->
            if (file.isFile) {
                processedFiles.add(file.absolutePath)
                uploadedFiles.add(file.absolutePath)
                onLog("DirectoryWatcher: Marked as processed: ${file.name}")
            }
        }
    }

    // 扫描任务
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isWatching.get()) {
                return
            }
            
            scanDirectory()
            
            // 继续下次扫描
            if (isWatching.get()) {
                handler.postDelayed(this, SCAN_INTERVAL_MS)
            }
        }
    }

    // 使用 File API 从目录获取所有文件（不依赖 MediaStore）
    private fun getFilesFromDirectory(directory: File): List<File> {
        val allFiles = mutableListOf<File>()
        onLog("DirectoryWatcher: ========================================")
        onLog("DirectoryWatcher: getFilesFromDirectory starting for: ${directory.absolutePath}")
        onLog("DirectoryWatcher: Directory exists: ${directory.exists()}")
        onLog("DirectoryWatcher: Directory isDirectory: ${directory.isDirectory}")
        onLog("DirectoryWatcher: Directory canRead: ${directory.canRead()}")
        onLog("DirectoryWatcher: Directory canWrite: ${directory.canWrite()}")
        onLog("DirectoryWatcher: ========================================")
        
        try {
            onLog("DirectoryWatcher: [1] Using File API listFiles()...")
            val files = directory.listFiles()
            
            if (files == null) {
                onLog("DirectoryWatcher: WARNING: listFiles() returned NULL!")
                onLog("DirectoryWatcher: [2] Trying list() as fallback...")
                
                try {
                    val children = directory.list()
                    onLog("DirectoryWatcher: list() returned ${children?.size ?: 0} filenames")
                    
                    children?.forEachIndexed { index, name ->
                        onLog("DirectoryWatcher: Child $index: $name")
                        val file = File(directory, name)
                        onLog("DirectoryWatcher:   - exists: ${file.exists()}")
                        onLog("DirectoryWatcher:   - isFile: ${file.isFile}")
                        onLog("DirectoryWatcher:   - isDirectory: ${file.isDirectory}")
                        onLog("DirectoryWatcher:   - canRead: ${file.canRead()}")
                        
                        if (file.exists() && file.isFile) {
                            allFiles.add(file)
                            onLog("DirectoryWatcher: [FILE] Added: $name")
                        }
                    }
                } catch (e: Exception) {
                    onLog("DirectoryWatcher: list() failed: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                onLog("DirectoryWatcher: listFiles() returned ${files.size} items")
                onLog("DirectoryWatcher: Listing all items:")
                
                files.forEachIndexed { index, file ->
                    onLog("DirectoryWatcher: Item $index: ${file.name}")
                    onLog("DirectoryWatcher:   - path: ${file.absolutePath}")
                    onLog("DirectoryWatcher:   - exists: ${file.exists()}")
                    onLog("DirectoryWatcher:   - isFile: ${file.isFile}")
                    onLog("DirectoryWatcher:   - isDirectory: ${file.isDirectory}")
                    onLog("DirectoryWatcher:   - canRead: ${file.canRead()}")
                    onLog("DirectoryWatcher:   - size: ${file.length()}")
                    
                    if (file.isFile) {
                        allFiles.add(file)
                        onLog("DirectoryWatcher: [FILE] Added: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            onLog("DirectoryWatcher: ERROR getting files: ${e.message}")
            e.printStackTrace()
        }
        
        onLog("DirectoryWatcher: ========================================")
        onLog("DirectoryWatcher: Total files found: ${allFiles.size}")
        allFiles.forEachIndexed { index, file ->
            onLog("DirectoryWatcher: File $index: ${file.name} (${file.absolutePath})")
        }
        onLog("DirectoryWatcher: ========================================")
        return allFiles
    }

    // 扫描目录
    private fun scanDirectory() {
        val directory = getWatchDirectory()
        onLog("DirectoryWatcher: Scanning directory: ${directory.absolutePath}")
        onLog("DirectoryWatcher: Directory exists: ${directory.exists()}, isDirectory: ${directory.isDirectory}")
        
        if (!directory.exists() || !directory.isDirectory) {
            onLog("DirectoryWatcher: Directory does not exist or is not a directory: ${directory.absolutePath}")
            return
        }
        
        val files = getFilesFromDirectory(directory)
        onLog("DirectoryWatcher: Found ${files.size} items to check")
        
        files.forEach { file ->
            if (file.isFile && !processedFiles.contains(file.absolutePath)) {
                onLog("DirectoryWatcher: Found new file: ${file.name}")
                processFile(file)
            }
        }
    }

    // 处理文件
    private fun processFile(file: File) {
        // Use add()'s return value to make the check-then-act atomic. Previously
        // contains() + add() was not atomic, so two concurrent scans could both
        // pass the check and upload the file twice.
        if (!processedFiles.add(file.absolutePath)) {
            return
        }

        onLog("DirectoryWatcher: Processing new file: ${file.name}")
        listener?.onFileDetected(file.name)

        // 开始上传
        scope.launch {
            uploadFile(file)
        }
    }

    // 上传文件
    private suspend fun uploadFile(file: File) {
        try {
            onLog("DirectoryWatcher: Uploading file: ${file.name}")
            
            // 替换文件名中的非法字符
            val safeFileName = file.name.replace(":", "_").replace("*", "_").replace("?", "_")
                .replace("\"", "_").replace("<", "_").replace(">", "_").replace("|", "_")
            
            // 获取文件的 Uri (使用 MediaStore 方式)
            val uri = getFileUri(context, file, onLog)
            
            val result = transferService.uploadFileChunked(
                context = context,
                uri = uri,
                fileName = safeFileName
            )
            
            if (result.isSuccess) {
                onLog("DirectoryWatcher: File uploaded successfully: ${file.name}")
                uploadedFiles.add(file.absolutePath)
                listener?.onFileUploaded(file.name)
            } else {
                onLog("DirectoryWatcher: Failed to upload file: ${file.name}, ${result.exceptionOrNull()?.message}")
                // 如果上传失败，从 processedFiles 中移除，下次可以重试
                processedFiles.remove(file.absolutePath)
                listener?.onFileUploadFailed(file.name, result.exceptionOrNull()?.message ?: "Unknown error")
            }
        } catch (e: Exception) {
            onLog("DirectoryWatcher: Error uploading file: ${file.name}: ${e.message}")
            processedFiles.remove(file.absolutePath)
            listener?.onFileUploadFailed(file.name, e.message ?: "Unknown error")
        }
    }

    // 获取监视状态
    fun isCurrentlyWatching(): Boolean = isWatching.get()

    // 获取已上传文件数量
    fun getUploadedFileCount(): Int = uploadedFiles.size

    // 从已处理集合中移除文件
    fun removeProcessedFile(filePath: String) {
        processedFiles.remove(filePath)
        uploadedFiles.remove(filePath)
    }

    // 清理资源
    fun destroy() {
        stopWatching()
        scope.cancel()
        // Quit the background HandlerThread to release its looper and thread.
        scanThread.quit()
    }
}
