"""
OmniDownloader FastAPI 服务主程序 (Windows 移植版)
提供 REST API、SSE 任务实时状态推流及静态 Web UI 托管
"""

import os
import sys

# 针对 Windows GUI / 无控制台纯后台模式，保护标准流，杜绝 [Errno 22] Invalid argument
if sys.stdout is None or not hasattr(sys.stdout, "write"):
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None or not hasattr(sys.stderr, "write"):
    sys.stderr = open(os.devnull, "w", encoding="utf-8")
if sys.stdin is None:
    sys.stdin = open(os.devnull, "r", encoding="utf-8")

import json
import html
import re
import urllib.parse
import asyncio
from pathlib import Path
from typing import List, Literal, Optional, Dict, Any

from fastapi import FastAPI, HTTPException, Request, BackgroundTasks
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse, FileResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn

# 确保将当前 windows 目录加入模块搜索路径
CURRENT_DIR = Path(__file__).parent.resolve()
if str(CURRENT_DIR) not in sys.path:
    sys.path.insert(0, str(CURRENT_DIR))

from core.models import VideoMetadata, DownloadTask, DownloadType, AudioFormat, TaskStatus
from core.config import config
from core.task_manager import task_manager
from engine.url_sniffer import UrlSniffer
from engine.bilibili_extractor import BilibiliDirectExtractor
from engine.domestic_extractor import DomesticDirectExtractor
from engine.tiktok_extractor import TikTokDirectExtractor
from engine.ytdlp_engine import YtDlpEngine

app = FastAPI(title="OmniDownloader API", description="全能多媒体下载器 Windows 后端接口", version="1.0.0")

# 允许跨域以便调试
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# === 请求数据模型 ===
class AnalyzeRequest(BaseModel):
    url: str


class CreateTaskItem(BaseModel):
    url: str
    title: str
    download_type: DownloadType = Field(..., alias="downloadType")
    selected_resolution: str = Field("", alias="selectedResolution")
    audio_format: AudioFormat = Field(AudioFormat.MP3, alias="audioFormat")
    thumbnail_url: str = Field("", alias="thumbnailUrl")
    author: str = ""
    collection_id: Optional[str] = Field(None, alias="collectionId")
    collection_title: Optional[str] = Field(None, alias="collectionTitle")
    episode_index: int = Field(0, alias="episodeIndex")
    episode_total: int = Field(0, alias="episodeTotal")

    class Config:
        populate_by_name = True


class BatchCreateTaskRequest(BaseModel):
    tasks: List[CreateTaskItem]


class UpdateSettingsRequest(BaseModel):
    download_dir: Optional[str] = Field(None, alias="downloadDir")
    proxy_url: Optional[str] = Field(None, alias="proxyUrl")
    bilibili_cookie: Optional[str] = Field(None, alias="bilibiliCookie")
    max_concurrent_tasks: Optional[int] = Field(None, alias="maxConcurrentTasks")
    theme: Optional[Literal["auto", "light", "dark"]] = None
    auto_start: Optional[bool] = Field(None, alias="autoStart")

class AddRelaySiteRequest(BaseModel):
    name: str
    url: str


# === API 路由 ===

@app.get("/api/relay-sites")
async def get_relay_sites():
    """获取所有备用中转网站列表及兼容性提示状态"""
    return {
        "relaySites": config.relay_sites,
        "hasShownRelayCompatTip": config.has_shown_relay_compat_tip
    }


@app.post("/api/relay-sites")
async def add_relay_site(req: AddRelaySiteRequest):
    """添加备用中转网站"""
    name = req.name.strip()
    url = req.url.strip()
    if not name or not url:
        raise HTTPException(status_code=400, detail="网站名称与网址不能为空")
    site = config.add_relay_site(name, url)
    return {"status": "success", "site": site, "relaySites": config.relay_sites}


@app.delete("/api/relay-sites/{site_id}")
async def delete_relay_site(site_id: str):
    """删除指定的备用中转网站"""
    config.remove_relay_site(site_id)
    return {"status": "success", "relaySites": config.relay_sites}


@app.post("/api/relay-sites/confirm-tip")
async def confirm_relay_compat_tip():
    """标记已阅读第三方兼容性与免责声明提示"""
    config.confirm_relay_compat_tip()
    return {"status": "success", "hasShownRelayCompatTip": True}


def _fetch_web_title_sync(raw_url: str, proxy_url: str = "") -> str:
    """流式拉取网页头部前 64KB 解析 title 标签（对齐 Android 端 WebTitleFetcher）"""
    trimmed = raw_url.strip()
    if not trimmed:
        return ""
    if not trimmed.startswith("http://") and not trimmed.startswith("https://"):
        trimmed = f"https://{trimmed}"

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"
    }
    proxies = {"http": proxy_url, "https": proxy_url} if proxy_url else None

    def _fallback_host():
        try:
            parsed = urllib.parse.urlparse(trimmed)
            host = (parsed.hostname or "").lower()
            if host.startswith("www."):
                host = host[4:]
            if host:
                parts = host.split(".")
                return parts[0].capitalize() if parts else "备用网站"
        except Exception:
            pass
        return "备用网站"

    try:
        import requests
        with requests.get(trimmed, headers=headers, proxies=proxies, timeout=6, stream=True, allow_redirects=True) as resp:
            if not resp.ok:
                return _fallback_host()

            raw_bytes = bytearray()
            max_bytes = 64 * 1024
            for chunk in resp.iter_content(chunk_size=4096):
                raw_bytes.extend(chunk)
                if len(raw_bytes) >= max_bytes:
                    break
                preview = raw_bytes.decode("utf-8", errors="ignore").lower()
                if "</title>" in preview or "</head>" in preview:
                    break

            encoding = resp.encoding or "utf-8"
            if not resp.encoding or resp.encoding.lower() == "iso-8859-1":
                meta_match = re.search(r'charset=["\']?([a-zA-Z0-9_\-]+)', raw_bytes[:4096].decode("latin-1", errors="ignore"), re.I)
                if meta_match:
                    encoding = meta_match.group(1).strip()

            try:
                html_text = raw_bytes.decode(encoding, errors="replace")
            except Exception:
                html_text = raw_bytes.decode("utf-8", errors="replace")

            title_match = re.search(r'<title[^>]*>(.*?)</title>', html_text, re.IGNORECASE | re.DOTALL)
            if title_match:
                extracted = title_match.group(1).strip()
                clean_title = html.unescape(extracted)
                clean_title = re.sub(r'[\r\n\t]+', ' ', clean_title).strip()
                lower_t = clean_title.lower()
                if not clean_title or any(bad in lower_t for bad in ["404 not found", "attention required", "just a moment", "security check", "robot check"]):
                    return _fallback_host()
                return clean_title[:50]
            return _fallback_host()
    except Exception:
        return _fallback_host()


@app.get("/api/relay-sites/fetch-title")
async def fetch_relay_site_title(url: str):
    """异步抓取并解析目标网页的 Title，用于添加中转网站时自动回填名称"""
    if not url or not url.strip():
        raise HTTPException(status_code=400, detail="URL 不能为空")
    title = await asyncio.to_thread(_fetch_web_title_sync, url.strip(), config.proxy_url)
    return {"status": "success", "title": title}



@app.post("/api/analyze", response_model=VideoMetadata)
async def analyze_url(req: AnalyzeRequest):
    """解析任意平台媒体链接，返回视频标题、封面、清晰度列表与分P分集"""
    raw_url = req.url.strip()
    if not raw_url:
        raise HTTPException(status_code=400, detail="链接不能为空")

    extracted_url = UrlSniffer.extract_url(raw_url) or raw_url
    clean_url = await UrlSniffer.sanitize_and_resolve_url(extracted_url, config.proxy_url)
    site = UrlSniffer.identify_site(clean_url)

    # 0. 前置防呆：防止误将中转解析站本身网址当成视频源解析
    lower_clean = clean_url.lower()
    is_relay_site_home = any(
        site_item.get("url", "").lower().rstrip("/") == lower_clean.rstrip("/")
        for site_item in config.relay_sites
    ) or any(
        k in lower_clean for k in ["greenvideo.cc", "x2twitter.com", "snapany.com"]
    )
    if is_relay_site_home and not UrlSniffer.is_direct_media_url(clean_url):
        raise HTTPException(
            status_code=400,
            detail="检测到您输入的是中转解析网站主页，请粘贴欲解析的具体视频链接，或在中转站板块中使用该站点。"
        )

    # 0.1 若为中转直链或直接音视频媒体流，解包后直接生成元数据
    unpacked_url, unpacked_title = UrlSniffer.unpack_direct_media_url(clean_url, "中转视频")
    if UrlSniffer.is_direct_media_url(unpacked_url):
        from core.models import FormatOption
        return VideoMetadata(
            url=unpacked_url,
            title=unpacked_title,
            author="中转直链",
            thumbnail_url="",
            site_name="网络直链",
            is_gif=False,
            available_video_formats=[
                FormatOption(
                    format_id="direct",
                    resolution_label="中转直链",
                    ext="mp4",
                    note="中转提取高清原片"
                )
            ]
        )

    print(f"[OmniServer] 开始解析链接: site={site}, url={clean_url}")

    # 1. 哔哩哔哩原生解析
    if site == "哔哩哔哩":
        try:
            return await BilibiliDirectExtractor.extract(clean_url)
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"B站解析异常: {str(e)}")

    # 2. 国内平台（抖音、快手、小红书）原生极速直连
    if DomesticDirectExtractor.is_supported(site):
        try:
            return await DomesticDirectExtractor.extract(clean_url, site)
        except Exception as e:
            print(f"[OmniServer] {site} 原生解析失败，降级通用引擎: {e}")

    # 3. TikTok 原生解析
    if site == "TikTok" or TikTokDirectExtractor.is_supported(clean_url):
        try:
            return await TikTokDirectExtractor.extract(clean_url)
        except Exception as e:
            print(f"[OmniServer] TikTok 原生解析失败，降级通用引擎: {e}")

    # 4. 通用与海外主流平台走 yt-dlp 引擎
    try:
        return await YtDlpEngine.extract(clean_url, site)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"解析媒体资源失败: {str(e)}")


@app.post("/api/download")
async def create_download_tasks(req: BatchCreateTaskRequest):
    """批量提交下载任务（支持单集与合集多选）"""
    created_tasks = []
    for item in req.tasks:
        task = await task_manager.add_task(
            url=item.url,
            title=item.title,
            download_type=item.download_type,
            selected_resolution=item.selected_resolution,
            audio_format=item.audio_format,
            thumbnail_url=item.thumbnail_url,
            author=item.author,
            collection_id=item.collection_id,
            collection_title=item.collection_title,
            episode_index=item.episode_index,
            episode_total=item.episode_total
        )
        created_tasks.append(task.model_dump(by_alias=True))
    return {"status": "success", "count": len(created_tasks), "tasks": created_tasks}


@app.get("/api/tasks")
async def get_tasks():
    """获取全部下载任务列表"""
    tasks = task_manager.get_all_tasks()
    return [t.model_dump(by_alias=True) for t in tasks]


@app.post("/api/tasks/{task_id}/cancel")
async def cancel_task(task_id: str):
    """取消下载任务"""
    await task_manager.cancel_task(task_id)
    return {"status": "success"}


@app.delete("/api/tasks/{task_id}")
async def delete_task(task_id: str, deleteFile: bool = False):
    """删除任务记录（可指定是否同时删除已下载文件）"""
    await task_manager.delete_task(task_id, delete_file=deleteFile)
    return {"status": "success"}


@app.post("/api/tasks/clear")
@app.delete("/api/tasks/clear")
async def clear_tasks(clearAll: bool = False):
    """一键清空下载记录（默认仅清空已完成/失败/已取消的任务，clearAll=True 时清空全部任务）"""
    await task_manager.clear_all_tasks(clear_only_finished=not clearAll)
    return {"status": "success"}


@app.post("/api/tasks/{task_id}/open")
async def open_file(task_id: str):
    """在 Windows 资源管理器中高亮定位文件"""
    ok = task_manager.open_file_in_explorer(task_id)
    if not ok:
        raise HTTPException(status_code=404, detail="文件不存在或尚未下载完成")
    return {"status": "success"}


@app.get("/api/tasks/{task_id}/download")
async def download_task_file(task_id: str):
    """供浏览器直接下载文件流（支持 PC 浏览器与局域网手机/平板原生保存）"""
    from urllib.parse import quote
    task = task_manager.get_task(task_id)
    if not task or not task.local_file_path or not os.path.exists(task.local_file_path):
        raise HTTPException(status_code=404, detail="文件不存在或尚未下载完成")

    file_path = Path(task.local_file_path)
    file_name = file_path.name
    encoded_name = quote(file_name)
    headers = {
        "Content-Disposition": f"attachment; filename*=UTF-8''{encoded_name}"
    }
    return FileResponse(
        path=str(file_path),
        filename=file_name,
        headers=headers
    )


@app.post("/api/open-folder")
async def open_download_folder():
    """打开下载保存主目录"""
    ok = task_manager.open_download_folder()
    return {"status": "success" if ok else "failed"}


@app.get("/api/settings")
async def get_settings():
    """获取当前系统配置"""
    res = config.to_dict()
    try:
        from setup_shortcuts import is_autostart_enabled
        res["autoStart"] = is_autostart_enabled()
    except Exception:
        res["autoStart"] = False
    return res


@app.post("/api/settings")
async def update_settings(req: UpdateSettingsRequest):
    """更新配置"""
    if req.download_dir is not None:
        config.download_dir = req.download_dir
        os.makedirs(config.download_dir, exist_ok=True)
    if req.proxy_url is not None:
        config.proxy_url = req.proxy_url
    if req.bilibili_cookie is not None:
        config.bilibili_cookie = req.bilibili_cookie
    if req.max_concurrent_tasks is not None:
        config.max_concurrent_tasks = req.max_concurrent_tasks
    if req.theme is not None:
        config.theme = req.theme
    if req.auto_start is not None:
        try:
            from setup_shortcuts import enable_autostart
            enable_autostart(req.auto_start)
        except Exception as e:
            print(f"[OmniServer] 设置自启动失败: {e}")

    config.save()
    updated = config.to_dict()
    try:
        from setup_shortcuts import is_autostart_enabled
        updated["autoStart"] = is_autostart_enabled()
    except Exception:
        updated["autoStart"] = False
    return {"status": "success", "config": updated}


@app.get("/api/events")
async def sse_events(request: Request):
    """SSE 实时任务推流，推送下载进度与状态变动"""
    queue = await task_manager.register_listener()

    async def event_generator():
        try:
            # 初始发送连接就绪消息
            yield f"data: {json.dumps({'type': 'connected'})}\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    payload = await asyncio.wait_for(queue.get(), timeout=20.0)
                    yield f"data: {json.dumps(payload)}\n\n"
                except asyncio.TimeoutError:
                    # 心跳保活
                    yield f": keepalive\n\n"
        finally:
            task_manager.unregister_listener(queue)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )


@app.get("/api/system/client-status")
async def get_client_status():
    """查询是否有活跃打开的网页客户端页面"""
    count = task_manager.get_active_client_count()
    return {
        "status": "running",
        "activeClients": count,
        "hasActivePage": count > 0
    }


@app.post("/api/system/activate-page")
async def activate_client_page():
    """向所有已打开的浏览器页面下发 focus_page 唤醒/置顶通知"""
    count = task_manager.get_active_client_count()
    if count > 0:
        await task_manager.broadcast_event({"type": "focus_page"})
    return {
        "status": "success",
        "activeClients": count,
        "hasActivePage": count > 0
    }


# 挂载 Web UI 静态目录
def get_web_dir() -> Path:
    if getattr(sys, 'frozen', False):
        if hasattr(sys, '_MEIPASS'):
            p = Path(sys._MEIPASS) / "web"
            if p.exists():
                return p
        p2 = Path(sys.executable).parent / "_internal" / "web"
        if p2.exists():
            return p2
        p3 = Path(sys.executable).parent / "web"
        if p3.exists():
            return p3
    return CURRENT_DIR / "web"

web_dir = get_web_dir()
web_dir.mkdir(parents=True, exist_ok=True)
static_dir = web_dir / "static"
static_dir.mkdir(parents=True, exist_ok=True)

from fastapi.staticfiles import StaticFiles
app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")


@app.get("/favicon.ico", include_in_schema=False)
async def get_favicon():
    fav = static_dir / "favicon.ico"
    if not fav.exists():
        fav = web_dir / "favicon.ico"
    if fav.exists():
        return FileResponse(fav)
    return Response(status_code=404)


@app.get("/", response_class=HTMLResponse)
async def serve_index():
    index_file = web_dir / "index.html"
    if index_file.exists():
        return FileResponse(index_file)
    return HTMLResponse("<h2>OmniDownloader Web UI 正在初始化...</h2>")


def start_server(host: str = "127.0.0.1", port: int = 58000):
    """启动本地服务"""
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="OmniDownloader Windows 服务端")
    parser.add_argument("--host", type=str, default="127.0.0.1", help="监听地址 (0.0.0.0 支持局域网)")
    parser.add_argument("--port", type=int, default=58000, help="服务端口")
    args = parser.parse_args()

    print(f"=====================================================")
    print(f"🚀 OmniDownloader Windows 服务已启动!")
    print(f"👉 本地访问地址: http://{args.host}:{args.port}")
    print(f"👉 默认下载目录: {config.download_dir}")
    print(f"=====================================================")
    start_server(host=args.host, port=args.port)
