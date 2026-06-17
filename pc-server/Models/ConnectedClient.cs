namespace BluetoothFileServer.Models;

/// <summary>
/// P3-5: 已连接客户端信息（用于客户端管理 UI）
/// </summary>
public class ConnectedClient
{
    /// <summary>通道：蓝牙 / TCP</summary>
    public string Channel { get; set; } = "";

    /// <summary>设备名称</summary>
    public string DeviceName { get; set; } = "";

    /// <summary>设备地址</summary>
    public string DeviceAddress { get; set; } = "";

    /// <summary>连接时间</summary>
    public DateTime ConnectTime { get; set; } = DateTime.Now;

    /// <summary>关联的传输服务（用于断开连接）</summary>
    public Services.FileTransferServiceBase? Service { get; set; }

    /// <summary>显示文本</summary>
    public string DisplayText =>
        $"[{Channel}] {DeviceName} ({DeviceAddress}) - {ConnectTime:HH:mm:ss}";
}
