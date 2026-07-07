using System.Threading.Channels;

namespace BluetoothFileServer.Tcp;

/// <summary>
/// Relay传输适配器 - 将RelayClient适配成ITransferConnection接口
/// 让TcpFileTransferService可以透明地处理WebSocket中继数据
///
/// 工作原理：
/// - RelayClient收到二进制数据时通过DataReceived事件推送给本适配器
/// - 本适配器把数据块写入Channel作为接收缓冲
/// - ReadAsync按需从缓冲读取，可能跨多个WebSocket帧拼装
/// - WriteAsync把数据通过RelayClient.SendDataAsync发回Android客户端
/// </summary>
public class RelayTransferAdapter : ITransferConnection
{
    private readonly RelayClient _relay;
    private readonly Channel<byte[]> _recvChannel;
    private byte[] _current = Array.Empty<byte>();
    private int _currentOffset = 0;
    private bool _closed = false;

    public string DeviceName { get; set; } = "Relay Client";
    public string DeviceAddress { get; set; } = "Remote";

    public RelayTransferAdapter(RelayClient relay, string deviceName = "Relay Client")
    {
        _relay = relay;
        DeviceName = deviceName;
        _recvChannel = Channel.CreateUnbounded<byte[]>(new UnboundedChannelOptions
        {
            SingleReader = true,
            SingleWriter = true
        });
        _relay.DataReceived += OnDataReceived;
        _relay.ClientDisconnected += OnClientDisconnected;
    }

    private void OnDataReceived(object? sender, byte[] data)
    {
        if (data == null || data.Length == 0) return;
        _recvChannel.Writer.TryWrite(data);
    }

    private void OnClientDisconnected(object? sender, EventArgs e)
    {
        // Android客户端断开时关闭通道，让 ReadAsync 返回0
        try { _recvChannel.Writer.TryComplete(); } catch { }
    }

    public async Task<int> ReadAsync(byte[] buffer, int offset, int count)
    {
        if (_closed) return 0;

        int totalCopied = 0;
        try
        {
            while (totalCopied < count)
            {
                if (_currentOffset >= _current.Length)
                {
                    // 当前缓冲已读完，读取下一块
                    if (!await _recvChannel.Reader.WaitToReadAsync())
                    {
                        // 通道已关闭
                        return totalCopied > 0 ? totalCopied : 0;
                    }
                    _current = await _recvChannel.Reader.ReadAsync();
                    _currentOffset = 0;
                }

                int toCopy = Math.Min(count - totalCopied, _current.Length - _currentOffset);
                Array.Copy(_current, _currentOffset, buffer, offset + totalCopied, toCopy);
                _currentOffset += toCopy;
                totalCopied += toCopy;
            }
        }
        catch (Exception)
        {
            return totalCopied > 0 ? totalCopied : 0;
        }
        return totalCopied;
    }

    public async Task WriteAsync(byte[] buffer, int offset, int count)
    {
        if (_closed || count == 0) return;

        try
        {
            byte[] data;
            if (offset == 0 && count == buffer.Length)
            {
                data = buffer;
            }
            else
            {
                data = new byte[count];
                Array.Copy(buffer, offset, data, 0, count);
            }
            await _relay.SendDataAsync(data);
        }
        catch
        {
        }
    }

    public void Close()
    {
        if (_closed) return;
        _closed = true;
        try
        {
            _relay.DataReceived -= OnDataReceived;
            _relay.ClientDisconnected -= OnClientDisconnected;
            _recvChannel.Writer.TryComplete();
        }
        catch { }
    }
}
