using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace BluetoothFileServer.Tcp;

/// <summary>
/// WebSocket中继客户端 - 用于内网穿透
/// PC服务端主动连接到公网中继服务器，Android客户端也连接中继服务器进行配对
/// 配对后，中继服务器透传双方数据
/// </summary>
public class RelayClient : IDisposable
{
    private ClientWebSocket? _ws;
    private CancellationTokenSource? _cts;
    private readonly string _relayUrl;
    private readonly string _deviceId;
    private readonly string _deviceName;
    private readonly int _tcpPort;
    private bool _isRunning;
    private bool _isPaired;

    // 配对后的数据回调 - 将Android客户端发来的数据转发给本地TCP服务处理
    public event EventHandler<byte[]>? DataReceived;
    public event EventHandler<string>? StatusChanged;
    public event EventHandler? ClientConnected;
    public event EventHandler? ClientDisconnected;
    public event EventHandler<string?>? PublicIPChanged;

    public bool IsConnected => _ws?.State == WebSocketState.Open;
    public bool IsPaired => _isPaired;
    public string? PublicIP { get; private set; }

    public RelayClient(string relayUrl, string deviceId, string deviceName, int tcpPort = 9000)
    {
        _relayUrl = relayUrl;
        _deviceId = deviceId;
        _deviceName = deviceName;
        _tcpPort = tcpPort;
    }

    public async Task StartAsync()
    {
        _cts = new CancellationTokenSource();
        _isRunning = true;

        while (_isRunning && !_cts.Token.IsCancellationRequested)
        {
            try
            {
                StatusChanged?.Invoke(this, $"正在连接中继服务器 {_relayUrl}...");
                _ws = new ClientWebSocket();
                await _ws.ConnectAsync(new Uri(_relayUrl), _cts.Token);
                StatusChanged?.Invoke(this, "中继服务器已连接");

                // 注册PC服务端 - 同时上报本地TCP端口，用于P2P直连尝试
                await SendJson(new { type = "register_pc", deviceId = _deviceId, deviceName = _deviceName, tcpPort = _tcpPort });

                // 接收消息循环
                await ReceiveLoop(_cts.Token);
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke(this, $"中继连接断开: {ex.Message}");
                _isPaired = false;
            }

            if (_isRunning && !_cts.Token.IsCancellationRequested)
            {
                StatusChanged?.Invoke(this, "5秒后重连中继服务器...");
                await Task.Delay(5000, _cts.Token);
            }
        }
    }

    private async Task ReceiveLoop(CancellationToken token)
    {
        var buffer = new byte[524288]; // 512KB buffer

        while (_ws?.State == WebSocketState.Open && !token.IsCancellationRequested)
        {
            try
            {
                var result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), token);

                if (result.MessageType == WebSocketMessageType.Text)
                {
                    var text = Encoding.UTF8.GetString(buffer, 0, result.Count);
                    HandleControlMessage(text);
                }
                else if (result.MessageType == WebSocketMessageType.Binary)
                {
                    // 二进制数据，转发给本地处理
                    var data = new byte[result.Count];
                    Array.Copy(buffer, data, result.Count);
                    DataReceived?.Invoke(this, data);
                }
                else if (result.MessageType == WebSocketMessageType.Close)
                {
                    break;
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke(this, $"接收数据错误: {ex.Message}");
                break;
            }
        }
    }

    private void HandleControlMessage(string text)
    {
        try
        {
            var msg = JsonDocument.Parse(text);
            var type = msg.RootElement.GetProperty("type").GetString();

            switch (type)
            {
                case "registered":
                    var deviceId = msg.RootElement.GetProperty("deviceId").GetString();
                    // 读取服务器返回的公网IP（中继服务器看到的对端地址）
                    if (msg.RootElement.TryGetProperty("publicIP", out var ipEl))
                    {
                        PublicIP = ipEl.GetString();
                        PublicIPChanged?.Invoke(this, PublicIP);
                    }
                    StatusChanged?.Invoke(this, $"已注册，设备ID: {deviceId}");
                    break;

                case "client_connected":
                    _isPaired = true;
                    StatusChanged?.Invoke(this, "Android客户端已连接");
                    ClientConnected?.Invoke(this, EventArgs.Empty);
                    break;

                case "client_disconnected":
                    _isPaired = false;
                    StatusChanged?.Invoke(this, "Android客户端已断开");
                    ClientDisconnected?.Invoke(this, EventArgs.Empty);
                    break;

                case "pc_disconnected":
                    _isPaired = false;
                    StatusChanged?.Invoke(this, "中继连接已断开");
                    break;

                case "welcome":
                    // 忽略欢迎消息
                    break;
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"处理控制消息错误: {ex.Message}");
        }
    }

    /// <summary>
    /// 发送二进制数据给Android客户端（通过中继服务器透传）
    /// </summary>
    public async Task SendDataAsync(byte[] data)
    {
        if (_ws?.State == WebSocketState.Open)
        {
            try
            {
                await _ws.SendAsync(new ArraySegment<byte>(data), WebSocketMessageType.Binary, true, _cts?.Token ?? CancellationToken.None);
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke(this, $"发送数据失败: {ex.Message}");
            }
        }
    }

    private async Task SendJson(object obj)
    {
        if (_ws?.State == WebSocketState.Open)
        {
            var json = JsonSerializer.Serialize(obj);
            var bytes = Encoding.UTF8.GetBytes(json);
            await _ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, _cts?.Token ?? CancellationToken.None);
        }
    }

    public void Stop()
    {
        _isRunning = false;
        _cts?.Cancel();
        try
        {
            _ws?.CloseAsync(WebSocketCloseStatus.NormalClosure, "关闭", CancellationToken.None);
        }
        catch { }
        _isPaired = false;
    }

    public void Dispose()
    {
        Stop();
        _ws?.Dispose();
        _cts?.Dispose();
    }
}
