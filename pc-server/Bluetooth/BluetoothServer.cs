using System.Diagnostics;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using System.IO;

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
                // Get the Bluetooth radio
                var radio = BluetoothRadio.PrimaryRadio;
                if (radio != null)
                {
                    Debug.WriteLine($"Bluetooth Radio: {radio.Name}");
                    Debug.WriteLine($"Local Address: {radio.LocalAddress}");
                    Debug.WriteLine($"Current Mode: {radio.Mode}");
                    // Note: Setting radio mode to discoverable requires Windows Bluetooth settings
                    // or using native Win32 API. InTheHand library may not support this directly.
                }

                // Create Bluetooth listener for our service UUID
                _listener = new BluetoothListener(ServiceUuid);
                _listener.ServiceName = "BluetoothFileServer";

                Debug.WriteLine($"Starting listener with UUID: {ServiceUuid}");
                _listener.Start();
                Debug.WriteLine("Listener started successfully");

                // Wait a bit for listener to be ready
                Thread.Sleep(500);

                _isRunning = true;
                ConnectionStatusChanged?.Invoke(this, $"蓝牙服务已启动，UUID={ServiceUuid}\n正在等待连接...");

                // Start accepting clients in background
                Task.Run(() => AcceptClientsAsync(_cts.Token), _cts.Token);
            }
            catch (Exception ex)
            {
                ConnectionStatusChanged?.Invoke(this, $"启动失败: {ex.Message}");
                Debug.WriteLine($"Bluetooth server error: {ex}");
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
        catch (Exception ex)
        {
            Debug.WriteLine($"Stop error: {ex.Message}");
        }

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
                LogToFile("[AcceptClients] Waiting for Bluetooth connection...");
                ConnectionStatusChanged?.Invoke(this, "正在等待蓝牙连接...");
                // Accept waiting client - this is blocking so run in Task
                var client = await Task.Run(() => _listener.AcceptBluetoothClient(), token);

                LogToFile($"[AcceptClients] Client accepted: {client.RemoteMachineName ?? "Unknown"}");
                ConnectionStatusChanged?.Invoke(this, "客户端已连接!");
                var connectedClient = new BluetoothConnectedClient(client);
                ClientConnected?.Invoke(this, new ClientConnectionEventArgs(connectedClient));
            }
            catch (OperationCanceledException)
            {
                LogToFile("[AcceptClients] Cancelled");
                break;
            }
            catch (Exception ex)
            {
                LogToFile($"[AcceptClients] Exception: {ex.GetType().Name}: {ex.Message}\n{ex.StackTrace}");
                ConnectionStatusChanged?.Invoke(this, $"接受连接异常: {ex.Message}\n{ex.GetType().Name}");
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

    private static void LogToFile(string message)
    {
        try
        {
            var logDir = Path.GetDirectoryName(LogFilePath);
            if (!string.IsNullOrEmpty(logDir) && !Directory.Exists(logDir))
                Directory.CreateDirectory(logDir);
            var timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff");
            var logLine = $"[{timestamp}] {message}{Environment.NewLine}";
            File.AppendAllText(LogFilePath, logLine);
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

public class BluetoothConnectedClient
{
    private readonly BluetoothClient _client;

    public string DeviceName {
        get {
            try {
                return _client.RemoteMachineName ?? "Unknown Device";
            } catch (Exception ex) {
                Debug.WriteLine($"[BluetoothConnectedClient] RemoteMachineName error: {ex.Message}");
                return "Unknown Device";
            }
        }
    }
    public string DeviceAddress => "Connected";

    public BluetoothConnectedClient(BluetoothClient client)
    {
        _client = client;
    }

    public System.IO.Stream GetStream() => _client.GetStream();

    public async Task<int> ReadAsync(byte[] buffer, int offset, int count)
    {
        try
        {
            var stream = _client.GetStream();

            // Use a timeout to prevent blocking forever
            // 120 seconds (2 minutes) to allow large file transfers over Bluetooth
            var timeoutTask = Task.Run(() => {
                Thread.Sleep(120000); // 120 second timeout
                return -1;
            });

            var readTask = stream.ReadAsync(buffer, offset, count);
            var completedTask = await Task.WhenAny(readTask, timeoutTask);

            if (completedTask == timeoutTask)
            {
                Debug.WriteLine($"[BluetoothConnectedClient] ReadAsync timeout after 120s");
                return -1;
            }

            return await readTask;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"[BluetoothConnectedClient] ReadAsync error: {ex.GetType().Name}: {ex.Message}");
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
