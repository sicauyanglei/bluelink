/**
 * BlueLink WebSocket 中继服务器
 * 
 * 部署方法：
 * 1. 安装 Node.js (v16+)
 * 2. npm install ws
 * 3. node server.js
 * 4. 或设置 PORT 环境变量指定端口: PORT=8080 node server.js
 * 
 * 工作原理：
 * - PC服务端启动后，作为WebSocket客户端连接到此服务器，注册设备ID
 * - Android客户端连接到此服务器，通过设备ID找到对应的PC服务端
 * - 服务器转发双方的数据（二进制透传）
 */

const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');

const PORT = process.env.PORT || 9090;

// 先创建HTTP服务器，用于健康检查
const server = http.createServer((req, res) => {
    if (req.url === '/' || req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'ok',
            service: 'bluelink-relay',
            pcClients: pcClients.size,
            androidClients: androidClients.size,
            uptime: process.uptime()
        }));
    } else {
        res.writeHead(404);
        res.end('Not Found');
    }
});

const wss = new WebSocketServer({ server });

// 存储已注册的PC服务端连接: deviceId -> { ws, info }
const pcClients = new Map();
// 存储Android客户端连接: deviceId -> ws
const androidClients = new Map();

server.listen(PORT, () => {
    console.log(`BlueLink中继服务器启动，端口: ${PORT}`);
    console.log(`健康检查: http://localhost:${PORT}/health`);
});

wss.on('connection', (ws, req) => {
    const clientIP = req.socket.remoteAddress;
    console.log(`新连接: ${clientIP}`);

    let clientType = null; // 'pc' 或 'android'
    let deviceId = null;
    let pairedWS = null; // 配对的另一端

    ws.on('message', (data, isBinary) => {
        try {
            if (!isBinary) {
                // 文本消息，解析JSON控制指令
                const msg = JSON.parse(data.toString());
                console.log(`控制消息:`, msg);

                if (msg.type === 'register_pc') {
                    // PC服务端注册
                    clientType = 'pc';
                    deviceId = msg.deviceId || `pc-${Date.now()}`;
                    // 记录PC公网IP（中继服务器看到的对端地址）
                    const pcPublicIP = clientIP.replace(/^::ffff:/, '');
                    pcClients.set(deviceId, { ws, info: msg, publicIP: pcPublicIP });
                    console.log(`PC注册成功: deviceId=${deviceId}, name=${msg.deviceName || 'unknown'}, publicIP=${pcPublicIP}`);

                    ws.send(JSON.stringify({ type: 'registered', deviceId: deviceId, publicIP: pcPublicIP }));
                }
                else if (msg.type === 'register_android') {
                    // Android客户端注册，尝试配对
                    clientType = 'android';
                    const targetDeviceId = msg.deviceId;

                    const pcClient = pcClients.get(targetDeviceId);
                    if (pcClient && pcClient.ws.readyState === WebSocket.OPEN) {
                        // 配对成功
                        deviceId = targetDeviceId;
                        androidClients.set(deviceId, ws);
                        pairedWS = pcClient.ws;

                        // 获取PC的公网IP和端口信息
                        const pcPublicIP = pcClient.publicIP || '';
                        const pcTcpPort = pcClient.info.tcpPort || 9000;

                        // 通知Android客户端配对成功，并告知PC的公网地址（用于P2P直连尝试）
                        ws.send(JSON.stringify({
                            type: 'paired',
                            deviceId: targetDeviceId,
                            pcPublicIP: pcPublicIP,
                            pcTcpPort: pcTcpPort,
                            deviceName: pcClient.info.deviceName || 'PC'
                        }));
                        // 通知PC客户端配对成功
                        pcClient.ws.send(JSON.stringify({
                            type: 'client_connected',
                            clientPublicIP: clientIP.replace(/^::ffff:/, '')
                        }));

                        // 给PC客户端设置配对
                        pcClient.ws._pairedWS = ws;
                        ws._pairedWS = pcClient.ws;

                        console.log(`Android配对成功: deviceId=${targetDeviceId}, pcPublicIP=${pcPublicIP}:${pcTcpPort}`);
                    } else {
                        ws.send(JSON.stringify({ type: 'error', message: '未找到对应的PC服务端' }));
                        console.log(`Android配对失败: deviceId=${targetDeviceId} 不存在`);
                    }
                }
                else if (msg.type === 'get_pc_list') {
                    // 获取已注册的PC列表
                    const list = [];
                    pcClients.forEach((client, id) => {
                        if (client.ws.readyState === WebSocket.OPEN) {
                            list.push({
                                deviceId: id,
                                deviceName: client.info.deviceName || 'PC',
                                publicIP: client.publicIP || '',
                                tcpPort: client.info.tcpPort || 9000
                            });
                        }
                    });
                    ws.send(JSON.stringify({ type: 'pc_list', list: list }));
                }
            } else {
                // 二进制数据，透传给配对的另一端
                if (ws._pairedWS && ws._pairedWS.readyState === WebSocket.OPEN) {
                    ws._pairedWS.send(data, { binary: true });
                }
            }
        } catch (e) {
            console.error('处理消息错误:', e.message);
        }
    });

    ws.on('close', () => {
        console.log(`连接关闭: ${clientIP}, type=${clientType}, deviceId=${deviceId}`);
        
        if (clientType === 'pc' && deviceId) {
            pcClients.delete(deviceId);
            // 通知配对的Android客户端
            if (androidClients.has(deviceId)) {
                const androidWS = androidClients.get(deviceId);
                if (androidWS.readyState === WebSocket.OPEN) {
                    androidWS.send(JSON.stringify({ type: 'pc_disconnected' }));
                }
                androidClients.delete(deviceId);
            }
        } else if (clientType === 'android' && deviceId) {
            androidClients.delete(deviceId);
            // 通知配对的PC服务端
            if (pcClients.has(deviceId)) {
                const pcWS = pcClients.get(deviceId).ws;
                if (pcWS.readyState === WebSocket.OPEN) {
                    pcWS.send(JSON.stringify({ type: 'client_disconnected' }));
                    pcWS._pairedWS = null;
                }
            }
        }
        
        // 清理配对引用
        if (ws._pairedWS) {
            ws._pairedWS._pairedWS = null;
        }
    });

    ws.on('error', (error) => {
        console.error(`WebSocket错误: ${error.message}`);
    });

    // 发送欢迎消息
    ws.send(JSON.stringify({ type: 'welcome', message: 'BlueLink中继服务器已连接' }));
});

// 定期清理失效连接
setInterval(() => {
    pcClients.forEach((client, id) => {
        if (client.ws.readyState !== WebSocket.OPEN) {
            pcClients.delete(id);
            console.log(`清理失效PC连接: ${id}`);
        }
    });
    androidClients.forEach((ws, id) => {
        if (ws.readyState !== WebSocket.OPEN) {
            androidClients.delete(id);
            console.log(`清理失效Android连接: ${id}`);
        }
    });
}, 30000);

console.log('等待连接...');
