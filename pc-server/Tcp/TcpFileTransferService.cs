using BluetoothFileServer.Services;

namespace BluetoothFileServer.Tcp;

/// <summary>
/// TCP 通道的 FileTransferService，逻辑全部在 FileTransferServiceBase 中
/// </summary>
public class TcpFileTransferService : FileTransferServiceBase
{
    public TcpFileTransferService(string sharePath, string uploadPath, TcpConnectedClient client, System.Windows.Threading.Dispatcher dispatcher)
        : base(sharePath, uploadPath, client, dispatcher, "TCP")
    {
    }
}
