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
        lower = url.lower()
        if "bilibili.com" in lower or "b23.tv" in lower or lower.startswith("bv") or lower.startswith("av"):
            return "哔哩哔哩"
        if "youtube.com" in lower or "youtu.be" in lower:
            return "YouTube"
        if "twitter.com" in lower or "x.com" in lower:
            return "X (Twitter)"
        if "tiktok.com" in lower:
            return "TikTok"
        if "douyin.com" in lower or "iesdouyin.com" in lower:
            return "抖音"
        if "kuaishou.com" in lower or "kwai.com" in lower or "gifshow.com" in lower:
            return "快手"
        if "xiaohongshu.com" in lower or "xhslink.com" in lower or "rednote.com" in lower:
            return "小红书"
        if "instagram.com" in lower or "instagr.am" in lower:
            return "Instagram"
        if "facebook.com" in lower or "fb.watch" in lower or "fb.me" in lower:
            return "Facebook"
        if "pinterest.com" in lower or "pin.it" in lower:
            return "Pinterest"
        return "网络视频"

    @classmethod
    async def sanitize_and_resolve_url(cls, raw_url: str, proxy_url: str = "") -> str:
        """
        规范化并解析 URL（包含短链跟随重定向）
        """
        clean_url = raw_url.strip()
        if not clean_url:
            return ""

        # 若用户直接输入 BV 号
        bv_match = re.search(r"BV[a-zA-Z0-9]{10}", clean_url, re.IGNORECASE)
        if bv_match and "bilibili.com" not in clean_url and "b23.tv" not in clean_url:
            return f"https://www.bilibili.com/video/{bv_match.group(0)}"

        # 判断是否为常见需要重定向的短链
        is_short_link = any(domain in clean_url for domain in [
            "b23.tv", "v.douyin.com", "v.kuaishou.com", "xhslink.com",
            "vm.tiktok.com", "vt.tiktok.com", "pin.it", "fb.watch", "fb.me"
        ])

        if not is_short_link:
            return clean_url

        # 在子线程中执行网络追踪
        def _resolve() -> str:
            ua = DESKTOP_UA if any(d in clean_url for d in ["xhslink.com", "b23.tv", "pin.it", "pinterest.com"]) else MOBILE_UA
            headers = {"User-Agent": ua}
            proxies = None
            if proxy_url:
                proxies = {"http": proxy_url, "https": proxy_url}

            try:
                # 不自动跟随重定向，手动探测最多 10 跳以获取真实最终目标
                current = clean_url
                for _ in range(10):
                    resp = requests.head(current, headers=headers, proxies=proxies, timeout=8, allow_redirects=False)
                    if 300 <= resp.status_code < 400 and "Location" in resp.headers:
                        loc = resp.headers["Location"]
                        if loc.startswith("/"):
                            # 相对路径拼接
                            from urllib.parse import urljoin
                            current = urljoin(current, loc)
                        else:
                            current = loc
                    else:
                        break
                return current
            except Exception as e:
                print(f"[UrlSniffer] 解析短链重定向提示: {e}")
                return clean_url

        return await asyncio.to_thread(_resolve)

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

