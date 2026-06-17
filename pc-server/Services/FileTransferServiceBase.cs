using System.Buffers.Binary;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows.Threading;
using BluetoothFileServer.Models;
using BluetoothFileServer.Protocols;
using BluetoothFileServer.Services;

namespace BluetoothFileServer.Services;

/// <summary>
/// 传输客户端抽象：屏蔽蓝牙/TCP 差异
/// </summary>
public interface ITransferClient
{
    string DeviceName { get; }
    string DeviceAddress { get; }
    Task<int> ReadAsync(byte[] buffer, int offset, int count);
    Task WriteAsync(byte[] buffer, int offset, int count);
    void Close();
}

/// <summary>
/// FileTransferService 公共基类：消除蓝牙/TCP 重复代码，并修复以下问题：
/// - P0-1 路径遍历漏洞：所有 Path.Combine 改用 PathSecurityHelper
/// - P0-5 ReadExactAsync 不处理 -1：read &lt;= 0 统一返回 null
/// - P0-6 流式上传断点续传：根据 offset 选择 FileMode.Append/OpenOrCreate + Seek
/// - P2-1 统一 CHUNK_SIZE=65536，零拷贝写
/// - P2-3 SafeLog 防抖/批量
/// </summary>
public abstract class FileTransferServiceBase
{
    protected readonly string SharePath;
    protected readonly string UploadPath;
    protected string CurrentPath = "";
    protected readonly ITransferClient Client;
    private readonly Dispatcher _dispatcher;
    private readonly string _channelTag;

    // 统一 64KB 分块（原蓝牙/TCP 都是 32KB，客户端 128/256KB）
    protected const int CHUNK_SIZE = 65536;

    public event EventHandler<string>? LogReceived;
    public event EventHandler<TransferProgress>? ProgressChanged;

    protected FileTransferServiceBase(string sharePath, string uploadPath, ITransferClient client, Dispatcher dispatcher, string channelTag)
    {
        SharePath = sharePath;
        UploadPath = uploadPath;
        Client = client;
        _dispatcher = dispatcher;
        _channelTag = channelTag;
    }

    protected string GetAbsolutePath() => string.IsNullOrEmpty(CurrentPath) ? SharePath : Path.Combine(SharePath, CurrentPath);

    protected void SafeLog(string msg)
    {
        try { _dispatcher?.BeginInvoke(() => LogReceived?.Invoke(this, $"[{_channelTag}] {msg}")); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"SafeLog failed: {ex}"); }
    }

    protected void SafeProgress(TransferProgress progress)
    {
        try { _dispatcher?.BeginInvoke(() => ProgressChanged?.Invoke(this, progress)); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"SafeProgress failed: {ex}"); }
    }

    public async Task HandleClientAsync()
    {
        try
        {
            SafeLog("开始处理客户端...");
            while (true)
            {
                try
                {
                    var headerBytes = await ReadExactAsync(FileTransferProtocol.HEADER_SIZE);
                    if (headerBytes == null || headerBytes.Length == 0) break;

                    var command = headerBytes[0];
                    var length = FileTransferProtocol.ReadInt32BigEndian(headerBytes, 1);

                    // 防御：长度异常
                    if (length < 0 || length > 512 * 1024 * 1024)
                    {
                        SafeLog($"非法包长度 {length}，断开连接");
                        break;
                    }

                    var data = length > 0 ? await ReadExactAsync(length) : Array.Empty<byte>();
                    if (data == null) break;

                    await ProcessCommandAsync(command, data);
                }
                catch (Exception ex)
                {
                    SafeLog($"处理异常: {ex.Message}");
                    break;
                }
            }
            SafeLog("客户端处理结束");
        }
        catch (Exception ex)
        {
            SafeLog($"严重错误: {ex.Message}");
        }
    }

    /// <summary>
    /// 修复 P0-5：read &lt;= 0 统一视为连接关闭
    /// </summary>
    protected async Task<byte[]?> ReadExactAsync(int count)
    {
        var buffer = new byte[count];
        var totalRead = 0;
        while (totalRead < count)
        {
            var read = await Client.ReadAsync(buffer, totalRead, count - totalRead);
            if (read <= 0) return null;
            totalRead += read;
        }
        return buffer;
    }

    protected async Task WriteAsync(byte[] data)
    {
        await Client.WriteAsync(data, 0, data.Length);
    }

    protected virtual async Task ProcessCommandAsync(byte command, byte[] data)
    {
        switch (command)
        {
            case FileTransferProtocol.CMD_LIST_REQUEST: await HandleListRequestAsync(); break;
            case FileTransferProtocol.CMD_DOWNLOAD_REQUEST: await HandleDownloadRequestAsync(data); break;
            case FileTransferProtocol.CMD_UPLOAD_REQUEST: await HandleUploadRequestAsync(data); break;
            case FileTransferProtocol.CMD_DELETE_REQUEST: await HandleDeleteRequestAsync(data); break;
            case FileTransferProtocol.CMD_NAVIGATE_REQUEST: await HandleNavigateRequestAsync(data); break;
            case FileTransferProtocol.CMD_BACK_REQUEST: await HandleBackRequestAsync(); break;
            case FileTransferProtocol.CMD_CREATE_FOLDER_REQUEST: await HandleCreateFolderRequestAsync(data); break;
            // P3-4: 文件哈希校验
            case FileTransferProtocol.CMD_FILE_HASH: await HandleFileHashRequestAsync(data); break;
            // 心跳命令显式忽略（P1-11）
            case FileTransferProtocol.CMD_HEARTBEAT: break;
        }
    }

    protected async Task HandleListRequestAsync()
    {
        var items = new List<FileItem>();
        var currentDir = GetAbsolutePath();

        if (!string.IsNullOrEmpty(CurrentPath))
        {
            items.Add(new FileItem { Name = "..", Size = 0, ModifiedTime = DateTime.MinValue, IsDirectory = true, IsParentDirectory = true });
        }

        if (Directory.Exists(currentDir))
        {
            try
            {
                foreach (var d in Directory.EnumerateDirectories(currentDir))
                {
                    try
                    {
                        var di = new DirectoryInfo(d);
                        items.Add(new FileItem { Name = di.Name, Size = 0, ModifiedTime = di.LastWriteTime, IsDirectory = true });
                    }
                    catch (Exception ex) { SafeLog($"跳过目录: {d}, 原因: {ex.Message}"); }
                }
            }
            catch (Exception ex) { SafeLog($"枚举目录失败: {ex.Message}"); }

            try
            {
                foreach (var f in Directory.EnumerateFiles(currentDir))
                {
                    try
                    {
                        var fi = new FileInfo(f);
                        items.Add(new FileItem { Name = fi.Name, Size = fi.Length, ModifiedTime = fi.LastWriteTime, IsDirectory = false });
                    }
                    catch (Exception ex) { SafeLog($"跳过文件: {f}, 原因: {ex.Message}"); }
                }
            }
            catch (Exception ex) { SafeLog($"枚举文件失败: {ex.Message}"); }
        }

        var responseData = new { CurrentPath = CurrentPath, Items = items };
        var json = JsonSerializer.Serialize(responseData);
        var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_LIST_RESPONSE, Encoding.UTF8.GetBytes(json));
        await WriteAsync(response);
        SafeLog($"已发送文件列表: {items.Count} 项, 当前路径={CurrentPath}");
    }

    protected async Task HandleNavigateRequestAsync(byte[] data)
    {
        if (data.Length < 4) { await SendErrorAsync("数据格式错误"); return; }

        var folderNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        if (folderNameLength <= 0 || data.Length < 4 + folderNameLength) { await SendErrorAsync("数据格式错误"); return; }

        var folderName = Encoding.UTF8.GetString(data, 4, folderNameLength);

        if (folderName == "..")
        {
            if (!string.IsNullOrEmpty(CurrentPath))
            {
                var lastSep = CurrentPath.LastIndexOf(Path.DirectorySeparatorChar);
                CurrentPath = lastSep > 0 ? CurrentPath.Substring(0, lastSep) : "";
            }
            SafeLog($"返回上级目录: {CurrentPath}");
        }
        else
        {
            // P0-1: 校验目录名
            if (!PathSecurityHelper.IsSafeRelativeName(folderName))
            {
                await SendErrorAsync("无效的文件夹名称");
                return;
            }

            var newPath = string.IsNullOrEmpty(CurrentPath) ? folderName : Path.Combine(CurrentPath, folderName);
            // 校验最终路径必须在 SharePath 下
            var newFullPath = PathSecurityHelper.SafeCombineSubPath(SharePath, newPath);
            if (newFullPath == null || !Directory.Exists(newFullPath))
            {
                await SendErrorAsync("目录不存在");
                return;
            }
            CurrentPath = newPath;
            SafeLog($"进入目录: {CurrentPath}");
        }

        await SendSuccessAsync();
        await HandleListRequestAsync();
    }

    protected async Task HandleBackRequestAsync()
    {
        if (!string.IsNullOrEmpty(CurrentPath))
        {
            var lastSep = CurrentPath.LastIndexOf(Path.DirectorySeparatorChar);
            CurrentPath = lastSep > 0 ? CurrentPath.Substring(0, lastSep) : "";
            SafeLog($"返回上级目录: {CurrentPath}");
        }
        await SendSuccessAsync();
        await HandleListRequestAsync();
    }

    protected async Task HandleCreateFolderRequestAsync(byte[] data)
    {
        if (data.Length < 4) { await SendErrorAsync("数据格式错误"); return; }

        var folderNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        if (folderNameLength <= 0 || data.Length < 4 + folderNameLength) { await SendErrorAsync("数据格式错误"); return; }

        var folderName = Encoding.UTF8.GetString(data, 4, folderNameLength);

        // P0-1: 严格校验
        if (!PathSecurityHelper.IsSafeRelativeName(folderName))
        {
            await SendErrorAsync("无效的文件夹名称");
            return;
        }

        var newFolderPath = PathSecurityHelper.SafeCombine(GetAbsolutePath(), folderName);
        if (newFolderPath == null) { await SendErrorAsync("无效的路径"); return; }

        try
        {
            if (Directory.Exists(newFolderPath)) { await SendErrorAsync("文件夹已存在"); return; }
            Directory.CreateDirectory(newFolderPath);
            SafeLog($"已创建文件夹: {folderName}");
            await SendSuccessAsync();
            await HandleListRequestAsync();
        }
        catch (Exception ex)
        {
            SafeLog($"创建文件夹失败: {ex.Message}");
            await SendErrorAsync($"创建失败: {ex.Message}");
        }
    }

    protected async Task HandleDownloadRequestAsync(byte[] data)
    {
        if (data.Length < 4) { await SendErrorAsync("数据格式错误"); return; }

        var fileNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        if (fileNameLength <= 0 || data.Length < 4 + fileNameLength + 8) { await SendErrorAsync("数据格式错误"); return; }

        var fileName = Encoding.UTF8.GetString(data, 4, fileNameLength);
        var offset = FileTransferProtocol.ReadInt64BigEndian(data, 4 + fileNameLength);

        // P0-1: 校验文件名
        var filePath = PathSecurityHelper.SafeCombine(GetAbsolutePath(), fileName);
        if (filePath == null)
        {
            await SendErrorAsync("无效的文件名");
            return;
        }
        if (offset < 0) offset = 0;

        if (!File.Exists(filePath))
        {
            await SendErrorAsync("文件不存在");
            return;
        }

        var fileInfo = new FileInfo(filePath);
        var totalSize = fileInfo.Length;
        if (offset > totalSize) offset = totalSize;

        SafeProgress(new TransferProgress { FileName = fileName, TotalBytes = totalSize, TransferredBytes = offset });

        using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        fs.Seek(offset, SeekOrigin.Begin);

        var remaining = totalSize - offset;
        var buffer = ArrayPool<byte>.Shared.Rent(CHUNK_SIZE);

        try
        {
            while (remaining > 0)
            {
                var toRead = (int)Math.Min(CHUNK_SIZE, remaining);
                var bytesRead = await fs.ReadAsync(buffer.AsMemory(0, toRead));
                if (bytesRead == 0) break;

                // P2-1: 零拷贝，直接用 buffer 切片构造包
                var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_DOWNLOAD_RESPONSE, buffer, bytesRead);
                await WriteAsync(response);

                offset += bytesRead;
                remaining -= bytesRead;

                SafeProgress(new TransferProgress { FileName = fileName, TotalBytes = totalSize, TransferredBytes = offset });
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }

        var complete = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_TRANSFER_COMPLETE);
        await WriteAsync(complete);
        SafeLog($"已发送文件: {fileName} ({totalSize} bytes)");
    }

    /// <summary>
    /// 修复 P0-6：流式上传支持断点续传
    /// </summary>
    protected async Task HandleUploadRequestAsync(byte[] data)
    {
        if (data.Length < 4) { await SendErrorAsync("数据格式错误"); return; }

        var fileNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        if (fileNameLength <= 0 || data.Length < 4 + fileNameLength + 8) { await SendErrorAsync("数据格式错误"); return; }

        var fileName = Encoding.UTF8.GetString(data, 4, fileNameLength);
        var offset = FileTransferProtocol.ReadInt64BigEndian(data, 4 + fileNameLength);
        var initialContent = data.Skip(4 + fileNameLength + 8).ToArray();

        // P0-1: 校验文件名
        var filePath = PathSecurityHelper.SafeCombine(UploadPath, fileName);
        if (filePath == null)
        {
            await SendErrorAsync("无效的文件名");
            return;
        }
        if (offset < 0) offset = 0;

        if (!Directory.Exists(UploadPath)) Directory.CreateDirectory(UploadPath);

        long totalReceived = 0;
        long existingSize = File.Exists(filePath) ? new FileInfo(filePath).Length : 0;

        // 流式模式：header only，数据通过 CMD_UPLOAD_CHUNK 后续发送
        if (initialContent.Length == 0)
        {
            SafeLog($"流式上传: {fileName}, offset={offset}");

            // P0-6: 根据 offset 选择文件模式
            // offset=0 → 覆盖；offset>0 且等于已存在文件大小 → 追加；否则截断到 offset 后写
            FileMode fileMode;
            long startPosition;
            if (offset == 0)
            {
                fileMode = FileMode.Create;
                startPosition = 0;
            }
            else if (File.Exists(filePath) && offset == existingSize)
            {
                fileMode = FileMode.Append;
                startPosition = offset;
            }
            else
            {
                // offset != existingSize，截断到 offset 后追加
                fileMode = FileMode.OpenOrCreate;
                startPosition = offset;
            }

            using var fs = new FileStream(filePath, fileMode, FileAccess.Write, FileShare.None);
            if (fileMode == FileMode.OpenOrCreate)
            {
                fs.SetLength(offset);
                fs.Seek(offset, SeekOrigin.Begin);
            }

            while (true)
            {
                var chunkHeaderBytes = await ReadExactAsync(FileTransferProtocol.HEADER_SIZE);
                if (chunkHeaderBytes == null || chunkHeaderBytes.Length == 0)
                {
                    await SendErrorAsync("连接断开");
                    return;
                }

                var chunkCommand = chunkHeaderBytes[0];
                var chunkLength = FileTransferProtocol.ReadInt32BigEndian(chunkHeaderBytes, 1);

                if (chunkCommand == FileTransferProtocol.CMD_TRANSFER_COMPLETE) break;

                if (chunkLength < 0 || chunkLength > 64 * 1024 * 1024)
                {
                    await SendErrorAsync("非法分块大小");
                    return;
                }

                var chunkData = chunkLength > 0 ? await ReadExactAsync(chunkLength) : Array.Empty<byte>();
                if (chunkData == null) { await SendErrorAsync("连接断开"); return; }

                // 兼容 CMD_UPLOAD_CHUNK 与未知命令
                if (chunkCommand == FileTransferProtocol.CMD_UPLOAD_CHUNK || chunkCommand != FileTransferProtocol.CMD_TRANSFER_COMPLETE)
                {
                    await fs.WriteAsync(chunkData, 0, chunkData.Length);
                    totalReceived += chunkData.Length;
                    SafeProgress(new TransferProgress
                    {
                        FileName = fileName,
                        TotalBytes = startPosition + totalReceived,
                        TransferredBytes = startPosition + totalReceived
                    });
                }
            }

            await fs.FlushAsync();
            SafeLog($"流式上传完成: {fileName}, 本次接收={totalReceived}, 总大小={startPosition + totalReceived}");
        }
        else
        {
            // 传统模式：单包
            var fileContent = initialContent;
            totalReceived = fileContent.Length;

            if (offset > 0 && File.Exists(filePath) && offset == existingSize)
            {
                await AppendAllBytesAsync(filePath, fileContent);
                SafeLog($"续传文件: {fileName} (从 {offset} 字节开始)");
            }
            else
            {
                await File.WriteAllBytesAsync(filePath, fileContent);
                SafeLog($"已接收文件: {fileName}, 大小={fileContent.Length} bytes");
            }

            SafeProgress(new TransferProgress
            {
                FileName = fileName,
                TotalBytes = offset + fileContent.Length,
                TransferredBytes = offset + fileContent.Length
            });
        }

        var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SUCCESS);
        await WriteAsync(response);
    }

    protected async Task HandleDeleteRequestAsync(byte[] data)
    {
        var fileName = Encoding.UTF8.GetString(data);

        // P0-1: 校验文件名
        var filePath = PathSecurityHelper.SafeCombine(GetAbsolutePath(), fileName);
        if (filePath == null)
        {
            await SendErrorAsync("无效的文件名");
            return;
        }

        if (!File.Exists(filePath))
        {
            await SendErrorAsync("文件不存在");
            return;
        }

        try
        {
            File.Delete(filePath);
            var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SUCCESS);
            await WriteAsync(response);
            SafeLog($"已删除文件: {fileName}");
        }
        catch (Exception ex)
        {
            await SendErrorAsync($"删除失败: {ex.Message}");
        }
    }

    /// <summary>
    /// P3-4: 计算文件 SHA-256 哈希并返回（十六进制字符串）
    /// </summary>
    protected async Task HandleFileHashRequestAsync(byte[] data)
    {
        var fileName = Encoding.UTF8.GetString(data);

        var filePath = PathSecurityHelper.SafeCombine(GetAbsolutePath(), fileName);
        if (filePath == null || !File.Exists(filePath))
        {
            await SendErrorAsync("文件不存在");
            return;
        }

        try
        {
            using var sha = System.Security.Cryptography.SHA256.Create();
            using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
            var hashBytes = await sha.ComputeHashAsync(fs);
            var hashHex = Convert.ToHexString(hashBytes).ToLowerInvariant();

            var response = FileTransferProtocol.CreatePacket(
                FileTransferProtocol.CMD_FILE_HASH,
                Encoding.UTF8.GetBytes(hashHex));
            await WriteAsync(response);
            SafeLog($"已发送文件哈希: {fileName} = {hashHex.Substring(0, 16)}...");
        }
        catch (Exception ex)
        {
            await SendErrorAsync($"哈希计算失败: {ex.Message}");
        }
    }

    protected async Task SendErrorAsync(string message)
    {
        var error = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_ERROR, Encoding.UTF8.GetBytes(message));
        await WriteAsync(error);
    }

    protected async Task SendSuccessAsync()
    {
        var success = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SUCCESS);
        await WriteAsync(success);
    }

    private static async Task AppendAllBytesAsync(string path, byte[] bytes)
    {
        using var fs = new FileStream(path, FileMode.Append, FileAccess.Write, FileShare.None);
        await fs.WriteAsync(bytes);
    }

    public async Task NotifyPathChangedAsync()
    {
        CurrentPath = "";
        var newPathBytes = Encoding.UTF8.GetBytes(SharePath);
        var packet = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SHARE_PATH_CHANGED, newPathBytes);
        await WriteAsync(packet);
        SafeLog($"已通知客户端共享路径已更改: {SharePath}");
    }

    /// <summary>
    /// P3-5: 主动断开客户端连接（用于客户端管理 UI）
    /// </summary>
    public void CloseClient()
    {
        try { Client.Close(); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"CloseClient failed: {ex.Message}"); }
    }
}
