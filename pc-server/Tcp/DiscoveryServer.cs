using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace BluetoothFileServer.Tcp;

public class DiscoveryServer : IDisposable
{
    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private bool _isRunning;
    private readonly string _authToken;

    public event EventHandler<string>? DiscoveryStatusChanged;

    public const int DiscoveryPort = 9001;
    private const string DiscoveryMessage = "BLUELINK_DISCOVER";

    public DiscoveryServer(string authToken = "")
    {
        _authToken = authToken ?? "";
    }

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

                // P3-1: 支持带 token 的发现请求 BLUELINK_DISCOVER|token
                string requestToken = "";
                var msgParts = message.Split('|');
                if (msgParts.Length >= 2 && msgParts[0] == DiscoveryMessage)
                {
                    requestToken = msgParts[1];
                }
                else if (message != DiscoveryMessage)
                {
                    continue;
                }

                // 如果配置了 token，必须匹配
                if (!string.IsNullOrEmpty(_authToken) && requestToken != _authToken)
                {
                    DiscoveryStatusChanged?.Invoke(this, $"discovery token 不匹配，拒绝响应");
                    continue;
                }

                // 过滤虚拟网卡，仅返回物理/无线网卡 IP
                var allIPs = GetPhysicalLocalIPAddresses();
                var response = Encoding.UTF8.GetBytes($"BLUELINK_RESPONSE|{allIPs}|9000");
                await _udpClient.SendAsync(response, result.RemoteEndPoint, token);
                DiscoveryStatusChanged?.Invoke(this, $"发送响应 to {result.RemoteEndPoint}: {allIPs}");
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                DiscoveryStatusChanged?.Invoke(this, $"Discovery错误: {ex.Message}");
            }
        }
    }

    /// <summary>
    /// 仅返回物理/无线网卡的 IPv4 地址，过滤虚拟网卡（VMware/Hyper-V/WSL 等）
    /// </summary>
    private string GetPhysicalLocalIPAddresses()
    {
        var ips = new List<string>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                // 过滤虚拟网卡
                var desc = nic.Description.ToLowerInvariant();
                var name = nic.Name.ToLowerInvariant();
                if (desc.Contains("virtual") || desc.Contains("vmware") || desc.Contains("hyper-v") ||
                    desc.Contains("wsl") || desc.Contains("virtualbox") || desc.Contains("tunnel") ||
                    name.Contains("vmware") || name.Contains("hyper-v") || name.Contains("wsl") ||
                    name.Contains("virtual"))
                {
                    continue;
                }

                foreach (var ip in nic.GetIPProperties().UnicastAddresses)
                {
                    if (ip.Address.AddressFamily == AddressFamily.InterNetwork &&
                        !IPAddress.IsLoopback(ip.Address))
                    {
                        ips.Add(ip.Address.ToString());
                    }
                }
            }
        }
        catch { }
        return ips.Count > 0 ? string.Join(",", ips) : "127.0.0.1";
    }

    public void Dispose() => StopDiscovery();
}
