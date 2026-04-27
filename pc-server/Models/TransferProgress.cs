namespace BluetoothFileServer.Models;

public class TransferProgress
{
    public string FileName { get; set; } = "";
    public long TotalBytes { get; set; }
    public long TransferredBytes { get; set; }
    public double Percent => TotalBytes > 0 ? (TransferredBytes * 100.0 / TotalBytes) : 0;
}