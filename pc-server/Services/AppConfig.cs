using System.IO;
using System.Text.Json;

namespace BluetoothFileServer.Services;

/// <summary>
/// P1-6: 应用配置持久化（共享目录、上传目录、端口等）
/// </summary>
public class AppConfig
{
    public string SharePath { get; set; } = "";
    public string UploadPath { get; set; } = "";
    public int TcpPort { get; set; } = 9000;
    public bool AutoStartServers { get; set; } = true;
    public bool AutoStartWithWindows { get; set; } = false;
    public int MaxConnections { get; set; } = 5;

    private static readonly string ConfigDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "BluLink");
    private static readonly string ConfigPath = Path.Combine(ConfigDir, "config.json");

    public static AppConfig Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                var json = File.ReadAllText(ConfigPath);
                var cfg = JsonSerializer.Deserialize<AppConfig>(json);
                if (cfg != null)
                {
                    // 默认值兜底
                    if (string.IsNullOrWhiteSpace(cfg.SharePath))
                        cfg.SharePath = GetDefaultSharePath();
                    if (string.IsNullOrWhiteSpace(cfg.UploadPath))
                        cfg.UploadPath = Path.Combine(GetDefaultSharePath(), "upload");
                    return cfg;
                }
            }
        }
        catch { }

        return new AppConfig
        {
            SharePath = GetDefaultSharePath(),
            UploadPath = Path.Combine(GetDefaultSharePath(), "upload")
        };
    }

    public void Save()
    {
        try
        {
            if (!Directory.Exists(ConfigDir)) Directory.CreateDirectory(ConfigDir);
            var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(ConfigPath, json);
        }
        catch { }
    }

    /// <summary>
    /// 默认共享目录：用户目录下的 BluLink 文件夹（替代硬编码 E:\）
    /// </summary>
    public static string GetDefaultSharePath()
    {
        var user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var path = Path.Combine(user, "BluLink");
        if (!Directory.Exists(path)) Directory.CreateDirectory(path);
        return path;
    }
}
