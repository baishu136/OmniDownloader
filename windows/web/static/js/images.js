/* 图文解析结果与黑底全屏预览器（对齐 ImagesScreen.kt & ImageViewer.kt，支持合集与图集 ZIP 查看） */
import { get, post } from './api.js';
import { toast, escapeHtml } from './ui.js';
import { iconSvg } from './icons.js';
import { t } from './i18n.js';

const state = {
  post: null,
  selected: new Set(),
  viewer: null,
  viewerItems: [],
  viewerIndex: 0,
};

const $ = (id) => document.getElementById(id);

export function initImages() {
  $('btn-image-select-all')?.addEventListener('click', () => selectAll(true));
  $('btn-image-select-none')?.addEventListener('click', () => selectAll(false));
  $('btn-image-save')?.addEventListener('click', () => downloadImages(false));
  $('btn-image-zip')?.addEventListener('click', () => downloadImages(true));
  updateSelectionUi();
}

export function clearPost() {
  state.post = null;
  state.selected.clear();
  const section = $('image-result');
  if (section) section.hidden = true;
}

export function showPost(data) {
  state.post = data;
  state.selected.clear();
  (data.resources || []).forEach((item) => state.selected.add(item.id));

  $('image-title').textContent = data.title || '图文作品';
  $('image-count').textContent = `共 ${(data.resources || []).length} 张`;
  $('image-source').textContent = (data.resources || []).some((item) => !item.original)
    ? '部分为平台可用资源'
    : '原图资源';

  const grid = $('image-grid');
  const fragment = document.createDocumentFragment();
  (data.resources || []).forEach((item, index) => {
    fragment.appendChild(buildCard(item, index, data));
  });
  grid.replaceChildren(fragment);
  $('image-result').hidden = false;
  updateSelectionUi();
}

function buildCard(item, index, data) {
  const card = document.createElement('div');
  card.className = 'image-card is-selected';
  card.dataset.id = item.id;

  const area = document.createElement('div');
  area.className = 'image-card__area';

  const img = document.createElement('img');
  img.src = item.previewUrl || item.url;
  img.alt = `第 ${index + 1} 张图片`;
  img.loading = 'lazy';
  img.decoding = 'async';
  img.referrerPolicy = 'no-referrer';
  img.tabIndex = 0;

  const openThis = (event) => {
    event.stopPropagation();
    openViewer(
      (data.resources || []).map((row) => ({
        src: row.previewUrl || row.url,
        id: row.id,
        kind: row.kind,
      })),
      index
    );
  };
  img.addEventListener('click', openThis);
  img.addEventListener('keydown', (event) => { if (event.key === 'Enter') openThis(event); });
  img.addEventListener('error', () => {
    img.style.display = 'none';
    if (!area.querySelector('.image-card__fail')) {
      const fail = document.createElement('div');
      fail.className = 'image-card__fail';
      fail.textContent = '预览不可用';
      area.appendChild(fail);
    }
  });
  area.appendChild(img);

  const info = document.createElement('div');
  info.className = 'image-card__info';

  const check = document.createElement('input');
  check.type = 'checkbox';
  check.checked = true;
  check.setAttribute('aria-label', `选择第 ${index + 1} 张`);

  const label = document.createElement('span');
  label.className = 'truncate';
  label.textContent = item.kind === 'UGOIRA'
    ? t('动图 %d', index + 1)
    : t('图 %d · %s×%s', index + 1, item.width || '?', item.height || '?');

  info.append(check, label);
  card.append(area, info);

  const toggle = () => {
    const next = !state.selected.has(item.id);
    if (next) state.selected.add(item.id);
    else state.selected.delete(item.id);
    check.checked = next;
    card.classList.toggle('is-selected', next);
    updateSelectionUi();
  };
  check.addEventListener('click', (e) => e.stopPropagation());
  check.addEventListener('change', () => {
    if (check.checked) state.selected.add(item.id);
    else state.selected.delete(item.id);
    card.classList.toggle('is-selected', check.checked);
    updateSelectionUi();
  });
  card.addEventListener('click', toggle);

  return card;
}

function selectAll(value) {
  if (!state.post) return;
  state.selected.clear();
  document.querySelectorAll('#image-grid .image-card').forEach((card) => {
    const check = card.querySelector('input[type="checkbox"]');
    if (check) check.checked = value;
    card.classList.toggle('is-selected', value);
    if (value) state.selected.add(card.dataset.id);
  });
  updateSelectionUi();
}

function updateSelectionUi() {
  const total = state.post ? (state.post.resources || []).length : 0;
  const count = state.selected.size;
  const sel = $('image-selection');
  if (sel) sel.textContent = total ? t('已选 %d / %d 张', count, total) : `${count}`;
  if ($('btn-image-save')) $('btn-image-save').disabled = count === 0;
  if ($('btn-image-zip')) $('btn-image-zip').disabled = count === 0;
}

async function downloadSingleImage(resourceId) {
  if (!state.post) return;
  const tasks = [{
    url: state.post.url,
    title: `${state.post.title || '图片'}`,
    author: state.post.author || '',
    downloadType: 'IMAGE',
    imagePlatform: state.post.platform,
    selectedImageIds: [resourceId],
    mediaId: state.post.contentId || '',
    episodeIndex: 1,
    episodeTotal: 1,
  }];
  try {
    await post('/api/download', { tasks });
    toast('已加入下载任务');
  } catch (e) {
    toast(e.message || '创建任务失败', 'error');
  }
}

async function downloadImages(archive) {
  if (!state.post || !state.selected.size) {
    toast('请先选择图片', 'warning');
    return;
  }
  const ids = state.post.resources
    .filter((item) => state.selected.has(item.id))
    .map((item) => item.id);
  const groups = archive ? [ids] : ids.map((id) => [id]);
  const group = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now());
  const tasks = groups.map((batch, index) => ({
    url: state.post.url,
    title: `${state.post.title} (${archive ? '原图归档' : index + 1})`,
    author: state.post.author || '',
    downloadType: archive ? 'IMAGE_ZIP' : 'IMAGE',
    imagePlatform: state.post.platform,
    selectedImageIds: batch,
    mediaId: state.post.contentId || '',
    collectionId: groups.length > 1 ? group : null,
    collectionTitle: state.post.title,
    episodeIndex: index + 1,
    episodeTotal: groups.length,
  }));
  try {
    await post('/api/download', { tasks });
    toast(`已加入 ${tasks.length} 个下载任务`);
    document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'tasks' } }));
  } catch (e) {
    toast(e.message || '创建任务失败', 'error');
  }
}

/* ---------- ImageViewer (全屏黑底灯箱) ---------- */
function ensureViewer() {
  if (state.viewer) return state.viewer;
  const dialog = document.createElement('dialog');
  dialog.className = 'viewer-dialog';
  dialog.innerHTML = `
    <div class="viewer__layout">
      <div class="viewer__bar">
        <span data-count style="flex:1;font-weight:600"></span>
        <button class="btn btn-text btn-sm" data-save hidden type="button">保存此图</button>
        <button class="btn btn-text btn-sm" data-close type="button">关闭</button>
      </div>
      <div class="viewer__stage">
        <img alt="图片预览">
        <span data-error hidden style="color:#E3E2E8;font-size:13px">图片暂不可预览</span>
      </div>
      <div class="viewer__foot">
        <button class="btn btn-text btn-sm" data-prev type="button">上一张</button>
        <span>点击图片缩放 · 按 Esc 关闭</span>
        <button class="btn btn-text btn-sm" data-next type="button">下一张</button>
      </div>
    </div>`;
  document.body.appendChild(dialog);

  const img = dialog.querySelector('img');
  img.addEventListener('click', () => img.classList.toggle('is-zoomed'));
  img.addEventListener('error', () => { dialog.querySelector('[data-error]').hidden = false; });

  dialog.querySelector('[data-close]').addEventListener('click', () => dialog.close());
  dialog.querySelector('[data-prev]').addEventListener('click', () => step(-1));
  dialog.querySelector('[data-next]').addEventListener('click', () => step(1));
  dialog.querySelector('[data-save]').addEventListener('click', () => {
    const item = state.viewerItems[state.viewerIndex];
    if (item && item.id) downloadSingleImage(item.id);
  });
  dialog.addEventListener('click', (event) => {
    if (event.target.classList.contains('viewer__stage')) dialog.close();
  });
  dialog.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowLeft') step(-1);
    if (event.key === 'ArrowRight') step(1);
  });
  dialog.addEventListener('close', () => {
    img.classList.remove('is-zoomed');
    img.removeAttribute('src');
  });

  state.viewer = dialog;
  return dialog;
}

function step(delta) {
  const next = state.viewerIndex + delta;
  if (next < 0 || next >= state.viewerItems.length) return;
  state.viewerIndex = next;
  renderViewer();
}

function renderViewer() {
  const dialog = state.viewer;
  const item = state.viewerItems[state.viewerIndex];
  const img = dialog.querySelector('img');
  img.classList.remove('is-zoomed');
  img.src = item.src;
  const suffix = item.kind === 'UGOIRA' ? ' · 动图封面' : (item.label ? ` · ${item.label}` : '');
  dialog.querySelector('[data-count]').textContent = `${state.viewerIndex + 1} / ${state.viewerItems.length}${suffix}`;
  const saveBtn = dialog.querySelector('[data-save]');
  saveBtn.hidden = !item.id;
  saveBtn.textContent = item.kind === 'UGOIRA' ? '下载动图' : '保存此图';
  dialog.querySelector('[data-prev]').disabled = state.viewerIndex === 0;
  dialog.querySelector('[data-next]').disabled = state.viewerIndex === state.viewerItems.length - 1;
  dialog.querySelector('[data-error]').hidden = true;
}

function openViewer(items, index = 0) {
  if (!items.length) return;
  const dialog = ensureViewer();
  state.viewerItems = items;
  state.viewerIndex = Math.max(0, Math.min(index, items.length - 1));
  renderViewer();
  if (!dialog.open) dialog.showModal();
}

export function openImageViewer(taskId, label = '') {
  openViewer([{ src: `/api/tasks/${encodeURIComponent(taskId)}/view`, label }], 0);
}

export function openImageCollectionViewer(tasks, startIndex = 0) {
  const items = (Array.isArray(tasks) ? tasks : [])
    .filter((t) => t && t.id)
    .map((t) => ({ src: `/api/tasks/${encodeURIComponent(t.id)}/view`, label: t.title || '' }));
  openViewer(items, startIndex);
}

export async function openImageZipViewer(taskId, title = '') {
  try {
    const data = await get(`/api/tasks/${encodeURIComponent(taskId)}/zip-entries`);
    const entries = Array.isArray(data?.entries) ? data.entries : [];
    if (!entries.length) {
      toast('压缩包中未检测到可预览的图片文件', 'warning');
      return;
    }
    const items = entries.map((entry) => ({
      src: `/api/tasks/${encodeURIComponent(taskId)}/zip-entry?entry=${encodeURIComponent(entry)}`,
      label: title || entry,
    }));
    openViewer(items, 0);
  } catch (e) {
    toast(e.message || '无法读取图集压缩包', 'error');
  }
}
