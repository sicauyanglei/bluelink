using System.Collections.Concurrent;
using System.IO;

namespace BluetoothFileServer.Services;

/// <summary>
/// P2-3: 异步日志写入器，避免阻塞调用线程
/// </summary>
public static class AsyncLogger
{
    private static readonly BlockingCollection<string> _queue = new(new ConcurrentQueue<string>());
    private static readonly Thread _writerThread;
    private static volatile bool _running = true;

    static AsyncLogger()
    {
        _writerThread = new Thread(WriterLoop) { IsBackground = true, Name = "AsyncLogger" };
        _writerThread.Start();
    }

    public static void Append(string filePath, string line)
    {
        if (!_running) return;
        try
        {
            // 路径作为前缀传入，与日志行一起入队
            _queue.Add($"{filePath}\u0001{line}");
        }
        catch { /* 队列关闭，忽略 */ }
    }

    private static void WriterLoop()
    {
        foreach (var item in _queue.GetConsumingEnumerable())
        {
            try
            {
                var idx = item.IndexOf('\u0001');
                if (idx <= 0) continue;
                var path = item.Substring(0, idx);
                var line = item.Substring(idx + 1);
                var dir = Path.GetDirectoryName(path);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                    Directory.CreateDirectory(dir);
                File.AppendAllText(path, line);
            }
            catch { }
        }
    }

    public static void Shutdown()
    {
        _running = false;
        _queue.CompleteAdding();
    }
}
