/* Web 版原生 HTML5 视频/音频内嵌播放弹层（支持合集连播、音频封面展示与统一任务媒体分发） */
import { toast, escapeHtml } from './ui.js';
import { openImageViewer, openImageCollectionViewer, openImageZipViewer } from './images.js';

const AUDIO_EXTS = ['.mp3', '.m4a', '.flac', '.opus', '.ogg', '.wav', '.aac'];
const IMAGE_EXTS = ['.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.avif'];
const VIDEO_EXTS = ['.mp4', '.m4v', '.webm', '.mkv', '.mov', '.avi', '.flv'];

const mediaState = {
  dialog: null,
  items: [],
  index: 0,
};

function fileExt(path) {
  const clean = String(path || '').split(/[?#]/)[0].toLowerCase();
  const dot = clean.lastIndexOf('.');
  return dot >= 0 ? clean.slice(dot) : '';
}

export function isImageLikeTask(task) {
  if (!task) return false;
  const ext = fileExt(task.localFilePath);
  if (task.downloadType === 'IMAGE_ZIP' || ext === '.zip') return true;
  if (VIDEO_EXTS.includes(ext)) return false;
  if (AUDIO_EXTS.includes(ext)) return false;
  if (IMAGE_EXTS.includes(ext)) return true;
  return ['IMAGE', 'COVER', 'GIF'].includes(task.downloadType);
}

export function isAudioLikeTask(task) {
  if (!task) return false;
  const ext = fileExt(task.localFilePath);
  if (AUDIO_EXTS.includes(ext)) return true;
  return task.downloadType === 'AUDIO_ONLY' && !VIDEO_EXTS.includes(ext);
}

export function openTaskMedia(task, playlistTasks = []) {
  if (!task || !task.id) return;
  const ext = fileExt(task.localFilePath);
  if (task.downloadType === 'IMAGE_ZIP' || ext === '.zip') {
    openImageZipViewer(task.id, task.title);
    return;
  }

  if (isImageLikeTask(task)) {
    const completedImages = (Array.isArray(playlistTasks) ? playlistTasks : [])
      .filter((t) => t && t.status === 'COMPLETED' && isImageLikeTask(t) && t.downloadType !== 'IMAGE_ZIP');
    if (completedImages.length > 1) {
      const idx = Math.max(0, completedImages.findIndex((t) => t.id === task.id));
      openImageCollectionViewer(completedImages, idx);
    } else {
      openImageViewer(task.id, task.title);
    }
    return;
  }

  const candidates = (Array.isArray(playlistTasks) && playlistTasks.length ? playlistTasks : [task])
    .filter((t) => t && t.status === 'COMPLETED' && !isImageLikeTask(t));
  const list = candidates.length ? candidates : [task];
  const idx = Math.max(0, list.findIndex((t) => t.id === task.id));
  openMediaPlayer(list, idx);
}

function ensureMediaDialog() {
  if (mediaState.dialog) return mediaState.dialog;
  const dialog = document.createElement('dialog');
  dialog.className = 'viewer-dialog viewer-dialog--media';
  dialog.innerHTML = `
    <div class="viewer__layout">
      <div class="viewer__bar">
        <span data-media-title class="truncate" style="flex:1;font-weight:600"></span>
        <span data-media-count style="color:#C6C6D0;font-size:12px"></span>
        <a data-media-ext class="btn btn-text btn-sm" target="_blank" rel="noopener">新窗口打开</a>
        <a data-media-dl class="btn btn-text btn-sm" download="">下载保存</a>
        <button class="btn btn-text btn-sm" data-media-close type="button">关闭</button>
      </div>
      <div class="viewer__stage">
        <video data-video controls playsinline preload="metadata" hidden></video>
        <div class="viewer__audio-card" data-audio-card hidden>
          <img data-audio-cover alt="" hidden>
          <div class="viewer__audio-meta">
            <div data-audio-title style="font-size:16px;font-weight:700"></div>
            <div data-audio-sub style="font-size:12.5px;color:#C6C6D0"></div>
          </div>
          <audio data-audio controls preload="metadata" style="width:100%"></audio>
        </div>
        <div data-media-error hidden style="text-align:center;color:#E3E2E8;font-size:13px">
          <p>当前格式暂不支持浏览器内嵌解码，可点击右上角下载或在系统播放器中打开。</p>
        </div>
      </div>
      <div class="viewer__foot">
        <button class="btn btn-text btn-sm" data-media-prev type="button">上一集</button>
        <span data-media-hint>Space 播放/暂停 · ←/→ 切换 · Esc 关闭</span>
        <button class="btn btn-text btn-sm" data-media-next type="button">下一集</button>
      </div>
    </div>`;
  document.body.appendChild(dialog);

  const video = dialog.querySelector('[data-video]');
  const audio = dialog.querySelector('[data-audio]');
  const errEl = dialog.querySelector('[data-media-error]');

  const onError = () => { errEl.hidden = false; };
  video.addEventListener('error', onError);
  audio.addEventListener('error', onError);

  const autoNext = () => {
    if (mediaState.index + 1 < mediaState.items.length) {
      stepMedia(1);
    }
  };
  video.addEventListener('ended', autoNext);
  audio.addEventListener('ended', autoNext);

  dialog.querySelector('[data-media-close]').addEventListener('click', () => dialog.close());
  dialog.querySelector('[data-media-prev]').addEventListener('click', () => stepMedia(-1));
  dialog.querySelector('[data-media-next]').addEventListener('click', () => stepMedia(1));
  dialog.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowLeft' && event.altKey) stepMedia(-1);
    else if (event.key === 'ArrowRight' && event.altKey) stepMedia(1);
  });
  dialog.addEventListener('close', () => {
    stopActiveStreams(dialog);
  });

  mediaState.dialog = dialog;
  return dialog;
}

function stopActiveStreams(dialog) {
  const video = dialog.querySelector('[data-video]');
  const audio = dialog.querySelector('[data-audio]');
  for (const el of [video, audio]) {
    if (!el) continue;
    try { el.pause(); } catch (e) { /* ignore */ }
    el.removeAttribute('src');
    el.load();
  }
}

function stepMedia(delta) {
  const next = mediaState.index + delta;
  if (next < 0 || next >= mediaState.items.length) return;
  mediaState.index = next;
  renderMedia();
}

function renderMedia() {
  const dialog = mediaState.dialog;
  const item = mediaState.items[mediaState.index];
  if (!dialog || !item) return;

  stopActiveStreams(dialog);

  const viewUrl = `/api/tasks/${encodeURIComponent(item.id)}/view`;
  const dlUrl = `/api/tasks/${encodeURIComponent(item.id)}/download`;
  const audioMode = isAudioLikeTask(item);

  dialog.querySelector('[data-media-title]').textContent = item.title || '媒体播放';
  dialog.querySelector('[data-media-count]').textContent = mediaState.items.length > 1
    ? `${mediaState.index + 1} / ${mediaState.items.length}`
    : '';
  dialog.querySelector('[data-media-ext]').href = viewUrl;
  dialog.querySelector('[data-media-dl]').href = dlUrl;
  dialog.querySelector('[data-media-error]').hidden = true;

  const prevBtn = dialog.querySelector('[data-media-prev]');
  const nextBtn = dialog.querySelector('[data-media-next]');
  prevBtn.style.visibility = mediaState.items.length > 1 ? 'visible' : 'hidden';
  nextBtn.style.visibility = mediaState.items.length > 1 ? 'visible' : 'hidden';
  prevBtn.disabled = mediaState.index === 0;
  nextBtn.disabled = mediaState.index === mediaState.items.length - 1;

  const video = dialog.querySelector('[data-video]');
  const audioCard = dialog.querySelector('[data-audio-card]');
  const audio = dialog.querySelector('[data-audio]');
  const cover = dialog.querySelector('[data-audio-cover]');

  if (audioMode) {
    video.hidden = true;
    audioCard.hidden = false;
    dialog.querySelector('[data-audio-title]').textContent = item.title || '音频播放';
    dialog.querySelector('[data-audio-sub]').textContent = [item.author, item.fileSizeText].filter(Boolean).join(' · ') || '本地音频文件';
    if (item.thumbnailUrl) {
      cover.src = item.thumbnailUrl;
      cover.hidden = false;
      cover.onerror = () => { cover.hidden = true; };
    } else {
      cover.hidden = true;
    }
    audio.src = viewUrl;
    audio.play().catch(() => { /* 用户交互策略阻止时保留手动点击播放 */ });
  } else {
    audioCard.hidden = true;
    video.hidden = false;
    if (item.thumbnailUrl) video.poster = item.thumbnailUrl;
    else video.removeAttribute('poster');
    video.src = viewUrl;
    video.play().catch(() => { /* 保留原生控制条点击播放 */ });
  }
}

export function openMediaPlayer(items, index = 0) {
  if (!Array.isArray(items) || !items.length) {
    toast('未找到可播放的已完成媒体', 'warning');
    return;
  }
  const dialog = ensureMediaDialog();
  mediaState.items = items;
  mediaState.index = Math.max(0, Math.min(index, items.length - 1));
  renderMedia();
  if (!dialog.open) dialog.showModal();
}
