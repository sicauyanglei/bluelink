"""
BluLink UI自动化测试
测试项目：
1. 窗口显示和控件可见性
2. 蓝牙启动/停止按钮
3. TCP启动/停止按钮
4. 最小化到托盘
5. 托盘菜单功能
"""
import sys
import time
import subprocess

from pywinauto import Application

# 测试结果收集
test_results = []

def log(msg):
    print(f"[LOG] {msg}")

def pass_test(name):
    test_results.append((name, "PASS"))
    print(f"[PASS] {name}")

def fail_test(name, reason):
    test_results.append((name, f"FAIL: {reason}"))
    print(f"[FAIL] {name} - {reason}")

def test_window_display(app):
    """测试窗口显示和标题"""
    try:
        log("测试1: 窗口显示")
        window = app.window(title_re="BluLink.*")
        window.wait('visible', timeout=5)

        title = window.window_text()
        if "BluLink" in title:
            pass_test(f"窗口标题正确: {title}")
        else:
            fail_test("窗口标题", f"期望包含'BluLink',实际: {title}")

        # 列出所有可用控件
        log("  可用控件列表:")
        for child in window.children():
            if child.is_visible():
                log(f"    - {child.window_text() or child.class_name} ({child.class_name})")

    except Exception as e:
        fail_test("窗口显示测试", str(e))

def test_bluetooth_buttons(app):
    """测试蓝牙启动/停止按钮"""
    try:
        log("测试2: 蓝牙按钮功能")
        window = app.window(title_re="BluLink.*")

        # 使用正则表达式查找按钮
        start_btn = window['启动蓝牙']
        stop_btn = window['停止蓝牙']

        log(f"  启动蓝牙按钮可用: {start_btn.is_enabled()}")
        log(f"  停止蓝牙按钮可用: {stop_btn.is_enabled()}")

        # 点击启动蓝牙
        start_btn.click()
        time.sleep(1.5)

        # 检查状态变化
        start_btn_enabled = start_btn.is_enabled()
        stop_btn_enabled = stop_btn.is_enabled()
        log(f"  点击后 - 启动按钮可用: {start_btn_enabled}, 停止按钮可用: {stop_btn_enabled}")

        if not start_btn_enabled and stop_btn_enabled:
            pass_test("蓝牙启动按钮切换正确")
        else:
            fail_test("蓝牙启动按钮切换", f"启动:{start_btn_enabled}, 停止:{stop_btn_enabled}")

        # 点击停止蓝牙
        stop_btn.click()
        time.sleep(1)

        start_btn_enabled_after = start_btn.is_enabled()
        stop_btn_enabled_after = stop_btn.is_enabled()
        log(f"  停止后 - 启动按钮可用: {start_btn_enabled_after}, 停止按钮可用: {stop_btn_enabled_after}")

        if start_btn_enabled_after and not stop_btn_enabled_after:
            pass_test("蓝牙停止按钮切换正确")
        else:
            fail_test("蓝牙停止按钮切换", f"启动:{start_btn_enabled_after}, 停止:{stop_btn_enabled_after}")

    except Exception as e:
        fail_test("蓝牙按钮测试", str(e))

def test_tcp_buttons(app):
    """测试TCP启动/停止按钮"""
    try:
        log("测试3: TCP按钮功能")
        window = app.window(title_re="BluLink.*")

        start_btn = window['启动TCP']
        stop_btn = window['停止TCP']

        log(f"  启动TCP按钮可用: {start_btn.is_enabled()}")
        log(f"  停止TCP按钮可用: {stop_btn.is_enabled()}")

        # 点击启动TCP
        start_btn.click()
        time.sleep(1.5)

        start_btn_enabled = start_btn.is_enabled()
        stop_btn_enabled = stop_btn.is_enabled()
        log(f"  点击后 - 启动按钮可用: {start_btn_enabled}, 停止按钮可用: {stop_btn_enabled}")

        if not start_btn_enabled and stop_btn_enabled:
            pass_test("TCP启动按钮切换正确")
        else:
            fail_test("TCP启动按钮切换", f"启动:{start_btn_enabled}, 停止:{stop_btn_enabled}")

        # 点击停止TCP
        stop_btn.click()
        time.sleep(1)

        start_btn_enabled_after = start_btn.is_enabled()
        stop_btn_enabled_after = stop_btn.is_enabled()
        log(f"  停止后 - 启动按钮可用: {start_btn_enabled_after}, 停止按钮可用: {stop_btn_enabled_after}")

        if start_btn_enabled_after and not stop_btn_enabled_after:
            pass_test("TCP停止按钮切换正确")
        else:
            fail_test("TCP停止按钮切换", f"启动:{start_btn_enabled_after}, 停止:{stop_btn_enabled_after}")

    except Exception as e:
        fail_test("TCP按钮测试", str(e))

def test_browse_button(app):
    """测试浏览按钮"""
    try:
        log("测试4: 浏览按钮")
        window = app.window(title_re="BluLink.*")

        browse_btn = window['浏览...']
        log(f"  浏览按钮可见: {browse_btn.is_visible()}")

        if browse_btn.is_visible():
            pass_test("浏览按钮可见")
        else:
            fail_test("浏览按钮", "不可见")

    except Exception as e:
        fail_test("浏览按钮测试", str(e))

def test_autostart_checkbox(app):
    """测试开机自启动复选框"""
    try:
        log("测试5: 开机自启动复选框")
        window = app.window(title_re="BluLink.*")

        autostart_cb = window['开机自启动']
        log(f"  开机自启动复选框可见: {autostart_cb.is_visible()}")

        if autostart_cb.is_visible():
            pass_test("开机自启动复选框可见")
        else:
            fail_test("开机自启动复选框", "不可见")

    except Exception as e:
        fail_test("开机自启动复选框测试", str(e))

def test_minimize_to_tray(app):
    """测试最小化到托盘"""
    try:
        log("测试6: 最小化到托盘")
        window = app.window(title_re="BluLink.*")

        # 最小化窗口
        window.minimize()
        time.sleep(1)

        # 检查窗口是否隐藏
        is_visible = window.is_visible()
        log(f"  最小化后窗口可见: {is_visible}")

        if not is_visible:
            pass_test("最小化到托盘(窗口隐藏)")
        else:
            fail_test("最小化到托盘", "窗口仍然可见")

    except Exception as e:
        fail_test("最小化到托盘测试", str(e))

def main():
    print("=" * 60)
    print("BluLink UI Automation Test")
    print("=" * 60)

    try:
        # Get BluLink process PID
        result = subprocess.run(['powershell', '-Command', '(Get-Process BluLink).Id'],
                             capture_output=True, text=True, timeout=5)
        pid = int(result.stdout.strip())
        log(f"Found BluLink process PID: {pid}")

        # Connect to the process
        app = Application(backend="win32").connect(process=pid, timeout=5)
        log("Connected to BluLink process")
    except Exception as e:
        print(f"Failed to connect to process: {e}")
        sys.exit(1)

    # Run tests
    test_window_display(app)
    test_bluetooth_buttons(app)
    test_tcp_buttons(app)
    test_browse_button(app)
    test_autostart_checkbox(app)
    test_minimize_to_tray(app)

    # Output results
    print("\n" + "=" * 60)
    print("Test Results Summary")
    print("=" * 60)
    passed = sum(1 for _, r in test_results if r == "PASS")
    failed = sum(1 for _, r in test_results if r.startswith("FAIL"))
    total = len(test_results)

    for name, result in test_results:
        status = "[OK]" if result == "PASS" else "[NG]"
        print(f"  {status} {name}: {result}")

    print(f"\nTotal: {total} | Passed: {passed} | Failed: {failed}")

    if failed > 0:
        sys.exit(1)
    else:
        print("\nAll tests passed!")
        sys.exit(0)

if __name__ == "__main__":
    main()
