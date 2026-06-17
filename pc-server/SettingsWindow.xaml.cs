using System.Diagnostics;
using System.IO;
using System.Windows;
using BluetoothFileServer.Services;

namespace BluetoothFileServer;

/// <summary>
/// P3-5: PC 端设置页 - 高级配置（最大连接数、自动启动、日志/配置入口）
/// </summary>
public partial class SettingsWindow : Window
{
    private readonly AppConfig _config;

    public bool SettingsChanged { get; private set; } = false;

    public SettingsWindow(AppConfig config, string sharePath, string uploadPath)
    {
        InitializeComponent();
        _config = config;

        MaxConnectionsTextBox.Text = _config.MaxConnections.ToString();
        AutoStartServersCheckBox.IsChecked = _config.AutoStartServers;

        var version = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version;
        VersionText.Text = $"版本: {version?.ToString() ?? "1.0.0"}";

        // 暂存路径供按钮使用
        Tag = new SettingsContext
        {
            SharePath = sharePath,
            UploadPath = uploadPath
        };
    }

    private void OpenShareFolder_Click(object sender, RoutedEventArgs e)
    {
        var ctx = (SettingsContext)Tag!;
        if (Directory.Exists(ctx.SharePath))
            Process.Start(new ProcessStartInfo("explorer.exe", ctx.SharePath) { UseShellExecute = true });
        else
            MessageBox.Show("共享目录不存在", "提示", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    private void OpenUploadFolder_Click(object sender, RoutedEventArgs e)
    {
        var ctx = (SettingsContext)Tag!;
        if (Directory.Exists(ctx.UploadPath))
            Process.Start(new ProcessStartInfo("explorer.exe", ctx.UploadPath) { UseShellExecute = true });
        else
            MessageBox.Show("上传目录不存在", "提示", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    private void OpenLog_Click(object sender, RoutedEventArgs e)
    {
        var logPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BluLink", "bluelink.log");
        var dir = Path.GetDirectoryName(logPath);
        if (Directory.Exists(dir))
        {
            if (File.Exists(logPath))
                Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{logPath}\"") { UseShellExecute = true });
            else
                Process.Start(new ProcessStartInfo("explorer.exe", dir!) { UseShellExecute = true });
        }
        else
        {
            MessageBox.Show("日志目录不存在", "提示", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void OpenConfig_Click(object sender, RoutedEventArgs e)
    {
        var configDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BluLink");
        if (Directory.Exists(configDir))
            Process.Start(new ProcessStartInfo("explorer.exe", configDir) { UseShellExecute = true });
        else
            MessageBox.Show("配置目录不存在", "提示", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        // 校验最大连接数
        if (!int.TryParse(MaxConnectionsTextBox.Text, out int maxConn) || maxConn < 1 || maxConn > 50)
        {
            MessageBox.Show("最大并发连接数必须是 1-50 之间的整数", "输入错误",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        bool changed = false;
        if (_config.MaxConnections != maxConn)
        {
            _config.MaxConnections = maxConn;
            changed = true;
        }
        var autoStart = AutoStartServersCheckBox.IsChecked == true;
        if (_config.AutoStartServers != autoStart)
        {
            _config.AutoStartServers = autoStart;
            changed = true;
        }

        if (changed)
        {
            _config.Save();
            SettingsChanged = true;
        }

        DialogResult = true;
        Close();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private class SettingsContext
    {
        public string SharePath { get; set; } = "";
        public string UploadPath { get; set; } = "";
    }
}
