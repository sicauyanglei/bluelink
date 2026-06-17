using System.Diagnostics;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using System.IO;
using BluetoothFileServer.Services;

namespace BluetoothFileServer.Bluetooth;

public class BluetoothServer : IDisposable
{
    private BluetoothListener? _listener;
    private bool _isRunning;
    private readonly object _lock = new();
    private CancellationTokenSource? _cts;
    private static readonly string LogFilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "BluLink",
        "bluelink.log");

    public event EventHandler<string>? ConnectionStatusChanged;
    public event EventHandler<ClientConnectionEventArgs>? ClientConnected;

    // UUID must match Android client - using standard SPP UUID
    public static readonly Guid ServiceUuid = new("00001101-0000-1000-8000-00805F9B34FB");

    public bool IsRunning
    {
        get { lock (_lock) { return _isRunning; } }
    }

    public void StartServer()
    {
        lock (_lock)
        {
            if (_isRunning) return;

            _cts = new CancellationTokenSource();

            try
            {
                var radio = BluetoothRadio.PrimaryRadio;
                if (radio != null)
                {
                    Debug.WriteLine($"Bluetooth Radio: {radio.Name}");
                }

                _listener = new BluetoothListener(ServiceUuid);
                _listener.ServiceName = "BluetoothFileServer";
                _listener.Start();

                _isRunning = true;
                ConnectionStatusChanged?.Invoke(this, $"蓝牙服务已启动，UUID={ServiceUuid}\n正在等待连接...");

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

        ConnectionStatusChanged?.Invoke(this, "服务已停止");
    }

    private async Task AcceptClientsAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested && _listener != null)
        {
            try
            {
                ConnectionStatusChanged?.Invoke(this, "正在等待蓝牙连接...");
                var client = await Task.Run(() => _listener.AcceptBluetoothClient(), token);

                ConnectionStatusChanged?.Invoke(this, "客户端已连接!");
                var connectedClient = new BluetoothConnectedClient(client);
                ClientConnected?.Invoke(this, new ClientConnectionEventArgs(connectedClient));
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

    private static void LogToFile(string message)
    {
        try
        {
            var logDir = Path.GetDirectoryName(LogFilePath);
            if (!string.IsNullOrEmpty(logDir) && !Directory.Exists(logDir))
                Directory.CreateDirectory(logDir);
            var timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff");
            var logLine = $"[{timestamp}] {message}{Environment.NewLine}";
            AsyncLogger.Append(LogFilePath, logLine);
            Debug.WriteLine(logLine);
        }
        catch { }
    }
}

public class ClientConnectionEventArgs : EventArgs
{
    public BluetoothConnectedClient Client { get; }
    public ClientConnectionEventArgs(BluetoothConnectedClient client) => Client = client;
}

public class BluetoothConnectedClient : ITransferClient
{
    private readonly BluetoothClient _client;
    private Stream? _stream;
    private const int ReadTimeoutMs = 120000; // 2 分钟

    public string DeviceName
    {
        get
        {
            try { return _client.RemoteMachineName ?? "Unknown Device"; }
            catch { return "Unknown Device"; }
        }
    }

    // P0-4: 暴露真实地址
    public string DeviceAddress
    {
        get
        {
            try { return _client.RemoteMachineAddress?.ToString() ?? "Connected"; }
            catch { return "Connected"; }
        }
    }

    public BluetoothConnectedClient(BluetoothClient client)
    {
        _client = client;
    }

    private Stream GetStreamInternal()
    {
        if (_stream == null) _stream = _client.GetStream();
        return _stream;
    }

    /// <summary>
    /// 修复 P0-4：用 CancellationTokenSource 替代 Thread.Sleep，避免线程泄漏
    /// </summary>
    public async Task<int> ReadAsync(byte[] buffer, int offset, int count)
    {
        try
        {
            var stream = GetStreamInternal();
            using var cts = new CancellationTokenSource(ReadTimeoutMs);
            try
            {
                return await stream.ReadAsync(buffer, offset, count, cts.Token);
            }
            catch (OperationCanceledException)
            {
                Debug.WriteLine("[BluetoothConnectedClient] ReadAsync timeout");
                return -1;
            }
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"[BluetoothConnectedClient] ReadAsync error: {ex.Message}");
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
            Debug.WriteLine($"[BluetoothConnectedClient] WriteAsync error: {ex.Message}");
            throw;
        }
    }

    public void Close()
    {
        try { _stream?.Close(); } catch { }
        try { _client.Close(); } catch { }
    }
}
