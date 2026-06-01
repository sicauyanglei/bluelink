package com.bluelink.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private var _bluetoothClient by mutableStateOf<BluetoothClient?>(null)
    private var _tcpClient by mutableStateOf<TcpClient?>(null)
    private var _transferService by mutableStateOf<FileTransferService?>(null)
    private var _refreshTrigger by mutableIntStateOf(0)
    private var _pathChangedTrigger by mutableIntStateOf(0)
    private var _resumeTrigger by mutableIntStateOf(0)
    private var _connectionStateVersion by mutableIntStateOf(0)
    private var _tcpHost by mutableStateOf("")
    private var _tcpPort by mutableStateOf("9000")
    private var _debugLogs by mutableStateOf<List<String>>(emptyList())
    private var _showDebugLog by mutableStateOf(false)
    private var _resumeCount by mutableIntStateOf(0)
    val bluetoothClient get() = _bluetoothClient
    val tcpClient get() = _tcpClient
    val transferService get() = _transferService
    val refreshTrigger get() = _refreshTrigger
    val pathChangedTrigger get() = _pathChangedTrigger
    val resumeTrigger get() = _resumeTrigger
    val resumeCount get() = _resumeCount
    val connectionStateVersion get() = _connectionStateVersion
    val tcpHost get() = _tcpHost
    val tcpPort get() = _tcpPort
    val debugLogs get() = _debugLogs
    val showDebugLog get() = _showDebugLog

    fun addDebugLog(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _debugLogs = (_debugLogs + "[$timestamp] $msg").takeLast(100)
    }

    fun toggleDebugLog() {
        _showDebugLog = !_showDebugLog
    }

    fun clearDebugLog() {
        _debugLogs = emptyList()
        // Delete all bluelink_debug_log* files from Downloads
        lifecycleScope.launch {
            try {
                val resolver = contentResolver
                val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
                val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf("log%", "Download/")
                var deletedCount = 0
                contentResolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id
                        )
                        resolver.delete(uri, null, null)
                        deletedCount++
                    }
                }
                val message = if (deletedCount > 0) {
                    "已清空日志和${deletedCount}个日志文件"
                } else {
                    "已清空日志"
                }
                android.widget.Toast.makeText(
                    this@MainActivity,
                    message,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = e.message ?: "未知错误"
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "清空失败: $errorMsg",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun triggerPathRefresh() {
        _pathChangedTrigger++
    }

    private val requiredPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            // Android 13+ (API 33+)
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.REQUEST_INSTALL_PACKAGES,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Android 12-12L (API 31-32)
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.REQUEST_INSTALL_PACKAGES,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        else -> {
            // Android 11 and below (API 30-)
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        addDebugLog(">>> 权限请求结果:")
        permissions.forEach { (permission, granted) ->
            addDebugLog(">>>   $permission: $granted")
        }
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            addDebugLog(">>> 部分权限未授予！")
        } else {
            addDebugLog(">>> 所有权限已授予！")
        }
        // 检查管理存储权限
        checkAndRequestManageStorage()
    }

    // 用于请求管理存储权限
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAndRequestManageStorage()
    }

    private fun checkAndRequestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                addDebugLog(">>> 已获得管理存储权限！")
            } else {
                addDebugLog(">>> 请求管理存储权限...")
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                manageStorageLauncher.launch(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        _resumeCount++
        _connectionStateVersion++
        addDebugLog(">>> MainActivity onResume - resumeCount=$_resumeCount")

        val btClient = _bluetoothClient
        val tcpCli = _tcpClient
        val btDisconnected = btClient != null && !btClient.isConnected
        val tcpDisconnected = tcpCli != null && !tcpCli.isConnected

        if (btDisconnected && tcpDisconnected) {
            if (btClient?.isReconnecting != true && tcpCli?.isReconnecting != true) {
                if (btClient?.hasConnectionInfo == true) {
                    addDebugLog(">>> onResume: 触发蓝牙后台重连")
                    kotlinx.coroutines.GlobalScope.launch { btClient.autoReconnect() }
                } else if (tcpCli?.hasConnectionInfo == true) {
                    addDebugLog(">>> onResume: 触发TCP后台重连")
                    kotlinx.coroutines.GlobalScope.launch { tcpCli.autoReconnect() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addDebugLog(">>> MainActivity onCreate 开始")
        
        // Request permissions if not granted
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        addDebugLog(">>> 需要请求的权限: ${permissionsToRequest.joinToString()}")

        if (permissionsToRequest.isNotEmpty()) {
            addDebugLog(">>> 正在请求权限...")
            permissionLauncher.launch(permissionsToRequest)
        } else {
            addDebugLog(">>> 所有权限已授予，无需请求")
            // 检查管理存储权限
            checkAndRequestManageStorage()
        }

        // Get Bluetooth adapter
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        bluetoothAdapter = bluetoothAdapter,
                        bluetoothClient = bluetoothClient,
                        tcpClient = tcpClient,
                        transferService = transferService,
                        pathChangedTrigger = _pathChangedTrigger,
                        resumeCount = _resumeCount,
                        connectionStateVersion = _connectionStateVersion,
                        tcpHost = _tcpHost,
                        tcpPort = _tcpPort,
                        debugLogs = _debugLogs,
                        showDebugLog = _showDebugLog,
                        onBluetoothConnected = { client, service ->
                            _bluetoothClient = client
                            _tcpClient = null
                            _transferService = service
                            _connectionStateVersion++
                            service.onPathChanged = {
                                _pathChangedTrigger++
                            }
                            addDebugLog("蓝牙连接成功")
                        },
                        onTcpConnected = { client, service ->
                            _tcpClient = client
                            _bluetoothClient = null
                            _transferService = service
                            _connectionStateVersion++
                            service.onPathChanged = {
                                _pathChangedTrigger++
                            }
                            addDebugLog("TCP连接成功")
                        },
                        onDisconnect = {
                            bluetoothClient?.disconnect()
                            tcpClient?.disconnect()
                            _bluetoothClient = null
                            _tcpClient = null
                            _transferService = null
                            _connectionStateVersion++
                            addDebugLog("连接已断开")
                        },
                        onReconnecting = {
                            _transferService = null
                            _connectionStateVersion++
                            addDebugLog("连接断开，正在重连...")
                        },
                        onReconnected = { client, service ->
                            if (client is TcpClient) {
                                _tcpClient = client
                                _bluetoothClient = null
                            } else if (client is BluetoothClient) {
                                _bluetoothClient = client
                                _tcpClient = null
                            }
                            _transferService = service
                            _connectionStateVersion++
                            service.onPathChanged = {
                                _pathChangedTrigger++
                            }
                            addDebugLog("重连成功")
                        },
                        onTcpHostChange = { _tcpHost = it },
                        onTcpPortChange = { _tcpPort = it },
                        onToggleDebugLog = { _showDebugLog = !_showDebugLog },
                        onClearDebugLog = { clearDebugLog() },
                        onAddDebugLog = { msg -> addDebugLog(msg) },
                        onTriggerPathRefresh = { triggerPathRefresh() }
                    )
                }
            }
        }
    }
}

private val lightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF26A69A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00695C),
    tertiary = Color(0xFF7C4DFF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DAFF),
    onTertiaryContainer = Color(0xFF4A148C),
    error = Color(0xFFF44336),
    onError = Color.White,
    errorContainer = Color(0xFFFFCDD2),
    onErrorContainer = Color(0xFFB71C1C),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E)
)

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    bluetoothAdapter: BluetoothAdapter?,
    bluetoothClient: BluetoothClient?,
    tcpClient: TcpClient?,
    transferService: FileTransferService?,
    pathChangedTrigger: Int = 0,
    resumeCount: Int = 0,
    connectionStateVersion: Int = 0,
    tcpHost: String,
    tcpPort: String,
    debugLogs: List<String> = emptyList(),
    showDebugLog: Boolean = false,
    onBluetoothConnected: (BluetoothClient, FileTransferService) -> Unit,
    onTcpConnected: (TcpClient, FileTransferService) -> Unit,
    onDisconnect: () -> Unit,
    onReconnecting: () -> Unit = {},
    onReconnected: (TransferClient, FileTransferService) -> Unit = { _, _ -> },
    onTcpHostChange: (String) -> Unit,
    onTcpPortChange: (String) -> Unit,
    onToggleDebugLog: () -> Unit = {},
    onClearDebugLog: () -> Unit = {},
    onAddDebugLog: (String) -> Unit = {},
    onTriggerPathRefresh: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Use key to force reconnect check on resume
    var resumeKey by remember { mutableIntStateOf(0) }

    // Connection status - recompute when connectionStateVersion changes
    val actualBluetoothConnected by remember(connectionStateVersion) {
        mutableStateOf(bluetoothClient?.isConnected == true)
    }
    val actualTcpConnected by remember(connectionStateVersion) {
        mutableStateOf(tcpClient?.isConnected == true)
    }
    val isClientReconnecting = bluetoothClient?.isReconnecting == true || tcpClient?.isReconnecting == true
    val hasClient = bluetoothClient != null || tcpClient != null
    val isConnected = actualBluetoothConnected || actualTcpConnected

    val connectionStatus = when {
        isClientReconnecting -> "正在重连..."
        actualBluetoothConnected -> "蓝牙已连接"
        actualTcpConnected -> "TCP已连接"
        hasClient -> "连接断开，重连中..."
        else -> "未连接"
    }

    val connectionType: String? = when {
        actualBluetoothConnected -> "蓝牙"
        actualTcpConnected -> "TCP"
        bluetoothClient != null -> "蓝牙"
        tcpClient != null -> "TCP"
        else -> null
    }

    // Lifecycle observer to detect resume and refresh connection status
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Custom Header
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BluLink",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = "文件传输",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (isConnected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = connectionStatus,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isConnected)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Debug button
                        TextButton(
                            onClick = onToggleDebugLog,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                        ) {
                            Text("日志", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Debug log dialog
        if (showDebugLog) {
            val context = androidx.compose.ui.platform.LocalContext.current
            AlertDialog(
                onDismissRequest = onToggleDebugLog,
                title = { Text("调试日志") },
                text = {
                    Column {
                        val logText = debugLogs.joinToString("\n")
                        Text(
                            text = logText.ifEmpty { "暂无日志" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        )
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            val logText = debugLogs.joinToString("\n")
                            if (logText.isNotEmpty()) {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Debug Log", logText)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "日志为空", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("复制")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onClearDebugLog) {
                            Text("清空")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onToggleDebugLog) {
                            Text("关闭")
                        }
                    }
                }
            )
        }

        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("蓝牙") },
                enabled = !isConnected || bluetoothClient?.isConnected == true
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("TCP") },
                enabled = !isConnected || tcpClient?.isConnected == true
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("文件")
                        if (isConnected && connectionType != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = connectionType ?: "",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            )
        }

        // Layer all tabs using Box to preserve state of all tabs
        Box(modifier = Modifier.weight(1f)) {
            // Bluetooth Tab - always composed, just hidden when not selected
            DeviceScanScreen(
                bluetoothAdapter = bluetoothAdapter,
                onConnected = { client, service ->
                    onBluetoothConnected(client, service)
                    selectedTab = 2
                },
                onDisconnect = {
                    onDisconnect()
                    selectedTab = 0
                },
                onReconnecting = onReconnecting,
                onReconnected = { client, service ->
                    onReconnected(client, service)
                },
                isConnected = bluetoothClient?.isConnected == true,
                visible = selectedTab == 0
            )

            // TCP Tab - always composed, just hidden when not selected
            ConnectScreen(
                onConnected = { client, service ->
                    onTcpConnected(client, service)
                    selectedTab = 2
                },
                onDisconnect = {
                    onDisconnect()
                    selectedTab = 1
                },
                onReconnecting = onReconnecting,
                onReconnected = { client, service ->
                    onReconnected(client, service)
                },
                isConnected = tcpClient?.isConnected == true,
                tabVisible = selectedTab == 1,
                visible = selectedTab == 1,
                savedHost = tcpHost,
                savedPort = tcpPort,
                onHostChange = onTcpHostChange,
                onPortChange = onTcpPortChange
            )

            // File Tab - always composed, just hidden when not selected
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = if (selectedTab == 2) 1f else 0f)
                    .then(
                        if (selectedTab == 2) Modifier else Modifier.pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent() }
                        }
                    )
            ) {
                if (isConnected && transferService != null) {
                    val client = tcpClient ?: bluetoothClient
                    if (client != null) {
                        FileListScreen(
                            client = client,
                            transferService = transferService,
                            triggerRefresh = selectedTab + connectionStateVersion,
                            pathChangedTrigger = pathChangedTrigger,
                            resumeCount = resumeCount,
                            connectionType = connectionType,
                            onConnectionLost = {
                                onReconnecting()
                            },
                        )
                    }
                } else if (hasClient) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (isClientReconnecting) "正在重连..." else "连接断开，后台重连中...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "请稍候，将自动恢复连接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "请先连接PC服务端",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "通过蓝牙或TCP连接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}
