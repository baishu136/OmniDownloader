"""
OmniDownloader 链接嗅探器 (Windows 移植版)
从任意文本中提取合法 URL，识别媒体平台，跟随网络重定向解析真实地址
"""

import re
import json
import base64
import asyncio
from typing import Optional, Tuple
import requests

URL_REGEX = re.compile(
    r"https?://[a-zA-Z0-9_\-.]+(?::[0-9]+)?(?:/[^\s]*)?",
    re.IGNORECASE
)

MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


class UrlSniffer:
    """链接提取、识别与规范化"""

    @classmethod
    def extract_url(cls, raw_text: str) -> Optional[str]:
        """从剪贴板或杂乱文本中提取首个合法 URL 并清理标点"""
        if not raw_text:
            return None
        match = URL_REGEX.search(raw_text)
        if match:
            url = match.group(0).strip()
            # 清理末尾常见的中英文标点或括号
            trailing_chars = ")]}。，！；,?>\"'”’）】"
            while url and url[-1] in trailing_chars:
                url = url[:-1]
            return url
        return None

    @classmethod
    def identify_site(cls, url: str) -> str:
        """识别媒体平台归属"""
        from engine.platforms import platform
        return platform(url)

    @classmethod
    async def sanitize_and_resolve_url(cls, raw_url: str, proxy_url: str = "") -> str:
        """
        规范化并解析 URL（包含短链跟随重定向）
        """
        from engine.platforms import normalize, http, page, platform
        clean = normalize(raw_url.strip())
        from urllib.parse import urlsplit
        host = (urlsplit(clean).hostname or '').lower()
        short = host in ('b23.tv', 'v.douyin.com', 'v.kuaishou.com', 'xhslink.com', 'xhslink.cn', 't.co', 'vm.tiktok.com', 'vt.tiktok.com', 'pin.it', 'fb.watch', 'fb.me')
        if not short: return clean
        def resolve():
            session = http(platform(clean))
            return normalize(page(session, clean).url)
        return await asyncio.to_thread(resolve)

    @classmethod
    def unpack_direct_media_url(cls, raw_url: str, default_title: str = "中转下载视频") -> Tuple[str, str]:
        """
        解析中转站提取的直链或携带 JWT Payload (如 SnapCDN/X2Twitter/TwitterSaver) 的长链接
        解码出真实的视频直链与规范的文件名，若无法解码则原样返回
        """
        trimmed = raw_url.strip()
        try:
            token_idx = trimmed.find("token=")
            if token_idx != -1:
                token_val = trimmed[token_idx + 6:]
                amp_idx = token_val.find("&")
                if amp_idx != -1:
                    token_val = token_val[:amp_idx]
                if token_val.startswith("eyJ") and "." in token_val:
                    parts = token_val.split(".")
                    if len(parts) >= 2:
                        payload = parts[1]
                        # 补齐 Base64 padding
                        padding = len(payload) % 4
                        if padding:
                            payload += "=" * (4 - padding)
                        decoded_bytes = base64.urlsafe_b64decode(payload.encode("utf-8"))
                        data = json.loads(decoded_bytes.decode("utf-8"))
                        real_url = data.get("url", "")
                        filename = data.get("filename", "")
                        title = filename.rsplit(".", 1)[0] if filename else default_title
                        if real_url and real_url.startswith("http"):
                            if cls.is_m3u8_url(real_url) and ("snapcdn" in trimmed or "get?token" in trimmed):
                                return trimmed, title
                            return real_url, title
        except Exception:
            pass
        return trimmed, default_title

    @classmethod
    def is_m3u8_url(cls, url: str) -> bool:
        """判断是否属于 M3U8 (HLS 分片索引流)"""
        lower = url.lower()
        return (
            lower.endswith(".m3u8") or ".m3u8?" in lower or
            "/hls/" in lower or "format=m3u8" in lower or
            ".m3u8/" in lower
        )

    @classmethod
    def is_direct_media_url(cls, url: str) -> bool:
        """判断是否属于网络媒体直链"""
        lower = url.lower()

        # 核心防御：严禁将网站内部的 API 接口调用误判为媒体直链（防止向仅支持 POST 的接口发 GET 导致 405 报错）
        is_api_route = (
            "/api/" in lower or "/ajax/" in lower or
            "/extract/" in lower or "/video-tool" in lower or
            "cnsimpleextract" in lower or "dodownload" in lower or
            "getdownloadinfo" in lower
        )
        is_explicit_media_ext = (
            lower.endswith(".mp4") or ".mp4?" in lower or
            lower.endswith(".m4a") or ".m4a?" in lower or
            lower.endswith(".mp3") or ".mp3?" in lower or
            lower.endswith(".webm") or ".webm?" in lower or
            lower.endswith(".flv") or ".flv?" in lower or
            lower.endswith(".m3u8") or ".m3u8?" in lower
        )

        if is_api_route and not is_explicit_media_ext:
            return False

        return is_explicit_media_ext or any(domain in lower for domain in [
            "dl.snapcdn.app",
            "video.twimg.com",
            "snapany.com/api/download",
            "googlevideo.com/videoplayback",
            "byteoversea.com",
            "ibytedtos.com",
            "tiktokcdn.com",
            "douyinvod.com",
            "snssdk.com",
            "yximgs.com",
            "xhscdn.com",
            "fbcdn.net",
            "twcdn.net"
        ])

