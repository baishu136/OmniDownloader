"""
OmniDownloader Windows 快捷启动与开机自启动管理工具
支持：
1. 配置/取消开机自启动 (同步支持 Windows 启动文件夹快捷方式与注册表 Run 键)
2. 创建桌面快捷方式 (带高分辨率专属图标，无黑框静默启动)
3. 注册 omni:// 浏览器伪协议
"""

import os
import sys
import winreg
import argparse
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()
VBS_PATH = BASE_DIR / "启动网页版(纯后台无黑框).vbs"
EXE_PATH = BASE_DIR / "dist" / "OmniDownloader_Web" / "OmniDownloader_Web.exe"
ICON_PATH = BASE_DIR / "app.ico"


def get_target_path():
    """获取启动目标路径（优先使用打包好的独立 EXE，其次使用 VBS 静默脚本）"""
    if EXE_PATH.exists():
        return str(EXE_PATH)
    return str(VBS_PATH)


def get_startup_folder() -> Path:
    """获取当前用户的 Windows 启动文件夹 (Startup)"""
    appdata = os.environ.get("APPDATA")
    if appdata:
        return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"
    return Path(os.environ["USERPROFILE"]) / "AppData" / "Roaming" / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"


def create_shortcut(shortcut_path: Path, target: str, description: str = "OmniDownloader", arguments: str = ""):
    """使用 Windows WScript.Shell 原生创建快捷方式 (.lnk)"""
    shortcut_path.parent.mkdir(parents=True, exist_ok=True)
    icon_line = f'sc.IconLocation = "{str(ICON_PATH)},0"' if ICON_PATH.exists() else ""
    arg_line = f'sc.Arguments = "{arguments}"' if arguments else ""

    vbs_script = f"""
    Set ws = CreateObject("WScript.Shell")
    Set sc = ws.CreateShortcut("{str(shortcut_path)}")
    sc.TargetPath = "{target}"
    sc.WorkingDirectory = "{str(BASE_DIR)}"
    {arg_line}
    {icon_line}
    sc.Description = "{description}"
    sc.Save
    """
    temp_vbs = BASE_DIR / "_temp_sc.vbs"
    try:
        with open(temp_vbs, "w", encoding="gbk") as f:
            f.write(vbs_script)
        os.system(f'cscript //nologo "{temp_vbs}"')
    finally:
        if temp_vbs.exists():
            try:
                temp_vbs.unlink()
            except Exception:
                pass


def create_desktop_shortcut():
    """在桌面创建应用快捷方式"""
    desktop = Path(os.environ["USERPROFILE"]) / "Desktop"
    shortcut_path = desktop / "OmniDownloader 网页版.lnk"
    target = get_target_path()
    create_shortcut(shortcut_path, target, "OmniDownloader 全能音视频下载器 (网页版)")
    print(f"[OK] 桌面快捷方式已创建: {shortcut_path}")


def get_start_menu_folder() -> Path:
    """获取当前用户的 Windows 开始菜单程序文件夹 (Start Menu\\Programs)"""
    appdata = os.environ.get("APPDATA")
    if appdata:
        return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs"
    return Path(os.environ["USERPROFILE"]) / "AppData" / "Roaming" / "Microsoft" / "Windows" / "Start Menu" / "Programs"


def create_start_menu_shortcut():
    """在开始菜单常驻应用列表中创建快捷方式"""
    programs_dir = get_start_menu_folder()
    
    # 1. 根程序列表中的快捷方式
    root_lnk = programs_dir / "OmniDownloader 网页版.lnk"
    target = get_target_path()
    create_shortcut(root_lnk, target, "OmniDownloader 全能音视频下载器 (网页版)")
    print(f"[OK] 开始菜单快捷方式已创建: {root_lnk}")

    # 2. 文件夹分组中的快捷方式
    group_dir = programs_dir / "OmniDownloader"
    group_lnk = group_dir / "OmniDownloader 网页版.lnk"
    create_shortcut(group_lnk, target, "OmniDownloader 全能音视频下载器 (网页版)")
    print(f"[OK] 开始菜单分组快捷方式已创建: {group_lnk}")

    # 3. 如果存在桌面版 EXE，也一并加入分组
    desktop_exe = BASE_DIR / "dist" / "OmniDownloader_Desktop" / "OmniDownloader_Desktop.exe"
    if desktop_exe.exists():
        desktop_lnk = group_dir / "OmniDownloader 桌面独立版.lnk"
        create_shortcut(desktop_lnk, str(desktop_exe), "OmniDownloader 桌面独立客户端")
        print(f"[OK] 开始菜单桌面客户端快捷方式已创建: {desktop_lnk}")

    # 4. 尝试通过 PowerShell 钉选到开始菜单 (Pin to Start)
    try:
        ps_cmd = f"""
        $shell = New-Object -ComObject Shell.Application
        $folder = $shell.Namespace('{str(programs_dir)}')
        $item = $folder.ParseName('OmniDownloader 网页版.lnk')
        if ($item) {{
            $verb = $item.Verbs() | Where-Object {{ $_.Name.Replace('&', '') -match '固定到“开始”屏幕|Pin to Start' }}
            if ($verb) {{ $verb.DoIt() }}
        }}
        """
        import subprocess
        subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd], capture_output=True, check=False)
    except Exception:
        pass


def is_autostart_enabled() -> bool:
    """检查是否已开启开机自启动"""
    # 1. 检查 Startup 快捷方式
    startup_lnk = get_startup_folder() / "OmniDownloader 网页版.lnk"
    if startup_lnk.exists():
        return True

    # 2. 检查注册表 Run 键
    key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_READ)
        for name in ("OmniDownloader_Web", "OmniDownloaderWeb"):
            try:
                val, _ = winreg.QueryValueEx(key, name)
                if val:
                    winreg.CloseKey(key)
                    return True
            except FileNotFoundError:
                pass
        winreg.CloseKey(key)
    except Exception:
        pass

    return False


def enable_autostart(enable: bool = True):
    """设置或取消开机自启动（同时维护 Startup 目录快捷方式与注册表 Run 键）"""
    startup_dir = get_startup_folder()
    startup_lnk = startup_dir / "OmniDownloader 网页版.lnk"
    old_startup_lnk = startup_dir / "OmniDownloaderWeb.lnk"
    key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
    target = get_target_path()

    if enable:
        # 1. 在 Startup 目录创建带图标的开机启动快捷方式
        create_shortcut(
            startup_lnk,
            target,
            description="OmniDownloader 网页版后台服务 (开机自启)",
            arguments="--autostart"
        )
        print(f"[OK] 启动项快捷方式已写入: {startup_lnk}")

        # 2. 写入注册表 Run 键 (开机带 --autostart 静默常驻)
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_ALL_ACCESS)
            # 清理旧名称
            try:
                winreg.DeleteValue(key, "OmniDownloaderWeb")
            except FileNotFoundError:
                pass
            winreg.SetValueEx(key, "OmniDownloader_Web", 0, winreg.REG_SZ, f'"{target}" --autostart')
            winreg.CloseKey(key)
            print("[OK] 注册表自启动项已更新: HKCU\\...\\Run -> OmniDownloader_Web")
        except Exception as e:
            print(f"[警告] 写入注册表失败: {e}")

        print("\n>>> 【开机自启动配置成功】<<<")
        print("以后电脑开机后，OmniDownloader 将自动在后台静默运行并常驻托盘，随时打开浏览器均可极速使用！")

    else:
        # 1. 移除 Startup 快捷方式
        if startup_lnk.exists():
            try:
                startup_lnk.unlink()
                print(f"[OK] 已移除启动项快捷方式: {startup_lnk}")
            except Exception as e:
                print(f"[警告] 移除快捷方式失败: {e}")

        if old_startup_lnk.exists():
            try:
                old_startup_lnk.unlink()
            except Exception:
                pass

        # 2. 移除注册表项
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_ALL_ACCESS)
            for name in ("OmniDownloader_Web", "OmniDownloaderWeb"):
                try:
                    winreg.DeleteValue(key, name)
                    print(f"[OK] 已删除注册表项: {name}")
                except FileNotFoundError:
                    pass
            winreg.CloseKey(key)
        except Exception as e:
            print(f"[警告] 删除注册表项失败: {e}")

        print("\n>>> 【开机自启动已成功取消】<<<")


def register_url_protocol():
    """注册 omni:// 浏览器伪协议"""
    target = get_target_path()
    try:
        key = winreg.CreateKey(winreg.HKEY_CURRENT_USER, r"Software\Classes\omni")
        winreg.SetValueEx(key, "", 0, winreg.REG_SZ, "URL:OmniDownloader Protocol")
        winreg.SetValueEx(key, "URL Protocol", 0, winreg.REG_SZ, "")

        cmd_key = winreg.CreateKey(key, r"shell\open\command")
        winreg.SetValueEx(cmd_key, "", 0, winreg.REG_SZ, f'"{target}"')

        winreg.CloseKey(cmd_key)
        winreg.CloseKey(key)
        print("[OK] omni:// 协议注册成功！")
    except Exception as e:
        print(f"[Error] 注册协议失败: {e}")


def main():
    parser = argparse.ArgumentParser(description="OmniDownloader 自启动与快捷方式管理")
    parser.add_argument("--autostart", choices=["on", "off"], help="开启或关闭开机自启动")
    parser.add_argument("--desktop", action="store_true", help="创建桌面快捷方式")
    parser.add_argument("--startmenu", action="store_true", help="创建开始菜单快捷方式并常驻")
    parser.add_argument("--protocol", action="store_true", help="注册 omni:// 伪协议")
    parser.add_argument("--status", action="store_true", help="查询当前开机自启动状态")
    args = parser.parse_args()

    if args.status:
        enabled = is_autostart_enabled()
        print(f"AUTOSTART_STATUS: {'ENABLED' if enabled else 'DISABLED'}")
        return

    if args.autostart == "on":
        enable_autostart(True)
    elif args.autostart == "off":
        enable_autostart(False)

    if args.desktop:
        create_desktop_shortcut()

    if args.startmenu:
        create_start_menu_shortcut()

    if args.protocol:
        register_url_protocol()

    if not any([args.autostart, args.desktop, args.startmenu, args.protocol, args.status]):
        # 默认一键全配置
        create_desktop_shortcut()
        create_start_menu_shortcut()
        register_url_protocol()
        enable_autostart(True)


if __name__ == "__main__":
    main()
