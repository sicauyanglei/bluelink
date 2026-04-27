using System.Windows;
using System.Threading;

namespace BluetoothFileServer;

public partial class App : Application
{
    private static readonly Mutex _mutex = new(true, "BluLink_SingleInstance_Mutex");
    private bool _mutexOwned = false;

    protected override void OnStartup(StartupEventArgs e)
    {
        // Check if another instance is already running
        if (_mutex.WaitOne(TimeSpan.Zero, true))
        {
            // This is the first instance - normal startup
            _mutexOwned = true;
            base.OnStartup(e);
        }
        else
        {
            // Another instance is already running - try to activate its window
            MessageBox.Show("BluLink 已在运行中", "提示", MessageBoxButton.OK, MessageBoxImage.Information);
            TryActivateExistingInstance();
            Shutdown();
        }
    }

    private void TryActivateExistingInstance()
    {
        // Find and activate the existing window
        var currentProcess = System.Diagnostics.Process.GetCurrentProcess();
        foreach (var process in System.Diagnostics.Process.GetProcesses())
        {
            if (process.Id != currentProcess.Id &&
                process.ProcessName == currentProcess.ProcessName &&
                process.MainWindowHandle != IntPtr.Zero)
            {
                // Found an existing window - try to activate it
                var hWnd = process.MainWindowHandle;
                if (NativeMethods.IsIconic(hWnd))
                {
                    NativeMethods.ShowWindow(hWnd, NativeMethods.SW_RESTORE);
                }
                NativeMethods.SetForegroundWindow(hWnd);
                break;
            }
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_mutexOwned)
        {
            _mutex.ReleaseMutex();
            _mutexOwned = false;
        }
        base.OnExit(e);
    }
}

internal static class NativeMethods
{
    public const int SW_RESTORE = 9;

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hWnd);
}
