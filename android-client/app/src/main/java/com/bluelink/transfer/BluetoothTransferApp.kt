package com.bluelink.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun BluetoothTransferApp(
    tcpClient: TcpClient?,
    transferService: FileTransferService?,
    onDisconnect: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("设备") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("文件") }
            )
        }

        when (selectedTab) {
            0 -> TcpDeviceScreen(
                isConnected = tcpClient?.isConnected == true,
                onDisconnect = onDisconnect
            )
            1 -> {
                if (tcpClient?.isConnected == true && transferService != null) {
                    TcpFileListScreen(tcpClient, transferService)
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text("请先连接设备", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TcpDeviceScreen(
    isConnected: Boolean,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("连接状态", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("已连接到PC服务端")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onDisconnect) {
                        Text("断开连接")
                    }
                }
            }
        } else {
            Text("未连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TcpFileListScreen(
    tcpClient: TcpClient,
    transferService: FileTransferService
) {
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("远程文件列表", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        val result = transferService.getFileList()
                        if (result.isSuccess) {
                            files = result.getOrNull()?.items ?: emptyList()
                        } else {
                            files = emptyList()
                        }
                        statusMessage = if (result.isSuccess) "刷新成功" else "刷新失败"
                        isLoading = false
                    }
                },
                enabled = !isLoading && tcpClient.isConnected
            ) {
                Text(if (isLoading) "加载中..." else "刷新列表")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("暂无文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(files.filter { !it.isDirectory }) { file ->
                    FileItemCard(
                        file = file,
                        onDownload = {
                            scope.launch {
                                statusMessage = "正在下载: ${file.name}"
                                val result = transferService.downloadFile(file.name)
                                statusMessage = if (result.isSuccess) "下载完成" else "下载失败"
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FileItemCard(
    file: FileItem,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge)
                Row {
                    Text(file.formattedSize, style = MaterialTheme.typography.bodySmall)
                    if (file.modifiedTime.isNotEmpty()) {
                        Text(" • ${file.modifiedTime}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = onDownload) {
                Text("下载")
            }
        }
    }
}
