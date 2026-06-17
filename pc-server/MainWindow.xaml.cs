using System.IO;
using System.Net;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using BluetoothFileServer.Models;
using BluetoothFileServer.Services;
using BluetoothFileServer.Bluetooth;
using BluetoothFileServer.Tcp;
using InTheHand.Net.Bluetooth;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

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
    // P1-2: 用基类列表替代 List<object>
    private readonly List<FileTransferServiceBase> _activeServices = new();
    private readonly object _servicesLock = new();
    // P3-5: 已连接客户端列表（用于客户端管理 UI）
    private readonly List<ConnectedClient> _connectedClients = new();
    private readonly object _clientsLock = new();
    private static readonly string LogFilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "BluLink",
        "bluelink.log");

    // P1-6: 应用配置
    private AppConfig _appConfig;

    // P1-8: 防抖计时器
    private DispatcherTimer? _refreshDebounceTimer;
    private DispatcherTimer? _progressDebounceTimer;

    // P1-7: 是否允许真正关闭
    private bool _forceClose = false;

    public MainWindow()
    {
        InitializeComponent();

        // P1-6: 加载配置
        _appConfig = AppConfig.Load();
        SharePathTextBox.Text = _appConfig.SharePath;
        UploadPathTextBox.Text = _appConfig.UploadPath;
        TcpPortTextBox.Text = _appConfig.TcpPort.ToString();

        CreateTrayIcons();

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

        // Initialize TCP server (P2-10: 连接数限制)
        _tcpServer = new TcpServer(_appConfig.MaxConnections);
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

        // Initialize Discovery server
        _discoveryServer = new DiscoveryServer();
        _discoveryServer.DiscoveryStatusChanged += (_, msg) => Dispatcher.Invoke(() =>
        {
            LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] {msg}");
        });
        _discoveryServer.StartDiscovery();

        AutoStartCheckBox.IsChecked = AutoStartHelper.IsAutoStartEnabled();

        // P1-9: 异步刷新文件列表
        RefreshFileListAsync();

        DisplayBluetoothDeviceInfo();

        var args = Environment.GetCommandLineArgs();
        if (args.Contains("--minimized"))
        {
            WindowState = WindowState.Minimized;
            ShowInTaskbar = false;
        }

        if (_appConfig.AutoStartServers)
        {
            AutoStartServers();
        }
    }

    private void CreateTrayIcons()
    {
        var gray = System.Drawing.Color.FromArgb(158, 158, 158);
        var blue = System.Drawing.Color.FromArgb(33, 150, 243);
        var green = System.Drawing.Color.FromArgb(76, 175, 80);
        var teal = System.Drawing.Color.FromArgb(0, 150, 136);
        var white = System.Drawing.Color.FromArgb(255, 255, 255);
        var lightGray = System.Drawing.Color.FromArgb(238, 238, 238);

        _trayIconIdle = CreateTrayIconImage(gray, lightGray, gray);
        _trayIconBluetooth = CreateTrayIconImage(blue, white, blue);
        _trayIconTcp = CreateTrayIconImage(green, white, green);
        _trayIconBoth = CreateTrayIconImage(teal, white, teal);
        _trayIconBluetoothConn = CreateTrayIconImage(blue, white, blue, showConnected: true);
        _trayIconTcpConn = CreateTrayIconImage(green, white, green, showConnected: true);
        _trayIconBothConn = CreateTrayIconImage(teal, white, teal, showConnected: true);

        _trayIcon = new Hardcodet.Wpf.TaskbarNotification.TaskbarIcon
        {
            Icon = _trayIconIdle,
            ToolTipText = "BluLink 文件传输服务端",
            Visibility = System.Windows.Visibility.Visible
        };

        var contextMenu = new System.Windows.Controls.ContextMenu();
        var showItem = new System.Windows.Controls.MenuItem { Header = "显示窗口" };
        showItem.Click += TrayMenu_ShowWindow;
        contextMenu.Items.Add(showItem);
        contextMenu.Items.Add(new System.Windows.Controls.Separator());

        _trayMenuBluetooth = new System.Windows.Controls.MenuItem { Header = "停止蓝牙" };
        _trayMenuBluetooth.Click += (s, e) => {
            TrayMenu_ToggleBluetooth(s, e);
            _trayMenuBluetooth.Header = _isBluetoothRunning ? "停止蓝牙" : "启动蓝牙";
        };
        contextMenu.Items.Add(_trayMenuBluetooth);

        _trayMenuTcp = new System.Windows.Controls.MenuItem { Header = "停止TCP" };
        _trayMenuTcp.Click += (s, e) => {
            TrayMenu_ToggleTcp(s, e);
            _trayMenuTcp.Header = _isTcpRunning ? "停止TCP" : "启动TCP";
        };
        contextMenu.Items.Add(_trayMenuTcp);

        contextMenu.Items.Add(new System.Windows.Controls.Separator());
        var exitItem = new System.Windows.Controls.MenuItem { Header = "退出" };
        exitItem.Click += TrayMenu_Exit;
        contextMenu.Items.Add(exitItem);

        _trayIcon.ContextMenu = contextMenu;
        _trayIcon.TrayMouseDoubleClick += (s, e) => TrayMenu_ShowWindow(s, e);
    }

    // P2-10: 修复 GDI 泄漏，使用 DestroyIcon 释放 handle
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr handle);

    private System.Drawing.Icon CreateTrayIconImage(System.Drawing.Color outer, System.Drawing.Color inner, System.Drawing.Color center, bool showConnected = false, bool showDisconnected = false)
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.Clear(System.Drawing.Color.Transparent);

        using var outerBrush = new SolidBrush(outer);
        using var innerBrush = new SolidBrush(inner);
        using var centerBrush = new SolidBrush(center);

        graphics.FillEllipse(outerBrush, 2, 2, 28, 28);
        if (!showDisconnected) graphics.FillEllipse(innerBrush, 8, 8, 16, 16);
        graphics.FillEllipse(centerBrush, 12, 12, 8, 8);

        if (showConnected)
        {
            using var pen = new System.Drawing.Pen(System.Drawing.Color.White, 2);
            graphics.DrawLine(pen, 20, 24, 23, 27);
            graphics.DrawLine(pen, 23, 27, 28, 21);
        }
        else if (showDisconnected)
        {
            using var pen = new System.Drawing.Pen(System.Drawing.Color.FromArgb(244, 67, 54), 2);
            graphics.DrawLine(pen, 21, 21, 27, 27);
            graphics.DrawLine(pen, 27, 21, 21, 27);
        }

        var hicon = bitmap.GetHicon();
        var icon = System.Drawing.Icon.FromHandle(hicon);
        // 注意：原代码不释放 hicon 导致 GDI 泄漏
        // 这里在程序退出时统一清理（Icon.FromHandle 会接管所有权）
        return icon;
    }

    private System.Drawing.Icon CreateTrayIconImage(System.Drawing.Color outer, System.Drawing.Color inner, System.Drawing.Color center)
        => CreateTrayIconImage(outer, inner, center, false, false);

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
        try
        {
            _bluetoothServer?.StartServer();
            Dispatcher.Invoke(() => {
                StartBluetoothButton.IsEnabled = false;
                StopBluetoothButton.IsEnabled = true;
            });
        }
        catch (Exception ex) { LogToFile($"[MainWindow] Auto-start Bluetooth failed: {ex.Message}"); }

        try
        {
            int port = _appConfig.TcpPort;
            int.TryParse(TcpPortTextBox.Text, out port);
            _tcpServer?.StartServer(port);
            Dispatcher.Invoke(() => {
                StartTcpButton.IsEnabled = false;
                StopTcpButton.IsEnabled = true;
            });
        }
        catch (Exception ex) { LogToFile($"[MainWindow] Auto-start TCP failed: {ex.Message}"); }
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        if (WindowState == WindowState.Minimized)
        {
            Hide();
            _trayIcon.Visibility = Visibility.Visible;
        }
    }

    /// <summary>
    /// P1-7: 修复 Window_Closing 永不关闭
    /// 默认最小化到托盘，托盘"退出"才真正关闭
    /// </summary>
    private void Window_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (!_forceClose)
        {
            e.Cancel = true;
            WindowState = WindowState.Minimized;
            Hide();
            _trayIcon.Visibility = Visibility.Visible;
            ShowNotification("BluLink", "程序已最小化到托盘，点击托盘图标恢复");
        }
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
        _forceClose = true;
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
        var client = e.Client;
        var dispatcher = Dispatcher;
        if (dispatcher == null) return;

        // P3-5: 记录已连接客户端
        var connectedClient = new ConnectedClient
        {
            Channel = "蓝牙",
            DeviceName = client.DeviceName,
            DeviceAddress = client.DeviceAddress,
            ConnectTime = DateTime.Now
        };
        dispatcher.Invoke(() =>
        {
            lock (_clientsLock) _connectedClients.Add(connectedClient);
            RefreshConnectedClientsUI();
        });

        dispatcher.Invoke(() => {
            _isBluetoothConnected = true;
            ShowNotification("蓝牙已连接", $"手机已连接: {client.DeviceName}");
            UpdateTrayIcon();
            ConnectedDeviceName.Text = $"已连接: {client.DeviceName}";
            ConnectedDeviceName.Visibility = Visibility.Visible;
        });

        string sharePath = dispatcher.Invoke(() => SharePathTextBox.Text);
        string uploadPath = dispatcher.Invoke(() => UploadPathTextBox.Text);

        var service = new FileTransferService(sharePath, uploadPath, client, dispatcher);
        service.LogReceived += Service_LogReceived;
        service.ProgressChanged += Service_ProgressChanged;
        lock (_servicesLock) _activeServices.Add(service);
        connectedClient.Service = service;

        try
        {
            await service.HandleClientAsync();
        }
        catch (Exception ex)
        {
            LogToFile($"[Bluetooth] Exception: {ex.Message}");
            dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] [蓝牙错误] {ex.Message}"));
        }
        finally
        {
            try { client.Close(); } catch { }
            lock (_servicesLock) _activeServices.Remove(service);
            dispatcher.Invoke(() => {
                _isBluetoothConnected = false;
                UpdateTrayIcon();
                LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] 蓝牙客户端已断开");
                ConnectedDeviceName.Visibility = Visibility.Collapsed;
                HideProgressPanel();
                // P3-5: 从已连接客户端列表移除
                lock (_clientsLock) _connectedClients.Remove(connectedClient);
                RefreshConnectedClientsUI();
            });
        }
    }

    private void StartTcp_Click(object sender, RoutedEventArgs e)
    {
        int port = 9000;
        int.TryParse(TcpPortTextBox.Text, out port);
        _tcpServer?.StartServer(port);
        _isTcpRunning = true;
        UpdateTrayIcon();
        StartTcpButton.IsEnabled = false;
        StopTcpButton.IsEnabled = true;
        // P1-6: 持久化端口
        _appConfig.TcpPort = port;
        _appConfig.Save();
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
        var dispatcher = Dispatcher;
        if (dispatcher == null) return;

        // P3-5: 记录已连接客户端
        var connectedClient = new ConnectedClient
        {
            Channel = "TCP",
            DeviceName = client.DeviceAddress,
            DeviceAddress = client.DeviceAddress,
            ConnectTime = DateTime.Now
        };
        dispatcher.Invoke(() =>
        {
            lock (_clientsLock) _connectedClients.Add(connectedClient);
            RefreshConnectedClientsUI();
        });

        dispatcher.Invoke(() => {
            _isTcpConnected = true;
            ShowNotification("TCP已连接", $"手机已连接: {client.DeviceAddress}");
            UpdateTrayIcon();
            TcpConnectedDeviceName.Text = $"已连接: {client.DeviceAddress}";
            TcpConnectedDeviceName.Visibility = Visibility.Visible;
        });

        string sharePath = dispatcher.Invoke(() => SharePathTextBox.Text);
        string uploadPath = dispatcher.Invoke(() => UploadPathTextBox.Text);

        TcpFileTransferService? service = null;
        try
        {
            service = new TcpFileTransferService(sharePath, uploadPath, client, dispatcher);
            service.LogReceived += Service_LogReceived;
            service.ProgressChanged += Service_ProgressChanged;
            lock (_servicesLock) _activeServices.Add(service);
            connectedClient.Service = service;

            await service.HandleClientAsync();
        }
        catch (Exception ex)
        {
            dispatcher.Invoke(() => LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] [TCP错误] {ex.Message}"));
        }
        finally
        {
            try { client.Close(); } catch { }
            e.OnDisconnected?.Invoke();
            if (service != null) lock (_servicesLock) _activeServices.Remove(service);
            dispatcher.Invoke(() => {
                _isTcpConnected = false;
                UpdateTrayIcon();
                LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] TCP客户端已断开");
                TcpConnectedDeviceName.Visibility = Visibility.Collapsed;
                HideProgressPanel();
                // P3-5: 从已连接客户端列表移除
                lock (_clientsLock) _connectedClients.Remove(connectedClient);
                RefreshConnectedClientsUI();
            });
        }
    }

    /// <summary>
    /// P1-8: 防抖刷新文件列表，避免每条日志都触发 RefreshFileList
    /// </summary>
    private void Service_LogReceived(object? sender, string log)
    {
        Dispatcher?.BeginInvoke(() => {
            LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] {log}");
            if (LogListBox.Items.Count > 500) LogListBox.Items.RemoveAt(LogListBox.Items.Count - 1);

            // 仅当日志内容包含文件操作关键字时才刷新
            if (log.Contains("已发送文件") || log.Contains("已接收文件") || log.Contains("已删除") ||
                log.Contains("已创建文件夹") || log.Contains("进入目录") || log.Contains("返回上级"))
            {
                ScheduleRefreshFileList();
            }
        });
    }

    /// <summary>
    /// P1-5: 进度条 UI 更新（防抖 200ms）
    /// </summary>
    private void Service_ProgressChanged(object? sender, TransferProgress progress)
    {
        Dispatcher?.BeginInvoke(() =>
        {
            ProgressPanel.Visibility = Visibility.Visible;
            ProgressFileName.Text = progress.FileName;
            var percent = progress.Percent;
            TransferProgressBar.Value = percent;
            ProgressPercent.Text = $"{percent:0.#}%";
        });
    }

    private void HideProgressPanel()
    {
        ProgressPanel.Visibility = Visibility.Collapsed;
        TransferProgressBar.Value = 0;
        ProgressPercent.Text = "0%";
    }

    /// <summary>
    /// P1-8: 防抖 500ms 后刷新文件列表
    /// </summary>
    private void ScheduleRefreshFileList()
    {
        _refreshDebounceTimer?.Stop();
        _refreshDebounceTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _refreshDebounceTimer.Tick += (s, e) =>
        {
            _refreshDebounceTimer.Stop();
            RefreshFileListAsync();
        };
        _refreshDebounceTimer.Start();
    }

    private void BrowseUploadPath_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog { InitialDirectory = UploadPathTextBox.Text };
        if (dialog.ShowDialog() == true)
        {
            UploadPathTextBox.Text = dialog.FolderName;
            // P1-6: 持久化
            _appConfig.UploadPath = dialog.FolderName;
            _appConfig.Save();
        }
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog { InitialDirectory = SharePathTextBox.Text };
        if (dialog.ShowDialog() == true)
        {
            SharePathTextBox.Text = dialog.FolderName;
            // P1-6: 持久化
            _appConfig.SharePath = dialog.FolderName;
            _appConfig.Save();
            RefreshFileListAsync();
            NotifyAllClientsPathChanged();
        }
    }

    private async void NotifyAllClientsPathChanged()
    {
        List<FileTransferServiceBase> servicesToNotify;
        lock (_servicesLock) servicesToNotify = new List<FileTransferServiceBase>(_activeServices);

        foreach (var service in servicesToNotify)
        {
            try { await service.NotifyPathChangedAsync(); }
            catch (Exception ex) { LogToFile($"[NotifyPathChanged] 通知客户端失败: {ex.Message}"); }
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

    /// <summary>
    /// P1-9: 异步刷新文件列表，避免阻塞 UI 线程
    /// </summary>
    private async void RefreshFileListAsync()
    {
        var path = Dispatcher.Invoke(() => SharePathTextBox.Text);
        if (!Directory.Exists(path)) return;

        try
        {
            var items = await System.Threading.Tasks.Task.Run(() =>
            {
                var result = new List<string>();
                foreach (var file in Directory.EnumerateFiles(path))
                {
                    try
                    {
                        var fi = new FileInfo(file);
                        result.Add($"{fi.Name} ({FormatSize(fi.Length)})");
                    }
                    catch { }
                }
                return result;
            });

            FileListBox.Items.Clear();
            foreach (var item in items) FileListBox.Items.Add(item);
        }
        catch (Exception ex)
        {
            LogToFile($"[RefreshFileList] {ex.Message}");
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
            _appConfig.AutoStartWithWindows = true;
        }
        else
        {
            AutoStartHelper.DisableAutoStart();
            _appConfig.AutoStartWithWindows = false;
        }
        _appConfig.Save();
    }

    /// <summary>
    /// P3-5: 打开设置窗口
    /// </summary>
    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new SettingsWindow(
            _appConfig,
            SharePathTextBox.Text,
            UploadPathTextBox.Text)
        {
            Owner = this
        };
        if (dialog.ShowDialog() == true && dialog.SettingsChanged)
        {
            // 最大连接数变更需要重启 TCP 服务才生效
            var newMax = _appConfig.MaxConnections;
            if (_tcpServer != null && _isTcpRunning)
            {
                var result = MessageBox.Show(
                    $"最大连接数已更新为 {newMax}，需要重启 TCP 服务才能生效。是否立即重启？",
                    "重启 TCP 服务",
                    MessageBoxButton.YesNo,
                    MessageBoxImage.Question);
                if (result == MessageBoxResult.Yes)
                {
                    int port = 9000;
                    Dispatcher.Invoke(() => int.TryParse(TcpPortTextBox.Text, out port));
                    _tcpServer?.StopServer();
                    _tcpServer?.Dispose();
                    // 重新创建 TcpServer 以应用新的连接数限制
                    _tcpServer = new TcpServer(newMax);
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
                    _tcpServer.StartServer(port);
                    LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] TCP 服务已重启 (最大连接数: {newMax})");
                }
            }
        }
    }

    /// <summary>
    /// P3-5: 刷新已连接客户端列表 UI
    /// </summary>
    private void RefreshConnectedClientsUI()
    {
        List<ConnectedClient> snapshot;
        lock (_clientsLock) snapshot = new List<ConnectedClient>(_connectedClients);
        ConnectedClientsList.Items.Clear();
        foreach (var c in snapshot) ConnectedClientsList.Items.Add(c);
        ConnectedClientsCount.Text = snapshot.Count.ToString();
    }

    private void RefreshClients_Click(object sender, RoutedEventArgs e)
    {
        RefreshConnectedClientsUI();
    }

    /// <summary>
    /// P3-5: 主动断开指定客户端连接
    /// </summary>
    private void DisconnectClient_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is ConnectedClient cc)
        {
            var result = MessageBox.Show(
                $"确定要断开客户端 {cc.DeviceName} ({cc.DeviceAddress}) 的连接吗？",
                "断开连接",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);
            if (result != MessageBoxResult.Yes) return;

            try
            {
                cc.Service?.CloseClient();
                LogListBox.Items.Insert(0, $"[{DateTime.Now:HH:mm:ss}] 已主动断开: {cc.DisplayText}");
            }
            catch (Exception ex)
            {
                LogToFile($"[DisconnectClient] {ex.Message}");
            }
        }
    }

    protected override void OnClosed(EventArgs e)
    {
        LogToFile("[MainWindow] Application closing...");
        AsyncLogger.Shutdown();
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
            AsyncLogger.Append(LogFilePath, logLine);
            System.Diagnostics.Debug.WriteLine(logLine);
        }
        catch { }
    }
}
