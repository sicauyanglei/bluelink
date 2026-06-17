using BluetoothFileServer.Services;

namespace BluetoothFileServer.Bluetooth;

/// <summary>
/// 蓝牙通道的 FileTransferService，逻辑全部在 FileTransferServiceBase 中
/// </summary>
public class FileTransferService : FileTransferServiceBase
{
    public FileTransferService(string sharePath, string uploadPath, BluetoothConnectedClient client, System.Windows.Threading.Dispatcher dispatcher)
        : base(sharePath, uploadPath, client, dispatcher, "BT")
    {
    }
}
