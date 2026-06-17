package com.bluelink.transfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
@Composable
fun DeviceScanScreen(
    bluetoothAdapter: BluetoothAdapter?,
    onConnected: (BluetoothClient, FileTransferService) -> Unit,
    onDisconnect: () -> Unit,
    onReconnecting: () -> Unit = {},
    onReconnected: (BluetoothClient, FileTransferService) -> Unit = { _, _ -> },
    isConnected: Boolean,
    visible: Boolean = true
) {
    var isScanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var connectionStatus by remember { mutableStateOf("") }
    var debugLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isReconnecting by remember { mutableStateOf(false) }
    var reconnectAttempt by remember { mutableStateOf(0) }
    var scanTimeLeft by remember { mutableStateOf(10) }
    var isAutoConnecting by remember { mutableStateOf(false) }
    var autoConnectProgress by remember { mutableStateOf("") }

    fun addLog(msg: String) {
        debugLogs = (listOf("[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] $msg") + debugLogs).take(20)
    }

    val scope = rememberCoroutineScope()
    val client = remember { bluetoothAdapter?.let { BluetoothClient(it) } }
    val context = LocalContext.current
    val deviceStore = remember { BluetoothDeviceStore(context) }
    var savedDevices by remember { mutableStateOf(deviceStore.loadDevices()) }

    fun saveAndRefresh(device: BluetoothDevice) {
        deviceStore.saveDevice(device.name, device.address)
        savedDevices = deviceStore.loadDevices()
    }

    // Set up toast and reconnection callbacks
    DisposableEffect(client) {
        client?.toast = { msg ->
            addLog(msg)
        }
        client?.onReconnecting = { attempt ->
            reconnectAttempt = attempt
            isReconnecting = true
            connectionStatus = if (attempt == 0) "后台重连中..." else "正在重连... ($attempt/3)"
            onReconnecting()
        }
        client?.onReconnected = {
            isReconnecting = false
            addLog(">>> 重连成功")
            connectionStatus = "重连成功"
            client?.let { c ->
                val transferService = FileTransferService(c)
                onReconnected(c, transferService)
            }
        }
        client?.onDisconnected = {
            isReconnecting = true
            addLog(">>> 连接断开，后台重连中...")
            connectionStatus = "连接断开，后台重连中..."
            onReconnecting()
        }
        onDispose {
            client?.onReconnecting = null
            client?.onReconnected = null
            client?.onDisconnected = null
            client?.toast = null
        }
    }

    // Bluetooth receiver for device discovery
    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            if (!devices.any { d -> d.address == it.address }) {
                                devices = devices + it
                            }
                        }
                    }
                }
            }
        }
    }

    // Register receiver
    DisposableEffect(Unit) {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(receiver, filter)
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) { }
        }
    }

    // Auto-connect to saved devices when tab becomes visible and not connected
    LaunchedEffect(visible, isConnected) {
        if (visible && !isConnected && !isAutoConnecting && savedDevices.isNotEmpty()) {
            isAutoConnecting = true
            autoConnectProgress = "正在尝试自动连接已记录设备..."

            for ((index, saved) in savedDevices.withIndex()) {
                if (!visible || isConnected) break

                autoConnectProgress = "正在尝试连接 (${index + 1}/${savedDevices.size}): ${saved.name ?: saved.address}"
                addLog(">>> 自动连接尝试: ${saved.name ?: saved.address}")

                try {
                    val device = bluetoothAdapter?.getRemoteDevice(saved.address)
                    if (device != null) {
                        val result = client?.connect(device)
                        if (result?.isSuccess == true) {
                            connectionStatus = "自动连接成功"
                            addLog(">>> 自动连接成功: ${saved.name ?: saved.address}")
                            saveAndRefresh(device)
                            val transferService = FileTransferService(client)
                            onConnected(client, transferService)
                            isAutoConnecting = false
                            autoConnectProgress = ""
                            return@LaunchedEffect
                        } else {
                            addLog(">>> 自动连接失败: ${saved.name ?: saved.address}")
                        }
                    }
                } catch (e: Exception) {
                    addLog(">>> 自动连接异常: ${e.message}")
                }

                delay(500)
            }

            isAutoConnecting = false
            autoConnectProgress = ""
            if (!isConnected) {
                addLog(">>> 所有已记录设备均无法连接")
            }
        }
    }

    // Use Box to control visibility without destroying composition
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = if (visible) 1f else 0f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "蓝牙设备",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "搜索并连接附近的蓝牙设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-connect progress
            if (isAutoConnecting) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = autoConnectProgress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Scan Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !isConnected && !isAutoConnecting) {
                        if (isScanning) {
                            bluetoothAdapter?.cancelDiscovery()
                            isScanning = false
                        } else {
                            isScanning = true
                            devices = emptyList()
                            bluetoothAdapter?.startDiscovery()
                            scope.launch {
                                scanTimeLeft = 10
                                while (scanTimeLeft > 0 && isScanning) {
                                    delay(1000)
                                    if (isScanning) scanTimeLeft--
                                }
                                if (isScanning) {
                                    bluetoothAdapter?.cancelDiscovery()
                                    isScanning = false
                                }
                            }
                        }
                    }
                    .then(
                        if (isScanning) {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        } else if (isAutoConnecting) {
                            Modifier.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.primary)
                        }
                    )
            ) {
                if (isScanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(scanTimeLeft / 10f)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                                    )
                                )
                            )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isScanning) {
                        Icon(Icons.Default.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.onError)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("停止扫描 ($scanTimeLeft)", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    } else if (isAutoConnecting) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("自动连接中...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("扫描设备", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Debug log display
            AnimatedVisibility(
                visible = debugLogs.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("调试日志:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(
                                onClick = {
                                    val logText = debugLogs.joinToString("\n")
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Debug Logs", logText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        debugLogs.take(5).forEach { log ->
                            Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (connectionStatus.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            connectionStatus.contains("成功") || connectionStatus.contains("已连接")
                                -> MaterialTheme.colorScheme.primaryContainer
                            connectionStatus.contains("重连") -> MaterialTheme.colorScheme.secondaryContainer
                            connectionStatus.contains("失败") || connectionStatus.contains("错误")
                                -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isReconnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = connectionStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Connected State
            if (isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("已连接PC服务端", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                client?.disconnect()
                                onDisconnect()
                                connectionStatus = "已断开"
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ) {
                            Text("断开连接")
                        }
                    }
                }
            } else {
                // Saved devices section
                if (savedDevices.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "已记录设备 (${savedDevices.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Device List
                if (devices.isEmpty() && savedDevices.isEmpty() && !isScanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("未发现设备", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("点击上方扫描按钮搜索设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f)
                    ) {
                        // Saved devices
                        if (savedDevices.isNotEmpty()) {
                            items(savedDevices) { saved ->
                                val isScanned = devices.any { it.address == saved.address }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clickable(enabled = !isConnected && !isAutoConnecting) {
                                            scope.launch {
                                                val device = bluetoothAdapter?.getRemoteDevice(saved.address)
                                                if (device != null) {
                                                    addLog(">>> 点击连接: ${saved.name ?: saved.address}")
                                                    connectionStatus = "正在连接..."
                                                    val result = client?.connect(device)
                                                    if (result?.isSuccess == true) {
                                                        connectionStatus = "连接成功"
                                                        saveAndRefresh(device)
                                                        val transferService = FileTransferService(client)
                                                        onConnected(client, transferService)
                                                    } else {
                                                        val error = result?.exceptionOrNull()?.message ?: "未知错误"
                                                        connectionStatus = "连接失败: $error"
                                                        addLog(">>> 连接失败: $error")
                                                    }
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isScanned)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.History,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = saved.name ?: "未知设备",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = saved.address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isScanned) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "在线",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Scanned devices (not in saved list)
                        val newDevices = devices.filter { d ->
                            savedDevices.none { s -> s.address == d.address }
                        }
                        if (newDevices.isNotEmpty()) {
                            if (savedDevices.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "发现 ${newDevices.size} 个新设备:",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                            items(newDevices) { device ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clickable(enabled = !isConnected && !isAutoConnecting) {
                                            scope.launch {
                                                addLog(">>> 点击连接: ${device.name} (${device.address})")
                                                connectionStatus = "正在连接..."
                                                val result = client?.connect(device)
                                                if (result?.isSuccess == true) {
                                                    connectionStatus = "连接成功"
                                                    saveAndRefresh(device)
                                                    val transferService = FileTransferService(client)
                                                    onConnected(client, transferService)
                                                } else {
                                                    val error = result?.exceptionOrNull()?.message ?: "未知错误"
                                                    connectionStatus = "连接失败: $error"
                                                    addLog(">>> 连接失败: $error")
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.name ?: "未知设备",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = device.address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Scanning indicator at bottom
                        if (isScanning && devices.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("正在扫描...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
