using System.Net;
using System.Net.Sockets;
using BluetoothFileServer.Protocols;
using BluetoothFileServer.Services;

namespace BluetoothFileServer.Tcp;

public class TcpServer : IDisposable
{
    private TcpListener? _listener;
    private bool _isRunning;
    private readonly object _lock = new();
    private CancellationTokenSource? _cts;
    // P2-10: 连接数限制
    private int _activeConnections;
    private readonly int _maxConnections;

    public event EventHandler<string>? ConnectionStatusChanged;
    public event EventHandler<TcpClientConnectionEventArgs>? ClientConnected;

    public const int DefaultPort = 9000;

    public TcpServer(int maxConnections = 5)
    {
        _maxConnections = maxConnections > 0 ? maxConnections : 5;
    }

    public bool IsRunning
    {
        get { lock (_lock) { return _isRunning; } }
    }

    public void StartServer(int port = DefaultPort)
    {
        lock (_lock)
        {
            if (_isRunning) return;
            _cts = new CancellationTokenSource();

            try
            {
                _listener = new TcpListener(IPAddress.Any, port);
                _listener.Start();
                _isRunning = true;

                ConnectionStatusChanged?.Invoke(this, $"TCP服务已启动，端口 {port}\n正在等待连接...");
                Task.Run(() => AcceptClientsAsync(_cts.Token), _cts.Token);
            }
            catch (Exception ex)
            {
                ConnectionStatusChanged?.Invoke(this, $"启动失败: {ex.Message}");
                _isRunning = false;
            }
        }
    }

    public void StopServer()
    {
        lock (_lock)
        {
            if (!_isRunning) return;
            _isRunning = false;
        }

        _cts?.Cancel();
        try { _listener?.Stop(); _listener = null; } catch { }
        _cts?.Dispose();
        _cts = null;

        ConnectionStatusChanged?.Invoke(this, "TCP服务已停止");
    }

    private async Task AcceptClientsAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested && _listener != null)
        {
            try
            {
                ConnectionStatusChanged?.Invoke(this, "正在等待TCP连接...");
                var client = await _listener.AcceptTcpClientAsync(token);

                // P2-10: 连接数限制
                if (_activeConnections >= _maxConnections)
                {
                    ConnectionStatusChanged?.Invoke(this, $"拒绝连接：已达最大连接数 {_maxConnections}");
                    try { client.Close(); } catch { }
                    continue;
                }

                System.Threading.Interlocked.Increment(ref _activeConnections);
                ConnectionStatusChanged?.Invoke(this, "客户端已连接!");
                var connectedClient = new TcpConnectedClient(client);
                ClientConnected?.Invoke(this, new TcpClientConnectionEventArgs(connectedClient, () => System.Threading.Interlocked.Decrement(ref _activeConnections)));
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                ConnectionStatusChanged?.Invoke(this, $"接受连接异常: {ex.Message}");
                if (!token.IsCancellationRequested) await Task.Delay(1000, token);
            }
        }
    }

    public void Dispose() => StopServer();
}

public class TcpClientConnectionEventArgs : EventArgs
{
    public TcpConnectedClient Client { get; }
    public Action OnDisconnected { get; }
    public TcpClientConnectionEventArgs(TcpConnectedClient client, Action onDisconnected)
    {
        Client = client;
        OnDisconnected = onDisconnected;
    }
}

public class TcpConnectedClient : ITransferClient
{
    private readonly TcpClient _client;
    private NetworkStream? _stream;

    // P0-4: 暴露真实远端地址
    public string DeviceName => "TCP Client";
    public string DeviceAddress
    {
        get
        {
            try { return _client.Client.RemoteEndPoint?.ToString() ?? "Connected"; }
            catch { return "Connected"; }
        }
    }

    public TcpConnectedClient(TcpClient client)
    {
        _client = client;
        try
        {
            _client.NoDelay = true;
            _client.ReceiveTimeout = 120000;
            _client.SendTimeout = 120000;
        }
        catch { }
    }

    private NetworkStream GetStreamInternal()
    {
        if (_stream == null) _stream = _client.GetStream();
        return _stream;
    }

    public async Task<int> ReadAsync(byte[] buffer, int offset, int count)
    {
        try
        {
            var stream = GetStreamInternal();
            return await stream.ReadAsync(buffer, offset, count);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[TcpConnectedClient] ReadAsync error: {ex.Message}");
            return 0;
        }
    }

    public async Task WriteAsync(byte[] buffer, int offset, int count)
    {
        try
        {
            var stream = GetStreamInternal();
            await stream.WriteAsync(buffer, offset, count);
            await stream.FlushAsync();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[TcpConnectedClient] WriteAsync error: {ex.Message}");
            throw;
        }
    }

    public void Close()
    {
        try { _stream?.Close(); } catch { }
        try { _client.Close(); } catch { }
    }
}
