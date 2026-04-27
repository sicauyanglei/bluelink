package com.bluelink.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private var _tcpHost by mutableStateOf("")
    private var _tcpPort by mutableStateOf("9000")
    private var _debugLogs by mutableStateOf<List<String>>(emptyList())
    private var _showDebugLog by mutableStateOf(false)
    val bluetoothClient get() = _bluetoothClient
    val tcpClient get() = _tcpClient
    val transferService get() = _transferService
    val refreshTrigger get() = _refreshTrigger
    val pathChangedTrigger get() = _pathChangedTrigger
    val resumeTrigger get() = _resumeTrigger
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

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.REQUEST_INSTALL_PACKAGES  // For APK installation
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            // Handle permission denied - show message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions if not granted
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
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
                        tcpHost = _tcpHost,
                        tcpPort = _tcpPort,
                        debugLogs = _debugLogs,
                        showDebugLog = _showDebugLog,
                        onBluetoothConnected = { client, service ->
                            _bluetoothClient = client
                            _tcpClient = null
                            _transferService = service
                            service.onPathChanged = {
                                _pathChangedTrigger++
                            }
                            addDebugLog("蓝牙连接成功")
                        },
                        onTcpConnected = { client, service ->
                            _tcpClient = client
                            _bluetoothClient = null
                            _transferService = service
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
                            addDebugLog("连接已断开")
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
    tcpHost: String,
    tcpPort: String,
    debugLogs: List<String> = emptyList(),
    showDebugLog: Boolean = false,
    onBluetoothConnected: (BluetoothClient, FileTransferService) -> Unit,
    onTcpConnected: (TcpClient, FileTransferService) -> Unit,
    onDisconnect: () -> Unit,
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

    // Connection status - depends on both resumeKey and actual connection state
    val isConnected by remember(resumeKey, bluetoothClient?.isConnected, tcpClient?.isConnected) {
        mutableStateOf(bluetoothClient?.isConnected == true || tcpClient?.isConnected == true)
    }

    // Connection status for header
    val connectionStatus by remember(resumeKey, bluetoothClient?.isConnected, tcpClient?.isConnected) {
        mutableStateOf(when {
            bluetoothClient?.isConnected == true -> "蓝牙已连接"
            tcpClient?.isConnected == true -> "TCP已连接"
            else -> "未连接"
        })
    }

    // Connection type for display
    val connectionType by remember(resumeKey, bluetoothClient?.isConnected, tcpClient?.isConnected) {
        mutableStateOf(when {
            bluetoothClient?.isConnected == true -> "蓝牙"
            tcpClient?.isConnected == true -> "TCP"
            else -> null
        })
    }

    // Lifecycle observer to detect resume and refresh connection status
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val toast = { msg: String ->
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }

                // 先刷新 UI 状态
                resumeKey++

                // 检查客户端连接状态
                val clientToReconnect = when {
                    bluetoothClient != null && !bluetoothClient!!.isConnected -> {
                        toast("蓝牙已断开，正在重连...")
                        bluetoothClient
                    }
                    tcpClient != null && !tcpClient!!.isConnected -> {
                        toast("TCP已断开，正在重连...")
                        tcpClient
                    }
                    else -> null
                }

                if (clientToReconnect != null) {
                    scope.launch {
                        val result = clientToReconnect.autoReconnect()
                        if (result.isSuccess) {
                            toast("重连成功")
                            resumeKey++
                        } else {
                            toast("重连失败")
                            onDisconnect()
                        }
                    }
                }
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
            AlertDialog(
                onDismissRequest = onToggleDebugLog,
                title = { Text("调试日志 (点击复制)") },
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
                onConnected = { client ->
                    android.util.Log.d("MainActivity", ">>> onConnected callback: client=$client")
                    val service = FileTransferService(client)
                    android.util.Log.d("MainActivity", ">>> created FileTransferService: $service")
                    onBluetoothConnected(client, service)
                    // Auto switch to file tab after connection
                    selectedTab = 2
                    android.util.Log.d("MainActivity", ">>> onBluetoothConnected done")
                },
                onDisconnect = {
                    onDisconnect()
                    // Switch back to Bluetooth tab when disconnected
                    selectedTab = 0
                },
                isConnected = bluetoothClient?.isConnected == true,
                visible = selectedTab == 0
            )

            // TCP Tab - always composed
            if (selectedTab == 1) {
                ConnectScreen(
                    onConnected = { client, service ->
                        onTcpConnected(client, service)
                        // Auto switch to file tab after connection
                        selectedTab = 2
                    },
                    onDisconnect = {
                        onDisconnect()
                        // Switch back to TCP tab when disconnected
                        selectedTab = 1
                    },
                    isConnected = tcpClient?.isConnected == true,
                    tabVisible = selectedTab == 1,
                    savedHost = tcpHost,
                    savedPort = tcpPort,
                    onHostChange = onTcpHostChange,
                    onPortChange = onTcpPortChange
                )
            }

            // File Tab - always composed
            if (selectedTab == 2) {
                if (isConnected && transferService != null) {
                    val client = tcpClient ?: bluetoothClient
                    if (client != null) {
                        FileListScreen(
                            client = client,
                            transferService = transferService,
                            triggerRefresh = selectedTab,
                            pathChangedTrigger = pathChangedTrigger,
                            connectionType = connectionType,
                            onConnectionLost = {
                                android.util.Log.d("MainActivity", ">>> FileTab: connection lost, triggering reconnect")
                                scope.launch {
                                    val result = client.autoReconnect()
                                    android.util.Log.d("MainActivity", ">>> FileTab: reconnect result=${result.isSuccess}")
                                    if (result.isSuccess) {
                                        // 重连成功，刷新文件列表
                                        android.util.Log.d("MainActivity", ">>> FileTab: 重连成功，刷新文件列表")
                                        onTriggerPathRefresh()
                                        resumeKey++
                                    } else {
                                        // 重连失败，切换到对应标签页
                                        android.util.Log.d("MainActivity", ">>> FileTab: 重连失败，切换标签")
                                        onDisconnect()
                                        if (client is BluetoothClient) {
                                            selectedTab = 0
                                        } else {
                                            selectedTab = 1
                                        }
                                    }
                                }
                            }
                        )
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
