namespace BluetoothFileServer.Models;

public class FileItem
{
    public string Name { get; set; } = "";
    public long Size { get; set; }
    public DateTime ModifiedTime { get; set; }
    public bool IsDirectory { get; set; }
    public bool IsParentDirectory { get; set; }  // Special flag for ".." entry

    public string FormattedSize => IsParentDirectory ? "" : (IsDirectory ? "<DIR>" : FormatSize(Size));

    private static string FormatSize(long bytes)
    {
        string[] sizes = { "B", "KB", "MB", "GB" };
        int order = 0;
        double size = bytes;
        while (size >= 1024 && order < sizes.Length - 1)
        {
            order++;
            size /= 1024;
        }
        return $"{size:0.##} {sizes[order]}";
    }
}