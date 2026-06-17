using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using BluetoothFileServer.Protocols;

namespace BluetoothFileServer.Tcp;

public class TcpServer : IDisposable
{
    private TcpListener? _listener;
    private bool _isRunning;
    private readonly object _lock = new();
    private CancellationTokenSource? _cts;

    public event EventHandler<string>? ConnectionStatusChanged;
    public event EventHandler<TcpClientConnectionEventArgs>? ClientConnected;

    public const int DefaultPort = 9000;

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

        try
        {
            _listener?.Stop();
            _listener = null;
        }
        catch { }

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

                ConnectionStatusChanged?.Invoke(this, "客户端已连接!");
                var connectedClient = new TcpConnectedClient(client);
                ClientConnected?.Invoke(this, new TcpClientConnectionEventArgs(connectedClient));
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                ConnectionStatusChanged?.Invoke(this, $"接受连接异常: {ex.Message}");
                if (!token.IsCancellationRequested)
                {
                    await Task.Delay(1000, token);
                }
            }
        }
    }

    public void Dispose()
    {
        StopServer();
    }
}

public class TcpClientConnectionEventArgs : EventArgs
{
    public TcpConnectedClient Client { get; }
    public TcpClientConnectionEventArgs(TcpConnectedClient client) => Client = client;
}

public class TcpConnectedClient
{
    private readonly TcpClient _client;

    public string DeviceName => "TCP Client";
    public string DeviceAddress => "Connected";

    public TcpConnectedClient(TcpClient client)
    {
        _client = client;
    }

    public NetworkStream GetStream() => _client.GetStream();

    public async Task<int> ReadAsync(byte[] buffer, int offset, int count)
    {
        try
        {
            var stream = _client.GetStream();
            return await stream.ReadAsync(buffer, offset, count);
        }
        catch
        {
            return 0;
        }
    }

    public async Task WriteAsync(byte[] buffer, int offset, int count)
    {
        try
        {
            var stream = _client.GetStream();
            await stream.WriteAsync(buffer, offset, count);
            await stream.FlushAsync();
        }
        catch
        {
        }
    }

    public void Close()
    {
        try
        {
            _client.Close();
        }
        catch { }
    }
}
