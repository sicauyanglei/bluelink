using System.IO;
using System.Net;
using System.Windows;
using System.Windows.Media;
using BluetoothFileServer.Services;
using BluetoothFileServer.Bluetooth;
using BluetoothFileServer.Tcp;
using InTheHand.Net.Bluetooth;
using System.Drawing;
using System.Drawing.Drawing2D;

namespace BluetoothFileServer;

public partial class MainWindow : Window
{
    private BluetoothServer? _bluetoothServer;
    private TcpServer? _tcpServer;
    private DiscoveryServer? _discoveryServer;
    private Hardcodet.Wpf.TaskbarNotification.TaskbarIcon? _trayIcon;
    private System.Drawing.Icon? _trayIconIdle;
    private System.Drawing.Icon? _trayIconBluetooth;
    private System.Drawing.Icon? _trayIconTcp;
    private System.Drawing.Icon? _trayIconBoth;
    private System.Drawing.Icon? _trayIconBluetoothConn;
    private System.Drawing.Icon? _trayIconTcpConn;
    private System.Drawing.Icon? _trayIconBothConn;
    private System.Windows.Controls.MenuItem? _trayMenuBluetooth;
    private System.Windows.Controls.MenuItem? _trayMenuTcp;
    private bool _isBluetoothRunning = false;
    private bool _isTcpRunning = false;
    private bool _isBluetoothConnected = false;
    private bool _isTcpConnected = false;
    private readonly List<object> _activeServices = new();
    private readonly object _servicesLock = new();
    private static readonly string LogFilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "BluLink",
        "bluelink.log");

    public MainWindow()
    {
        InitializeComponent();

        // Create tray icons programmatically
        CreateTrayIcons();

        // Ensure log directory exists
        var logDir = Path.GetDirectoryName(LogFilePath);
        if (!string.IsNullOrEmpty(logDir) && !Directory.Exists(logDir))
            Directory.CreateDirectory(logDir);

        LogToFile("[MainWindow] Application starting...");

        // Global exception handlers
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            var ex = e.ExceptionObject as Exception;
            LogToFile($"[UnhandledException] {ex?.GetType().Name}: {ex?.Message}\n{ex?.StackTrace}");
            Dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[致命] {ex?.GetType().Name}: {ex?.Message}"));
        };

        Application.Current.DispatcherUnhandledException += (_, e) =>
        {
            LogToFile($"[DispatcherException] {e.Exception.GetType().Name}: {e.Exception.Message}\n{e.Exception.StackTrace}");
            Dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[UI异常] {e.Exception.GetType().Name}: {e.Exception.Message}"));
            e.Handled = true;
        };

        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            LogToFile($"[TaskException] {e.Exception.GetType().Name}: {e.Exception.Message}\n{e.Exception.StackTrace}");
            Dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[Task异常] {e.Exception.GetType().Name}: {e.Exception.Message}"));
            e.SetObserved();
        };

        LogToFile("[MainWindow] Initialization complete");

        // Initialize Bluetooth server
        _bluetoothServer = new BluetoothServer();
        _bluetoothServer.ConnectionStatusChanged += (_, msg) => Dispatcher.Invoke(() => {
            BluetoothStatus.Text = msg;
            BluetoothStatusIndicator.Fill = msg.Contains("已连接") || msg.Contains("运行中")
                ? (SolidColorBrush)FindResource("SuccessBrush")
                : (SolidColorBrush)FindResource("AccentCyanBrush");
            _isBluetoothRunning = msg.Contains("运行中");
            UpdateTrayIcon();
        });
        _bluetoothServer.ClientConnected += OnBluetoothClientConnected;

        // Initialize TCP server
        _tcpServer = new TcpServer();
        _tcpServer.ConnectionStatusChanged += (_, msg) => Dispatcher.Invoke(() => {
            TcpStatus.Text = msg;
            TcpStatusIndicator.Fill = msg.Contains("已连接") || msg.Contains("运行中")
                ? (SolidColorBrush)FindResource("SuccessBrush")
                : (SolidColorBrush)FindResource("AccentCyanBrush");
            ConnectionStatusText.Text = msg.Contains("已连接") || msg.Contains("运行中") ? "已连接" : "未连接";
            StatusIndicator.Fill = msg.Contains("已连接") || msg.Contains("运行中")
                ? (SolidColorBrush)FindResource("SuccessBrush")
                : (SolidColorBrush)FindResource("TextMutedBrush");
            _isTcpRunning = msg.Contains("运行中");
            UpdateTrayIcon();
        });
        _tcpServer.ClientConnected += OnTcpClientConnected;

        // Initialize Discovery server (auto-start)
        _discoveryServer = new DiscoveryServer();
        _discoveryServer.DiscoveryStatusChanged += (_, msg) => Dispatcher.Invoke(() =>
        {
            System.Diagnostics.Debug.WriteLine($"[Discovery] {msg}");
            LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] {msg}");
        });
        _discoveryServer.StartDiscovery();

        System.Diagnostics.Debug.WriteLine("[MainWindow] Initialization complete");
        AutoStartCheckBox.IsChecked = AutoStartHelper.IsAutoStartEnabled();
        RefreshFileList();

        // Display local Bluetooth device info
        DisplayBluetoothDeviceInfo();

        // Check for --minimized argument (auto-start)
        var args = Environment.GetCommandLineArgs();
        if (args.Contains("--minimized"))
        {
            WindowState = WindowState.Minimized;
            ShowInTaskbar = false;
        }

        // Auto-start Bluetooth and TCP servers by default
        AutoStartServers();
    }

    private void CreateTrayIcons()
    {
        // Base colors
        var gray = System.Drawing.Color.FromArgb(158, 158, 158);
        var blue = System.Drawing.Color.FromArgb(33, 150, 243);
        var green = System.Drawing.Color.FromArgb(76, 175, 80);
        var teal = System.Drawing.Color.FromArgb(0, 150, 136);
        var white = System.Drawing.Color.FromArgb(255, 255, 255);
        var lightGray = System.Drawing.Color.FromArgb(238, 238, 238);

        // Idle icons (no services running)
        _trayIconIdle = CreateTrayIconImage(gray, lightGray, gray);

        // Service running icons (base service status, no phone connected)
        _trayIconBluetooth = CreateTrayIconImage(blue, white, blue);
        _trayIconTcp = CreateTrayIconImage(green, white, green);
        _trayIconBoth = CreateTrayIconImage(teal, white, teal);

        // Connected overlay icons
        _trayIconBluetoothConn = CreateTrayIconImage(blue, white, blue, showConnected: true);
        _trayIconTcpConn = CreateTrayIconImage(green, white, green, showConnected: true);
        _trayIconBothConn = CreateTrayIconImage(teal, white, teal, showConnected: true);

        // Default icon (idle)
        _trayIcon = new Hardcodet.Wpf.TaskbarNotification.TaskbarIcon
        {
            Icon = _trayIconIdle,
            ToolTipText = "BluLink 文件传输服务端",
            Visibility = System.Windows.Visibility.Visible
        };

        var contextMenu = new System.Windows.Controls.ContextMenu();

        // Show window
        var showItem = new System.Windows.Controls.MenuItem { Header = "显示窗口" };
        showItem.Click += TrayMenu_ShowWindow;
        contextMenu.Items.Add(showItem);

        // Separator
        contextMenu.Items.Add(new System.Windows.Controls.Separator());

        // Bluetooth toggle (toggle menu item based on current state)
        _trayMenuBluetooth = new System.Windows.Controls.MenuItem { Header = _isBluetoothRunning ? "停止蓝牙" : "启动蓝牙" };
        _trayMenuBluetooth.Click += (s, e) => {
            TrayMenu_ToggleBluetooth(s, e);
            _trayMenuBluetooth.Header = _isBluetoothRunning ? "停止蓝牙" : "启动蓝牙";
        };
        contextMenu.Items.Add(_trayMenuBluetooth);

        // TCP toggle
        _trayMenuTcp = new System.Windows.Controls.MenuItem { Header = _isTcpRunning ? "停止TCP" : "启动TCP" };
        _trayMenuTcp.Click += (s, e) => {
            TrayMenu_ToggleTcp(s, e);
            _trayMenuTcp.Header = _isTcpRunning ? "停止TCP" : "启动TCP";
        };
        contextMenu.Items.Add(_trayMenuTcp);

        // Separator and Exit
        contextMenu.Items.Add(new System.Windows.Controls.Separator());
        var exitItem = new System.Windows.Controls.MenuItem { Header = "退出" };
        exitItem.Click += TrayMenu_Exit;
        contextMenu.Items.Add(exitItem);

        _trayIcon.ContextMenu = contextMenu;
        _trayIcon.TrayMouseDoubleClick += (s, e) => TrayMenu_ShowWindow(s, e);
    }

    private System.Drawing.Icon CreateTrayIconImage(System.Drawing.Color outer, System.Drawing.Color inner, System.Drawing.Color center, bool showConnected = false, bool showDisconnected = false)
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.Clear(System.Drawing.Color.Transparent);

        using var outerBrush = new SolidBrush(outer);
        using var innerBrush = new SolidBrush(inner);
        using var centerBrush = new SolidBrush(center);

        // Draw outer circle
        graphics.FillEllipse(outerBrush, 2, 2, 28, 28);

        // Draw inner white circle (unless we need to show disconnected)
        if (!showDisconnected)
        {
            graphics.FillEllipse(innerBrush, 8, 8, 16, 16);
        }

        // Draw center
        graphics.FillEllipse(centerBrush, 12, 12, 8, 8);

        // Draw overlay symbol for connection status
        if (showConnected)
        {
            // Draw white checkmark
            using var pen = new System.Drawing.Pen(System.Drawing.Color.White, 2);
            graphics.DrawLine(pen, 20, 24, 23, 27);
            graphics.DrawLine(pen, 23, 27, 28, 21);
        }
        else if (showDisconnected)
        {
            // Draw red X
            using var pen = new System.Drawing.Pen(System.Drawing.Color.FromArgb(244, 67, 54), 2);
            graphics.DrawLine(pen, 21, 21, 27, 27);
            graphics.DrawLine(pen, 27, 21, 21, 27);
        }

        return System.Drawing.Icon.FromHandle(bitmap.GetHicon());
    }

    private System.Drawing.Icon CreateTrayIconImage(System.Drawing.Color outer, System.Drawing.Color inner, System.Drawing.Color center)
    {
        return CreateTrayIconImage(outer, inner, center, false, false);
    }

    private void UpdateTrayIcon()
    {
        if (_trayIcon == null) return;

        System.Drawing.Icon? newIcon = null;
        string tooltip = "BluLink 文件传输服务端";
        bool hasConnected = _isBluetoothConnected || _isTcpConnected;

        if (_isBluetoothRunning && _isTcpRunning)
        {
            newIcon = hasConnected ? _trayIconBothConn : _trayIconBoth;
            tooltip = hasConnected ? "BluLink - 蓝牙+TCP已连接" : "BluLink - 蓝牙+TCP已启动";
        }
        else if (_isBluetoothRunning)
        {
            newIcon = hasConnected ? _trayIconBluetoothConn : _trayIconBluetooth;
            tooltip = hasConnected ? "BluLink - 蓝牙已连接" : "BluLink - 蓝牙已启动";
        }
        else if (_isTcpRunning)
        {
            newIcon = hasConnected ? _trayIconTcpConn : _trayIconTcp;
            tooltip = hasConnected ? "BluLink - TCP已连接" : "BluLink - TCP已启动";
        }
        else
        {
            newIcon = _trayIconIdle;
        }

        _trayIcon.Icon = newIcon;
        _trayIcon.ToolTipText = tooltip;
    }

    private void ShowNotification(string title, string message)
    {
        _trayIcon?.ShowBalloonTip(title, message, Hardcodet.Wpf.TaskbarNotification.BalloonIcon.Info);
    }

    private void AutoStartServers()
    {
        // Auto-start Bluetooth server
        try
        {
            _bluetoothServer?.StartServer();
            Dispatcher.Invoke(() => {
                StartBluetoothButton.IsEnabled = false;
                StopBluetoothButton.IsEnabled = true;
            });
            LogToFile("[MainWindow] Bluetooth server auto-started");
        }
        catch (Exception ex)
        {
            LogToFile($"[MainWindow] Auto-start Bluetooth failed: {ex.Message}");
        }

        // Auto-start TCP server
        try
        {
            int port = 9000;
            int.TryParse(TcpPortTextBox.Text, out port);
            _tcpServer?.StartServer(port);
            Dispatcher.Invoke(() => {
                StartTcpButton.IsEnabled = false;
                StopTcpButton.IsEnabled = true;
            });
            LogToFile("[MainWindow] TCP server auto-started on port " + port);
        }
        catch (Exception ex)
        {
            LogToFile($"[MainWindow] Auto-start TCP failed: {ex.Message}");
        }
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        // Minimize to system tray instead of taskbar
        if (WindowState == WindowState.Minimized)
        {
            Hide();
            _trayIcon.Visibility = Visibility.Visible;
        }
    }

    private void Window_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        // Hide to tray instead of closing, unless it's a forced close
        e.Cancel = true;
        WindowState = WindowState.Minimized;
        Hide();
        _trayIcon.Visibility = Visibility.Visible;
    }

    private void TrayMenu_ShowWindow(object sender, RoutedEventArgs e)
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void TrayMenu_ToggleBluetooth(object sender, RoutedEventArgs e)
    {
        if (_isBluetoothRunning)
        {
            _bluetoothServer?.StopServer();
            _isBluetoothRunning = false;
            _isBluetoothConnected = false;
        }
        else
        {
            _bluetoothServer?.StartServer();
            _isBluetoothRunning = true;
        }
        UpdateTrayIcon();
        Dispatcher.Invoke(() => {
            StartBluetoothButton.IsEnabled = !_isBluetoothRunning;
            StopBluetoothButton.IsEnabled = _isBluetoothRunning;
        });
    }

    private void TrayMenu_ToggleTcp(object sender, RoutedEventArgs e)
    {
        if (_isTcpRunning)
        {
            _tcpServer?.StopServer();
            _isTcpRunning = false;
            _isTcpConnected = false;
        }
        else
        {
            int port = 9000;
            Dispatcher.Invoke(() => int.TryParse(TcpPortTextBox.Text, out port));
            _tcpServer?.StartServer(port);
            _isTcpRunning = true;
        }
        UpdateTrayIcon();
        Dispatcher.Invoke(() => {
            StartTcpButton.IsEnabled = !_isTcpRunning;
            StopTcpButton.IsEnabled = _isTcpRunning;
        });
    }

    private void TrayMenu_Exit(object sender, RoutedEventArgs e)
    {
        // Force close - exit the application
        _trayIcon.Dispose();
        Application.Current.Shutdown();
    }

    private void DisplayBluetoothDeviceInfo()
    {
        try
        {
            var radio = BluetoothRadio.PrimaryRadio;
            if (radio != null)
            {
                BluetoothDeviceName.Text = radio.Name ?? "未知设备";
                BluetoothDeviceAddress.Text = radio.LocalAddress?.ToString() ?? "未知地址";
            }
            else
            {
                BluetoothDeviceName.Text = "未找到蓝牙适配器";
                BluetoothDeviceAddress.Text = "N/A";
            }
        }
        catch (Exception ex)
        {
            BluetoothDeviceName.Text = "获取失败";
            BluetoothDeviceAddress.Text = ex.Message;
        }
    }

    // Bluetooth handlers
    private void StartBluetooth_Click(object sender, RoutedEventArgs e)
    {
        _bluetoothServer?.StartServer();
        _isBluetoothRunning = true;
        UpdateTrayIcon();
        StartBluetoothButton.IsEnabled = false;
        StopBluetoothButton.IsEnabled = true;
    }

    private void StopBluetooth_Click(object sender, RoutedEventArgs e)
    {
        _bluetoothServer?.StopServer();
        _isBluetoothRunning = false;
        _isBluetoothConnected = false;
        UpdateTrayIcon();
        StartBluetoothButton.IsEnabled = true;
        StopBluetoothButton.IsEnabled = false;
    }

    private async void OnBluetoothClientConnected(object? sender, ClientConnectionEventArgs e)
    {
        LogToFile("[Bluetooth] OnBluetoothClientConnected START");
        var client = e.Client;
        var dispatcher = Dispatcher;
        if (dispatcher == null) {
            LogToFile("[Bluetooth] dispatcher is null, returning");
            return;
        }

        System.Diagnostics.Debug.WriteLine($"[Bluetooth] Client connected: {client.DeviceName}");
        LogToFile($"[Bluetooth] Client: {client.DeviceName}");

        // Show notification for new connection
        dispatcher.Invoke(() => {
            _isBluetoothConnected = true;
            ShowNotification("蓝牙已连接", $"手机已连接: {client.DeviceName}");
            UpdateTrayIcon();
            ConnectedDeviceName.Text = $"已连接: {client.DeviceName}";
            ConnectedDeviceName.Visibility = Visibility.Visible;
        });

        // Capture share path in dispatcher thread
        string sharePath = string.Empty;
        string uploadPath = string.Empty;
        dispatcher.Invoke(() => {
            sharePath = SharePathTextBox.Text;
            uploadPath = UploadPathTextBox.Text;
        });
        LogToFile($"[Bluetooth] SharePath: {sharePath}, UploadPath: {uploadPath}");

        var service = new FileTransferService(sharePath, uploadPath, client, dispatcher);
        service.LogReceived += Service_LogReceived;
        lock (_servicesLock)
        {
            _activeServices.Add(service);
        }

        try
        {
            LogToFile("[Bluetooth] About to call HandleClientAsync");
            System.Diagnostics.Debug.WriteLine($"[Bluetooth] Starting HandleClientAsync");
            await service.HandleClientAsync();
            System.Diagnostics.Debug.WriteLine($"[Bluetooth] HandleClientAsync completed");
            LogToFile("[Bluetooth] HandleClientAsync completed normally");
        }
        catch (Exception ex)
        {
            LogToFile($"[Bluetooth] Exception: {ex.GetType().Name}: {ex.Message}\n{ex.StackTrace}");
            System.Diagnostics.Debug.WriteLine($"[Bluetooth] Exception: {ex.GetType().Name}: {ex.Message}\n{ex.StackTrace}");
            dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[蓝牙错误] {ex.GetType().Name}: {ex.Message}"));
        }
        finally
        {
            try { client.Close(); } catch { }
            lock (_servicesLock)
            {
                _activeServices.Remove(service);
            }
            dispatcher.Invoke(() => {
                _isBluetoothConnected = false;
                ShowNotification("蓝牙已断开", $"手机 {client.DeviceName} 已断开连接");
                UpdateTrayIcon();
                LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] 蓝牙客户端已断开");
                ConnectedDeviceName.Visibility = Visibility.Collapsed;
            });
            LogToFile("[Bluetooth] OnBluetoothClientConnected END");
        }
    }

    // TCP handlers
    private void StartTcp_Click(object sender, RoutedEventArgs e)
    {
        int port = 9000;
        int.TryParse(TcpPortTextBox.Text, out port);
        _tcpServer?.StartServer(port);
        _isTcpRunning = true;
        UpdateTrayIcon();
        StartTcpButton.IsEnabled = false;
        StopTcpButton.IsEnabled = true;
    }

    private void StopTcp_Click(object sender, RoutedEventArgs e)
    {
        _tcpServer?.StopServer();
        _isTcpRunning = false;
        _isTcpConnected = false;
        UpdateTrayIcon();
        StartTcpButton.IsEnabled = true;
        StopTcpButton.IsEnabled = false;
    }

    private async void OnTcpClientConnected(object? sender, TcpClientConnectionEventArgs e)
    {
        var client = e.Client;
        TcpFileTransferService? service = null;
        var dispatcher = Dispatcher;
        if (dispatcher == null) return;

        System.Diagnostics.Debug.WriteLine($"[TCP] Client connected");

        // Show notification for new connection
        dispatcher.Invoke(() => {
            _isTcpConnected = true;
            ShowNotification("TCP已连接", $"手机已连接: {client.DeviceAddress}");
            UpdateTrayIcon();
            TcpConnectedDeviceName.Text = $"已连接: {client.DeviceAddress}";
            TcpConnectedDeviceName.Visibility = Visibility.Visible;
        });

        // Capture share path in dispatcher thread
        string sharePath = string.Empty;
        string uploadPath = string.Empty;
        dispatcher.Invoke(() => {
            sharePath = SharePathTextBox.Text;
            uploadPath = UploadPathTextBox.Text;
        });

        try
        {
            service = new TcpFileTransferService(sharePath, uploadPath, client, dispatcher);
            service.LogReceived += Service_LogReceived;
            lock (_servicesLock)
            {
                _activeServices.Add(service);
            }

            System.Diagnostics.Debug.WriteLine($"[TCP] Starting HandleClientAsync");
            dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] 开始处理TCP客户端..."));

            await service.HandleClientAsync();
            System.Diagnostics.Debug.WriteLine($"[TCP] HandleClientAsync completed");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[TCP] Exception: {ex.GetType().Name}: {ex.Message}\n{ex.StackTrace}");
            var msg = $"[TCP错误] {ex.GetType().Name}: {ex.Message}";
            dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] {msg}"));
        }
        finally
        {
            try { client.Close(); } catch { }
            lock (_servicesLock)
            {
                _activeServices.Remove(service);
            }
            dispatcher.Invoke(() => {
                _isTcpConnected = false;
                ShowNotification("TCP已断开", $"手机 {client.DeviceAddress} 已断开连接");
                UpdateTrayIcon();
                LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] TCP客户端已断开");
                TcpConnectedDeviceName.Visibility = Visibility.Collapsed;
            });
        }
    }

    private void Service_LogReceived(object? sender, string log)
    {
        Dispatcher?.Invoke(() => {
            LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] {log}");
            RefreshFileList();
        });
    }

    private void BrowseUploadPath_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            InitialDirectory = UploadPathTextBox.Text
        };
        if (dialog.ShowDialog() == true)
        {
            UploadPathTextBox.Text = dialog.FolderName;
        }
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            InitialDirectory = SharePathTextBox.Text
        };
        if (dialog.ShowDialog() == true)
        {
            SharePathTextBox.Text = dialog.FolderName;
            RefreshFileList();
            NotifyAllClientsPathChanged();
        }
    }

    private async void NotifyAllClientsPathChanged()
    {
        List<object> servicesToNotify;
        lock (_servicesLock)
        {
            servicesToNotify = new List<object>(_activeServices);
        }

        foreach (var service in servicesToNotify)
        {
            try
            {
                if (service is BluetoothFileServer.Bluetooth.FileTransferService bluetoothService)
                {
                    await bluetoothService.NotifyPathChangedAsync();
                }
                else if (service is BluetoothFileServer.Tcp.TcpFileTransferService tcpService)
                {
                    await tcpService.NotifyPathChangedAsync();
                }
            }
            catch (Exception ex)
            {
                LogToFile($"[NotifyPathChanged] 通知客户端失败: {ex.Message}");
            }
        }
    }

    private void CopyLog_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var logs = LogListBox.Items.Cast<string>().ToList();
            var logText = string.Join(Environment.NewLine, logs);
            Clipboard.SetText(logText);
            MessageBox.Show("日志已复制到剪贴板", "复制成功", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"复制失败: {ex.Message}", "错误", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void RefreshFileList()
    {
        FileListBox.Items.Clear();
        if (Directory.Exists(SharePathTextBox.Text))
        {
            foreach (var file in Directory.GetFiles(SharePathTextBox.Text))
            {
                var fi = new FileInfo(file);
                FileListBox.Items.Add($"{fi.Name} ({FormatSize(fi.Length)})");
            }
        }
    }

    private string FormatSize(long bytes)
    {
        string[] sizes = { "B", "KB", "MB", "GB" };
        int order = 0;
        double size = bytes;
        while (size >= 1024 && order < sizes.Length - 1) { order++; size /= 1024; }
        return $"{size:0.##} {sizes[order]}";
    }

    private void AutoStart_Click(object sender, RoutedEventArgs e)
    {
        if (AutoStartCheckBox.IsChecked == true)
        {
            AutoStartHelper.EnableAutoStart();
        }
        else
        {
            AutoStartHelper.DisableAutoStart();
        }
    }

    protected override void OnClosed(EventArgs e)
    {
        LogToFile("[MainWindow] Application closing...");
        _trayIcon?.Dispose();
        _bluetoothServer?.Dispose();
        _tcpServer?.Dispose();
        _discoveryServer?.Dispose();
        base.OnClosed(e);
    }

    private static void LogToFile(string message)
    {
        try
        {
            var timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff");
            var logLine = $"[{timestamp}] {message}{Environment.NewLine}";
            File.AppendAllText(LogFilePath, logLine);
            System.Diagnostics.Debug.WriteLine(logLine);
        }
        catch { }
    }
}
