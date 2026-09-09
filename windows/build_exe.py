"""
OmniDownloader Windows 多版本构建总调度入口
支持：
- 桌面独立客户端版 (Desktop App)
- 网页纯后台服务版 (Web App)
- 双版本一键联合构建
"""

import sys
import argparse
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import build_web
import build_desktop


def main():
    parser = argparse.ArgumentParser(description="OmniDownloader 自动化打包工具")
    parser.add_argument(
        "--mode",
        choices=["desktop", "web", "all"],
        default="all",
        help="打包目标: desktop(桌面独立版), web(网页纯后台版), all(双版本全部打包，默认)"
    )
    args = parser.parse_args()

    print("=" * 65)
    print("OmniDownloader 多形态打包总调度工程")
    print(f"当前选定目标: {args.mode}")
    print("=" * 65 + "\n")

    if args.mode in ("web", "all"):
        build_web.main()

    if args.mode in ("desktop", "all"):
        build_desktop.main()

    print("\n" + "=" * 65)
    print("【全部打包流程结束】")
    print(f"打包成品发布目录: {BASE_DIR / 'release'}")
    print("=" * 65)


if __name__ == "__main__":
    main()
