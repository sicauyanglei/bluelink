using System.Net;
using System.Net.Sockets;
using System.Text;

namespace BluetoothFileServer.Tcp;

public class DiscoveryServer : IDisposable
{
    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private bool _isRunning;

    public event EventHandler<string>? DiscoveryStatusChanged;

    public const int DiscoveryPort = 9001;
    private const string DiscoveryMessage = "BLUELINK_DISCOVER";

    public void StartDiscovery()
    {
        if (_isRunning) return;

        _cts = new CancellationTokenSource();

        try
        {
            _udpClient = new UdpClient();
            _udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
            _udpClient.EnableBroadcast = true;

            _isRunning = true;
            DiscoveryStatusChanged?.Invoke(this, "Discovery服务已启动");

            Task.Run(() => ListenAsync(_cts.Token), _cts.Token);
        }
        catch (Exception ex)
        {
            DiscoveryStatusChanged?.Invoke(this, $"Discovery启动失败: {ex.Message}");
            _isRunning = false;
        }
    }

    public void StopDiscovery()
    {
        if (!_isRunning) return;

        _cts?.Cancel();
        _udpClient?.Close();
        _udpClient = null;
        _isRunning = false;
        DiscoveryStatusChanged?.Invoke(this, "Discovery服务已停止");
    }

    private async Task ListenAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested && _udpClient != null)
        {
            try
            {
                var result = await _udpClient.ReceiveAsync(token);
                var message = Encoding.UTF8.GetString(result.Buffer);

                DiscoveryStatusChanged?.Invoke(this, $"收到discovery请求 from {result.RemoteEndPoint}");

                if (message == DiscoveryMessage)
                {
                    // Get all local IP addresses
                    var allIPs = GetAllLocalIPAddresses();
                    var response = Encoding.UTF8.GetBytes($"BLUELINK_RESPONSE|{allIPs}|9000");
                    await _udpClient.SendAsync(response, result.RemoteEndPoint, token);
                    DiscoveryStatusChanged?.Invoke(this, $"发送响应 to {result.RemoteEndPoint}: {allIPs}");
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                DiscoveryStatusChanged?.Invoke(this, $"Discovery错误: {ex.Message}");
            }
        }
    }

    private string GetAllLocalIPAddresses()
    {
        var ips = new List<string>();
        try
        {
            var host = Dns.GetHostEntry(Dns.GetHostName());
            foreach (var ip in host.AddressList)
            {
                if (ip.AddressFamily == AddressFamily.InterNetwork)
                {
                    ips.Add(ip.ToString());
                }
            }
        }
        catch { }
        return ips.Count > 0 ? string.Join(",", ips) : "127.0.0.1";
    }

    public void Dispose()
    {
        StopDiscovery();
    }
}
