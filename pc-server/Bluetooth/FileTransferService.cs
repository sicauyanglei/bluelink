using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows.Threading;
using BluetoothFileServer.Models;
using BluetoothFileServer.Protocols;

namespace BluetoothFileServer.Bluetooth;

public class FileTransferService
{
    private readonly string _sharePath;
    private readonly string _uploadPath;
    private string _currentPath = "";  // Relative path from sharePath
    private readonly BluetoothConnectedClient _client;
    private readonly Dispatcher _dispatcher;
    private const int CHUNK_SIZE = 32768; // 32KB chunks for faster transfer

    public event EventHandler<string>? LogReceived;
    public event EventHandler<TransferProgress>? ProgressChanged;

    public FileTransferService(string sharePath, string uploadPath, BluetoothConnectedClient client, Dispatcher dispatcher)
    {
        _sharePath = sharePath;
        _uploadPath = uploadPath;
        _client = client;
        _dispatcher = dispatcher;
    }

    // Get absolute path of current directory
    private string GetAbsolutePath() => string.IsNullOrEmpty(_currentPath) ? _sharePath : Path.Combine(_sharePath, _currentPath);

    private void SafeLog(string msg)
    {
        try
        {
            _dispatcher.BeginInvoke(() => LogReceived?.Invoke(this, msg));
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"SafeLog failed: {ex}");
        }
    }

    private void SafeProgress(TransferProgress progress)
    {
        _dispatcher.BeginInvoke(() => ProgressChanged?.Invoke(this, progress));
    }

    public async Task HandleClientAsync()
    {
        var startTime = DateTime.Now;
        SafeLog($"[HandleClientAsync] START at {startTime:HH:mm:ss.fff}");
        try
        {
            SafeLog("开始处理客户端...");
            int loopCount = 0;
            while (true)
            {
                loopCount++;
                var loopStart = DateTime.Now;
                SafeLog($"[Loop #{loopCount}] 开始 at {loopStart:HH:mm:ss.fff}");
                try
                {
                    // Read protocol header (5 bytes: 1 byte command + 4 bytes length)
                    SafeLog($"[Loop #{loopCount}] 等待读取header...");
                    var headerBytes = await ReadExactAsync(5);
                    var headerTime = DateTime.Now;
                    SafeLog($"[Loop #{loopCount}] header读取完成 at {headerTime:HH:mm:ss.fff}, elapsed={headerTime-loopStart:mm\\:ss\\.fff}, result={(headerBytes == null ? "null" : headerBytes.Length + " bytes")}");
                    if (headerBytes == null || headerBytes.Length == 0) {
                        SafeLog("连接已断开，退出循环");
                        break;
                    }

                    var command = headerBytes[0];
                    var length = FileTransferProtocol.ReadInt32BigEndian(headerBytes, 1);
                    SafeLog($"[Loop #{loopCount}] 命令={command}, 长度={length}");

                    // Read data based on length
                    var dataStart = DateTime.Now;
                    var data = length > 0 ? await ReadExactAsync(length) : Array.Empty<byte>();
                    var dataTime = DateTime.Now;
                    SafeLog($"[Loop #{loopCount}] 数据读取完成 at {dataTime:HH:mm:ss.fff}, elapsed={dataTime-dataStart:mm\\:ss\\.fff}");
                    if (data == null) {
                        SafeLog("数据读取失败，退出循环");
                        break;
                    }

                    await ProcessCommandAsync(command, data);
                }
                catch (Exception ex)
                {
                    SafeLog($"[Loop #{loopCount}] 处理异常: {ex.Message}");
                    break;
                }
            }
            var endTime = DateTime.Now;
            SafeLog($"[HandleClientAsync] END at {endTime:HH:mm:ss.fff}, total={endTime-startTime:mm\\:ss\\.fff}");
        }
        catch (Exception ex)
        {
            SafeLog($"严重错误: {ex.Message}");
        }
    }

    private async Task<byte[]?> ReadExactAsync(int count)
    {
        var readStart = DateTime.Now;
        SafeLog($"[ReadExact] START at {readStart:HH:mm:ss.fff}, count={count}");
        var buffer = new byte[count];
        var totalRead = 0;
        int readAttempts = 0;
        while (totalRead < count)
        {
            readAttempts++;
            var attemptStart = DateTime.Now;
            SafeLog($"[ReadExact] Attempt #{readAttempts} at {attemptStart:HH:mm:ss.fff}, waiting for {count - totalRead} bytes...");
            var read = await _client.ReadAsync(buffer, totalRead, count - totalRead);
            var attemptEnd = DateTime.Now;
            SafeLog($"[ReadExact] Attempt #{readAttempts} completed at {attemptEnd:HH:mm:ss.fff}, elapsed={attemptEnd-attemptStart:mm\\:ss\\.fff}, read={read}, totalRead={totalRead}/{count}");
            if (read <= 0) {
                SafeLog($"[ReadExact] Read returned {read}, connection may be closed");
                return null;
            }
            totalRead += read;
        }
        var successEnd = DateTime.Now;
        SafeLog($"[ReadExact] SUCCESS at {successEnd:HH:mm:ss.fff}, total elapsed={successEnd-readStart:mm\\:ss\\.fff}");
        return buffer;
    }

    private async Task WriteAsync(byte[] data)
    {
        var writeStart = DateTime.Now;
        SafeLog($"[WriteAsync] START at {writeStart:HH:mm:ss.fff}, length={data.Length}");
        await _client.WriteAsync(data, 0, data.Length);
        var writeEnd = DateTime.Now;
        SafeLog($"[WriteAsync] END at {writeEnd:HH:mm:ss.fff}, elapsed={writeEnd-writeStart:mm\\:ss\\.fff}");
    }

    private async Task ProcessCommandAsync(byte command, byte[] data)
    {
        switch (command)
        {
            case FileTransferProtocol.CMD_LIST_REQUEST:
                await HandleListRequestAsync();
                break;
            case FileTransferProtocol.CMD_DOWNLOAD_REQUEST:
                await HandleDownloadRequestAsync(data);
                break;
            case FileTransferProtocol.CMD_UPLOAD_REQUEST:
                await HandleUploadRequestAsync(data);
                break;
            case FileTransferProtocol.CMD_DELETE_REQUEST:
                await HandleDeleteRequestAsync(data);
                break;
            case FileTransferProtocol.CMD_NAVIGATE_REQUEST:
                await HandleNavigateRequestAsync(data);
                break;
            case FileTransferProtocol.CMD_BACK_REQUEST:
                await HandleBackRequestAsync();
                break;
            case FileTransferProtocol.CMD_CREATE_FOLDER_REQUEST:
                await HandleCreateFolderRequestAsync(data);
                break;
        }
    }

    private async Task HandleListRequestAsync()
    {
        var items = new List<FileItem>();
        var currentDir = GetAbsolutePath();

        // Add parent directory indicator if not at root
        if (!string.IsNullOrEmpty(_currentPath))
        {
            items.Add(new FileItem
            {
                Name = "..",
                Size = 0,
                ModifiedTime = DateTime.MinValue,
                IsDirectory = true,
                IsParentDirectory = true
            });
        }

        // Get directories
        if (Directory.Exists(currentDir))
        {
            var dirs = Directory.GetDirectories(currentDir)
                .Select(d => new DirectoryInfo(d))
                .Select(di => new FileItem
                {
                    Name = di.Name,
                    Size = 0,
                    ModifiedTime = di.LastWriteTime,
                    IsDirectory = true
                })
                .ToList();
            items.AddRange(dirs);
        }

        // Get files
        var files = Directory.GetFiles(currentDir)
            .Select(f => new FileInfo(f))
            .Select(fi => new FileItem
            {
                Name = fi.Name,
                Size = fi.Length,
                ModifiedTime = fi.LastWriteTime,
                IsDirectory = false
            })
            .ToList();
        items.AddRange(files);

        var responseData = new
        {
            CurrentPath = _currentPath,
            Items = items
        };

        var json = JsonSerializer.Serialize(responseData);
        var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_LIST_RESPONSE, Encoding.UTF8.GetBytes(json));
        await WriteAsync(response);
        SafeLog($"已发送文件列表: {items.Count} 项, 当前路径={_currentPath}");
    }

    private async Task HandleNavigateRequestAsync(byte[] data)
    {
        // Data format: folder name length(4 bytes) + folder name
        if (data.Length < 4)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var folderNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        if (data.Length < 4 + folderNameLength)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var folderName = Encoding.UTF8.GetString(data, 4, folderNameLength);

        if (folderName == "..")
        {
            // Go back to parent
            if (!string.IsNullOrEmpty(_currentPath))
            {
                var lastSep = _currentPath.LastIndexOf(Path.DirectorySeparatorChar);
                if (lastSep > 0)
                {
                    _currentPath = _currentPath.Substring(0, lastSep);
                }
                else
                {
                    _currentPath = "";
                }
            }
            SafeLog($"返回上级目录: {_currentPath}");
        }
        else
        {
            // Navigate into subdirectory
            var newPath = string.IsNullOrEmpty(_currentPath) ? folderName : Path.Combine(_currentPath, folderName);
            var newFullPath = Path.Combine(_sharePath, newPath);

            if (!Directory.Exists(newFullPath))
            {
                await SendErrorAsync("目录不存在");
                return;
            }

            _currentPath = newPath;
            SafeLog($"进入目录: {_currentPath}");
        }

        // Send success and then the new list
        await SendSuccessAsync();
        await HandleListRequestAsync();
    }

    private async Task HandleBackRequestAsync()
    {
        if (!string.IsNullOrEmpty(_currentPath))
        {
            var lastSep = _currentPath.LastIndexOf(Path.DirectorySeparatorChar);
            if (lastSep > 0)
            {
                _currentPath = _currentPath.Substring(0, lastSep);
            }
            else
            {
                _currentPath = "";
            }
            SafeLog($"返回上级目录: {_currentPath}");
        }

        await SendSuccessAsync();
        await HandleListRequestAsync();
    }

    private async Task HandleCreateFolderRequestAsync(byte[] data)
    {
        // Data format: folder name length(4 bytes) + folder name
        if (data.Length < 4)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var folderNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        if (data.Length < 4 + folderNameLength)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var folderName = Encoding.UTF8.GetString(data, 4, folderNameLength);

        // Validate folder name
        if (string.IsNullOrWhiteSpace(folderName) || folderName.Contains(Path.DirectorySeparatorChar) || folderName.Contains(Path.AltDirectorySeparatorChar))
        {
            await SendErrorAsync("无效的文件夹名称");
            return;
        }

        var newFolderPath = Path.Combine(GetAbsolutePath(), folderName);

        try
        {
            if (Directory.Exists(newFolderPath))
            {
                await SendErrorAsync("文件夹已存在");
                return;
            }

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

    private async Task HandleDownloadRequestAsync(byte[] data)
    {
        // Data format: filename length(4 bytes) + filename + offset(8 bytes)
        if (data.Length < 4)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var fileNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        if (data.Length < 4 + fileNameLength)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var fileName = Encoding.UTF8.GetString(data, 4, fileNameLength);
        var offset = FileTransferProtocol.ReadInt64BigEndian(data, 4 + fileNameLength);
        var filePath = Path.Combine(GetAbsolutePath(), fileName);

        if (!File.Exists(filePath))
        {
            var error = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_ERROR, Encoding.UTF8.GetBytes("文件不存在"));
            await WriteAsync(error);
            return;
        }

        var fileInfo = new FileInfo(filePath);
        var totalSize = fileInfo.Length;

        // Report progress
        SafeProgress(new TransferProgress
        {
            FileName = fileName,
            TotalBytes = totalSize,
            TransferredBytes = offset
        });

        // Open file and seek to offset
        using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        fs.Seek(offset, SeekOrigin.Begin);

        var remaining = totalSize - offset;
        var buffer = new byte[CHUNK_SIZE];

        while (remaining > 0)
        {
            var toRead = (int)Math.Min(CHUNK_SIZE, remaining);
            var bytesRead = await fs.ReadAsync(buffer.AsMemory(0, toRead));

            if (bytesRead == 0) break;

            // Send chunk with header
            var chunkData = new byte[bytesRead];
            Array.Copy(buffer, chunkData, bytesRead);
            var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_DOWNLOAD_RESPONSE, chunkData);
            await WriteAsync(response);

            offset += bytesRead;
            remaining -= bytesRead;

            SafeProgress(new TransferProgress
            {
                FileName = fileName,
                TotalBytes = totalSize,
                TransferredBytes = offset
            });
        }

        // Send transfer complete signal
        var complete = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_TRANSFER_COMPLETE);
        await WriteAsync(complete);

        SafeLog($"已发送文件: {fileName} ({totalSize} bytes) [完成信号已发送]");
    }

    private async Task HandleUploadRequestAsync(byte[] data)
    {
        // Data format: filename length(4 bytes) + filename + offset(8 bytes) + content
        if (data.Length < 4)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var fileNameLength = FileTransferProtocol.ReadInt32BigEndian(data, 0);
        SafeLog($"BLUETOOTH UPLOAD: data.Length={data.Length}, fileNameLength={fileNameLength}");
        if (data.Length < 4 + fileNameLength + 8)
        {
            await SendErrorAsync("数据格式错误");
            return;
        }

        var fileName = Encoding.UTF8.GetString(data, 4, fileNameLength);
        var offset = FileTransferProtocol.ReadInt64BigEndian(data, 4 + fileNameLength);
        var initialContent = data.Skip(4 + fileNameLength + 8).ToArray();
        SafeLog($"BLUETOOTH UPLOAD: fileName={fileName}, offset={offset}, initialContent.Length={initialContent.Length}");
        var filePath = Path.Combine(_uploadPath, fileName);

        // Ensure upload directory exists
        if (!Directory.Exists(_uploadPath))
        {
            Directory.CreateDirectory(_uploadPath);
        }

        // Open file stream for writing (support streaming upload)
        long totalReceived = 0;
        long existingSize = File.Exists(filePath) ? new FileInfo(filePath).Length : 0;

        // If initial content is empty, this might be streaming mode (header only, data follows separately)
        if (initialContent.Length == 0 && offset == 0)
        {
            // Streaming mode: data comes in separate packets, need to loop until CMD_TRANSFER_COMPLETE
            SafeLog($"BLUETOOTH UPLOAD: streaming mode detected, waiting for data chunks...");

            using var fs = new FileStream(filePath, FileMode.Create, FileAccess.Write, FileShare.None);
            long expectedOffset = 0;

            // Loop to receive data chunks until CMD_TRANSFER_COMPLETE
            while (true)
            {
                // Read next packet
                var chunkHeaderBytes = await ReadExactAsync(5);
                if (chunkHeaderBytes == null || chunkHeaderBytes.Length == 0)
                {
                    SafeLog($"BLUETOOTH UPLOAD: connection closed while waiting for chunk");
                    await SendErrorAsync("连接断开");
                    return;
                }

                var chunkCommand = chunkHeaderBytes[0];
                var chunkLength = FileTransferProtocol.ReadInt32BigEndian(chunkHeaderBytes, 1);

                SafeLog($"BLUETOOTH UPLOAD: chunk cmd={chunkCommand}, len={chunkLength}");

                // Check for transfer complete signal
                if (chunkCommand == FileTransferProtocol.CMD_TRANSFER_COMPLETE)
                {
                    SafeLog($"BLUETOOTH UPLOAD: transfer complete signal received");
                    break;
                }

                // Check for upload chunk (streaming mode data chunk)
                if (chunkCommand == FileTransferProtocol.CMD_UPLOAD_CHUNK)
                {
                    // Read chunk data
                    var chunkData = chunkLength > 0 ? await ReadExactAsync(chunkLength) : Array.Empty<byte>();
                    if (chunkData == null)
                    {
                        SafeLog($"BLUETOOTH UPLOAD: connection closed while reading chunk");
                        await SendErrorAsync("连接断开");
                        return;
                    }

                    // Verify offset matches expected
                    if (fs.Position != expectedOffset)
                    {
                        SafeLog($"BLUETOOTH UPLOAD: offset mismatch, expected={expectedOffset}, actual={fs.Position}");
                    }

                    await fs.WriteAsync(chunkData, 0, chunkData.Length);
                    totalReceived += chunkData.Length;
                    expectedOffset += chunkData.Length;

                    SafeProgress(new TransferProgress
                    {
                        FileName = fileName,
                        TotalBytes = 0, // Unknown total for streaming
                        TransferredBytes = totalReceived
                    });
                    continue;
                }

                // Unknown command - treat as data chunk for backward compatibility
                var unknownChunkData = chunkLength > 0 ? await ReadExactAsync(chunkLength) : Array.Empty<byte>();
                if (unknownChunkData == null)
                {
                    SafeLog($"BLUETOOTH UPLOAD: connection closed while reading data");
                    await SendErrorAsync("连接断开");
                    return;
                }

                if (fs.Position != expectedOffset)
                {
                    SafeLog($"BLUETOOTH UPLOAD: offset mismatch, expected={expectedOffset}, actual={fs.Position}");
                }

                await fs.WriteAsync(unknownChunkData, 0, unknownChunkData.Length);
                totalReceived += unknownChunkData.Length;
                expectedOffset += unknownChunkData.Length;

                SafeProgress(new TransferProgress
                {
                    FileName = fileName,
                    TotalBytes = 0,
                    TransferredBytes = totalReceived
                });
            }

            SafeLog($"BLUETOOTH UPLOAD: streaming complete, totalReceived={totalReceived}");
        }
        else
        {
            // Traditional mode: all content in one packet
            var fileContent = initialContent;
            totalReceived = fileContent.Length;
            var totalSize = offset + fileContent.Length;

            // Append mode if offset matches existing file size (resume upload)
            // Otherwise overwrite
            if (offset > 0 && File.Exists(filePath) && offset == existingSize)
            {
                // Resume: append to existing file
                await AppendAllBytesAsync(filePath, fileContent);
                SafeLog($"续传文件: {fileName} (从 {offset} 字节开始)");
            }
            else
            {
                // New upload or overwrite
                await File.WriteAllBytesAsync(filePath, fileContent);
                SafeLog($"已接收文件: {fileName}, 大小={fileContent.Length} bytes");
            }

            SafeProgress(new TransferProgress
            {
                FileName = fileName,
                TotalBytes = totalSize,
                TransferredBytes = totalSize
            });
        }

        var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SUCCESS);
        await WriteAsync(response);
    }

    private async Task HandleDeleteRequestAsync(byte[] data)
    {
        var fileName = Encoding.UTF8.GetString(data);
        var filePath = Path.Combine(GetAbsolutePath(), fileName);

        if (!File.Exists(filePath))
        {
            await SendErrorAsync("文件不存在");
            return;
        }

        File.Delete(filePath);
        var response = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SUCCESS);
        await WriteAsync(response);
        SafeLog($"已删除文件: {fileName}");
    }

    private async Task SendErrorAsync(string message)
    {
        var error = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_ERROR, Encoding.UTF8.GetBytes(message));
        await WriteAsync(error);
    }

    private async Task SendSuccessAsync()
    {
        var success = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SUCCESS);
        await WriteAsync(success);
    }

    private static async Task AppendAllBytesAsync(string path, byte[] bytes)
    {
        using var fs = new FileStream(path, FileMode.Append, FileAccess.Write, FileShare.None);
        await fs.WriteAsync(bytes);
    }

    // Notify client that share path has changed - reset to root and refresh
    public async Task NotifyPathChangedAsync()
    {
        _currentPath = "";
        var newPathBytes = Encoding.UTF8.GetBytes(_sharePath);
        var packet = FileTransferProtocol.CreatePacket(FileTransferProtocol.CMD_SHARE_PATH_CHANGED, newPathBytes);
        await WriteAsync(packet);
        SafeLog($"已通知客户端共享路径已更改: {_sharePath}");
    }
}
