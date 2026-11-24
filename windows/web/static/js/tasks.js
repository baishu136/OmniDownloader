/* S03 下载管理：三段滑动切换器、单任务卡片、合集分组折叠卡片、SSE 实时更新（对齐 TasksScreen + TaskCard + CollectionTaskCard） */
import { get, post, del } from './api.js';
import { toast, escapeHtml, confirmDialog, showErrorDetailDialog } from './ui.js';
import { statusInfo, typeLabel, FILTERS } from './labels.js';
import { iconSvg } from './icons.js';
import { t } from './i18n.js';

const state = {
  tasks: [],
  filter: 'all',
  expandedCollections: new Set(),
  eventSource: null,
  renderRaf: null,
  recentRaf: null,
};

const $ = (id) => document.getElementById(id);

export function initTasks() {
  $('btn-open-folder')?.addEventListener('click', openFolder);
  $('btn-clear-history')?.addEventListener('click', clearHistory);

  $('task-filter')?.addEventListener('click', (event) => {
    const tab = event.target.closest('[data-filter]');
    if (!tab) return;
    state.filter = tab.dataset.filter;
    const idx = Number(tab.dataset.segIdx || 0);
    $('task-filter').style.setProperty('--seg-index', String(idx));
    document.querySelectorAll('#task-filter .omni-segmented__tab').forEach((item) => {
      const selected = item === tab;
      item.classList.toggle('is-active', selected);
      item.setAttribute('aria-selected', String(selected));
    });
    renderList();
    updateClearBtn();
  });

  fetchTasks();
  initSSE();
  document.addEventListener('omni:language', () => renderAll());
}

export async function refreshTasks() {
  await fetchTasks();
}

export async function fetchTasks() {
  try {
    const tasks = await get('/api/tasks');
    state.tasks = Array.isArray(tasks) ? tasks : [];
    renderAll();
  } catch (e) {
    console.error('加载任务失败', e);
    state.tasks = [];
    renderAll();
    toast('无法加载任务列表，请确认本地服务正在运行', 'error');
  }
}

/* ---------- 聚合单任务与合集分组（对齐 TaskListState.kt） ---------- */
function buildDisplayItems(tasks) {
  const collections = new Map();
  for (const task of tasks) {
    if (task.collectionId) {
      if (!collections.has(task.collectionId)) collections.set(task.collectionId, []);
      collections.get(task.collectionId).push(task);
    }
  }
  const seen = new Set();
  const all = [];
  const active = [];
  const done = [];

  for (const task of tasks) {
    let item;
    if (!task.collectionId) {
      item = { kind: 'single', key: `single_${task.id}`, task, members: [task] };
    } else {
      if (seen.has(task.collectionId)) continue;
      seen.add(task.collectionId);
      const group = collections.get(task.collectionId).slice().sort((a, b) => (a.episodeIndex || 999999) - (b.episodeIndex || 999999));
      item = {
        kind: 'collection',
        key: `col_${task.collectionId}`,
        collectionId: task.collectionId,
        collectionTitle: task.collectionTitle || group[0].title || t('合集下载'),
        tasks: group,
        members: group,
      };
    }
    all.push(item);
    if (item.members.some((t) => FILTERS.active(t))) active.push(item);
    if (item.members.every((t) => FILTERS.done(t))) done.push(item);
  }
  return { all, active, done };
}

function coverFallbackIcon(type) {
  if (type === 'AUDIO_ONLY') return 'audiotrack';
  if (type === 'VIDEO_ONLY') return 'volume_off';
  if (type === 'GIF') return 'gif';
  if (type === 'COVER' || type === 'IMAGE' || type === 'IMAGE_ZIP') return 'image';
  return 'videocam';
}

function typeBadgeText(task) {
  const base = typeLabel(task.downloadType);
  if (task.downloadType === 'VIDEO_WITH_AUDIO' || task.downloadType === 'VIDEO_ONLY') {
    const res = task.selectedResolution && task.selectedResolution !== 'best' ? task.selectedResolution : t('最佳');
    return `${base}: ${res}`;
  }
  if (task.downloadType === 'AUDIO_ONLY') {
    return `${base}: ${(task.audioFormat || 'MP3').toUpperCase()}`;
  }
  if (task.downloadType === 'GIF') return t('动图: GIF');
  return base;
}

/* ---------- 渲染 ---------- */
function renderAll() {
  const groups = buildDisplayItems(state.tasks);
  updateHeaderAndTabs(groups);
  updateBadge();
  renderList(groups);
  updateClearBtn(groups);
  scheduleRecent();
}

function updateHeaderAndTabs(groups) {
  const subtitle = $('tasks-subtitle');
  if (subtitle) subtitle.textContent = t('下载中 %d', groups.active.length);

  const tabs = document.querySelectorAll('#task-filter .omni-segmented__tab');
  if (tabs[0]) tabs[0].textContent = t('全部 (%d)', groups.all.length);
  if (tabs[1]) tabs[1].textContent = t('下载中 (%d)', groups.active.length);
  if (tabs[2]) tabs[2].textContent = t('已完成 (%d)', groups.done.length);
}

function updateClearBtn(groups) {
  const g = groups || buildDisplayItems(state.tasks);
  const btn = $('btn-clear-history');
  if (!btn) return;
  let visible = false;
  if (state.filter === 'all') visible = state.tasks.some((t) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(t.status));
  else if (state.filter === 'active') visible = state.tasks.some((t) => ['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(t.status));
  else if (state.filter === 'done') visible = g.done.length > 0;
  btn.hidden = !visible;
}

function updateBadge() {
  const badge = $('nav-task-badge');
  if (!badge) return;
  const count = state.tasks.filter((task) => ['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(task.status)).length;
  badge.textContent = String(count);
  badge.hidden = count === 0;
}

function scheduleRecent() {
  if (state.recentRaf) return;
  state.recentRaf = requestAnimationFrame(() => {
    state.recentRaf = null;
    document.dispatchEvent(new CustomEvent('omni:tasks', { detail: state.tasks }));
  });
}

function renderList(groups) {
  const g = groups || buildDisplayItems(state.tasks);
  const visible = g[state.filter] || g.all;
  const container = $('tasks-container');
  const empty = $('tasks-empty');

  if (!visible.length) {
    container.replaceChildren();
    empty.hidden = false;
    const iconBox = $('tasks-empty-icon');
    const titleEl = $('tasks-empty-title');
    const descEl = $('tasks-empty-desc');
    if (state.filter === 'active') {
      if (iconBox) iconBox.innerHTML = iconSvg('download');
      if (titleEl) titleEl.textContent = t('暂无正在进行的下载任务');
      if (descEl) descEl.textContent = t('新加入的下载任务会在这里实时展示进度。');
    } else if (state.filter === 'done') {
      if (iconBox) iconBox.innerHTML = iconSvg('check_circle');
      if (titleEl) titleEl.textContent = t('暂无已完成的下载记录');
      if (descEl) descEl.textContent = t('完成下载的媒体文件会集中保存在这里。');
    } else {
      if (iconBox) iconBox.innerHTML = iconSvg('inbox');
      if (titleEl) titleEl.textContent = t('暂无任何下载任务');
      if (descEl) descEl.textContent = t('粘贴视频链接并解析，即可开始下载。');
    }
    return;
  }

  empty.hidden = true;
  const frag = document.createDocumentFragment();
  for (const item of visible) {
    frag.appendChild(item.kind === 'collection' ? buildCollectionCard(item) : buildSingleCard(item.task));
  }
  container.replaceChildren(frag);
}

/* ---------- 单任务卡片 (TaskCard.kt) ---------- */
function buildSingleCard(task) {
  const info = statusInfo(task.status);
  const progress = Math.min(Math.max(Number(task.progress) || 0, 0), 100);
  const isImage = ['IMAGE', 'IMAGE_ZIP', 'COVER', 'GIF'].includes(task.downloadType);
  const isRunning = ['DOWNLOADING', 'PROCESSING'].includes(task.status);
  const isError = ['FAILED', 'WAITING_FOR_AUTH'].includes(task.status) && task.errorMessage;

  const card = document.createElement('article');
  card.className = 'task-card';
  card.dataset.id = task.id;

  card.innerHTML = `
    <div class="task-card__head">
      <div class="task-cover">
        ${iconSvg(coverFallbackIcon(task.downloadType))}
        ${task.thumbnailUrl ? `<img src="${escapeHtml(task.thumbnailUrl)}" alt="" loading="lazy">` : ''}
      </div>
      <div class="task-card__info">
        <div data-no-i18n class="task-card__title" title="${escapeHtml(task.title || t('下载任务'))}">${escapeHtml(task.title || t('下载任务'))}</div>
        <div class="task-card__badges">
          <span class="omni-pill omni-pill--primary">${escapeHtml(typeBadgeText(task))}</span>
          <span class="omni-pill ${info.pill}">${escapeHtml(info.label)}</span>
          ${task.fileSizeText ? `<span class="caption">${escapeHtml(task.fileSizeText)}</span>` : ''}
        </div>
      </div>
    </div>
    ${isRunning ? `
    <div class="task-progress-block">
      <div class="omni-progress"><div class="omni-progress__bar ${info.progress}" style="width:${progress}%"></div></div>
      <div class="task-progress-meta">
        <span>${task.status === 'PROCESSING' ? escapeHtml(t('正在合并音画轨...')) : `${progress.toFixed(0)}%${task.speedText ? ' · ' + escapeHtml(task.speedText) : ''}`}</span>
        <span>${task.etaText ? escapeHtml(t('剩余 %s', t(task.etaText))) : ''}</span>
      </div>
    </div>` : ''}
    ${isError ? `
    <div class="task-error-strip" role="button" tabindex="0">
      <span class="task-error-strip__text">${escapeHtml(t('错误: %s', t(task.errorMessage)))}</span>
      <span class="task-error-strip__detail">${escapeHtml(t('查看详情'))}</span>
    </div>` : ''}
    <div class="task-card__actions"></div>`;

  const img = card.querySelector('.task-cover img');
  if (img) img.addEventListener('error', () => img.remove());

  const errStrip = card.querySelector('.task-error-strip');
  if (errStrip) {
    const openErr = () => showErrorDetailDialog({ title: t('失败日志详情'), message: t(task.errorMessage) });
    errStrip.addEventListener('click', openErr);
    errStrip.addEventListener('keydown', (e) => { if (e.key === 'Enter') openErr(); });
  }

  const actions = card.querySelector('.task-card__actions');
  if (['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(task.status)) {
    const cancelBtn = makePillBtn('取消', 'close', false, () => cancelTask(task.id));
    actions.appendChild(cancelBtn);
  } else if (task.status === 'COMPLETED') {
    const primaryLabel = isImage ? '查看' : '播放';
    const primaryIcon = task.downloadType === 'COVER' || isImage ? 'image' : (task.downloadType === 'GIF' ? 'visibility' : 'play_arrow');
    const viewBtn = makePillBtn(primaryLabel, primaryIcon, true, () => {
      import('./media.js').then((mod) => mod.openTaskMedia(task, [task]));
    });
    actions.appendChild(viewBtn);

    const saveLink = document.createElement('a');
    saveLink.className = 'icon-btn';
    saveLink.href = `/api/tasks/${encodeURIComponent(task.id)}/download`;
    saveLink.setAttribute('download', '');
    const saveLabel = t('浏览器保存');
    saveLink.title = saveLabel;
    saveLink.setAttribute('aria-label', saveLabel);
    saveLink.innerHTML = iconSvg('download');
    actions.appendChild(saveLink);

    actions.appendChild(makeIconBtn('在电脑中定位', 'folder_open', () => locateInExplorer(task.id)));
    actions.appendChild(makeIconBtn('删除', 'delete', () => deleteSingleTask(task.id, true), true));
  } else {
    const retryText = task.errorCode === 'EXPORT_FAILED' ? '重试导出' : '重试';
    actions.appendChild(makePillBtn(retryText, 'refresh', true, () => retryTask(task.id)));
    actions.appendChild(makeIconBtn('删除', 'delete', () => deleteSingleTask(task.id, false), true));
  }

  return card;
}

/* ---------- 合集分组卡片 (CollectionTaskCard.kt) ---------- */
function buildCollectionCard(col) {
  const tasks = col.tasks;
  const total = tasks.length;
  const completedCount = tasks.filter((t) => t.status === 'COMPLETED').length;
  const failedCount = tasks.filter((t) => ['FAILED', 'WAITING_FOR_AUTH'].includes(t.status)).length;
  const activeTask = tasks.find((t) => ['DOWNLOADING', 'PROCESSING'].includes(t.status));
  const isAllDone = completedCount === total && total > 0;
  const isDownloading = Boolean(activeTask);
  const isPending = tasks.some((t) => t.status === 'PENDING') && !isDownloading;
  const hasFailed = tasks.some((t) => ['FAILED', 'WAITING_FOR_AUTH', 'CANCELLED'].includes(t.status));

  let overallSum = 0;
  for (const t of tasks) {
    if (t.status === 'COMPLETED') overallSum += 100;
    else if (t.status === 'DOWNLOADING' || t.status === 'PROCESSING') overallSum += Math.min(Math.max(Number(t.progress) || 0, 0), 100);
  }
  const overallPct = total ? Math.min(Math.max(overallSum / total, 0), 100) : 0;

  let statusText = t('等待中');
  let statusPill = 'omni-pill--warning';
  if (isAllDone) { statusText = t('已完成'); statusPill = 'omni-pill--success'; }
  else if (isDownloading) { statusText = `${t('正在下载')} (${Math.min(completedCount + 1, total)}/${total})`; statusPill = 'omni-pill--primary'; }
  else if (isPending) { statusText = t('等待中'); statusPill = 'omni-pill--warning'; }
  else if (hasFailed) { statusText = failedCount > 0 ? t('%d 集失败', failedCount) : t('已取消'); statusPill = 'omni-pill--error'; }

  const coverUrl = tasks.find((t) => t.thumbnailUrl)?.thumbnailUrl || '';
  const expanded = state.expandedCollections.has(col.collectionId);

  const card = document.createElement('article');
  card.className = 'task-card';
  card.dataset.collectionId = col.collectionId;

  const activePct = activeTask ? Math.min(Math.max(Number(activeTask.progress) || 0, 0), 100) : 0;

  card.innerHTML = `
    <div class="task-card__head">
      <div class="task-cover">
        ${iconSvg('video_library')}
        ${coverUrl ? `<img src="${escapeHtml(coverUrl)}" alt="" loading="lazy">` : ''}
        <span class="task-cover__count">${total}P</span>
      </div>
      <div class="task-card__info">
        <div data-no-i18n class="task-card__title" title="${escapeHtml(col.collectionTitle)}">${escapeHtml(col.collectionTitle)}</div>
        <div class="task-card__badges">
          <span class="omni-pill omni-pill--primary">${escapeHtml(t('合集 · %d 集', total))}</span>
          <span class="omni-pill ${statusPill}">${escapeHtml(statusText)}</span>
        </div>
      </div>
    </div>

    <div class="task-progress-block">
      <div class="task-progress-meta">
        <span style="font-weight:500;color:var(--text)">${escapeHtml(t('总进度: 已完成 %d/%d 集 (%d%%)', completedCount, total, overallPct.toFixed(0)))}</span>
        <span style="font-weight:700;color:${isAllDone ? 'var(--success)' : 'var(--primary)'}">${overallPct.toFixed(0)}%</span>
      </div>
      <div class="omni-progress omni-progress--lg">
        <div class="omni-progress__bar ${isAllDone ? 'omni-progress__bar--success' : ''}" style="width:${overallPct}%"></div>
      </div>
      ${activeTask ? `
      <div class="collection-sub-box">
        <div class="collection-sub-box__head">
          ${iconSvg('downloading')}
          <span class="collection-sub-box__title">${escapeHtml(t('当前分集 (P%d/%d): %s', activeTask.episodeIndex || 1, total, activeTask.title))}</span>
        </div>
        <div class="omni-progress omni-progress--sm"><div class="omni-progress__bar" style="width:${activePct}%"></div></div>
        <div class="task-progress-meta">
          <span style="color:var(--primary);font-weight:600">${activePct.toFixed(0)}% · ${escapeHtml(activeTask.speedText || t('准备中'))}</span>
          <span>${activeTask.etaText ? escapeHtml(t('剩余 %s', t(activeTask.etaText))) : ''}</span>
        </div>
      </div>` : ''}
    </div>

    <button class="collection-toggle" type="button">
      <span>${expanded ? escapeHtml(t('收起分集')) : escapeHtml(t('分集明细 (共 %d 集 · 已完成 %d)', total, completedCount))}</span>
      ${iconSvg(expanded ? 'expand_less' : 'expand_more')}
    </button>

    ${expanded ? '<div class="collection-episodes"></div>' : ''}
    <div class="task-card__actions"></div>`;

  const img = card.querySelector('.task-cover img');
  if (img) img.addEventListener('error', () => img.remove());

  card.querySelector('.collection-toggle').addEventListener('click', () => {
    if (state.expandedCollections.has(col.collectionId)) state.expandedCollections.delete(col.collectionId);
    else state.expandedCollections.add(col.collectionId);
    renderList();
  });

  if (expanded) {
    const epContainer = card.querySelector('.collection-episodes');
    for (const sub of tasks) {
      const row = document.createElement('div');
      row.className = 'collection-episode-row';
      const subPct = Math.min(Math.max(Number(sub.progress) || 0, 0), 100);
      row.innerHTML = `
        <span class="collection-episode-row__badge">P${sub.episodeIndex || 1}</span>
        <span data-no-i18n class="collection-episode-row__title" title="${escapeHtml(sub.title)}">${escapeHtml(sub.title)}</span>`;

      if (sub.status === 'COMPLETED') {
        const playBtn = makeIconBtn('播放', 'play_circle', () => {
          import('./media.js').then((mod) => mod.openTaskMedia(sub, tasks));
        });
        playBtn.style.width = '30px';
        playBtn.style.height = '30px';
        playBtn.style.color = 'var(--success)';
        row.appendChild(playBtn);
      } else if (sub.status === 'DOWNLOADING' || sub.status === 'PROCESSING') {
        const pct = document.createElement('span');
        pct.style.cssText = 'font-size:11px;font-weight:700;color:var(--primary)';
        pct.textContent = `${subPct.toFixed(0)}%`;
        row.appendChild(pct);
      } else if (sub.status === 'FAILED' || sub.status === 'WAITING_FOR_AUTH') {
        const rBtn = makeIconBtn('重试', 'refresh', () => retryTask(sub.id));
        rBtn.style.width = '30px';
        rBtn.style.height = '30px';
        rBtn.style.color = 'var(--status-error)';
        row.appendChild(rBtn);
      } else {
        const st = document.createElement('span');
        st.className = 'caption';
        st.textContent = sub.status === 'CANCELLED' ? t('已取消') : t('等待中');
        row.appendChild(st);
      }
      epContainer.appendChild(row);
    }
  }

  const actions = card.querySelector('.task-card__actions');
  if (isDownloading || isPending) {
    actions.appendChild(makePillBtn('取消合集', 'close', false, () => cancelCollection(col)));
  }
  if (isAllDone) {
    const firstDone = tasks.find((t) => t.status === 'COMPLETED');
    if (firstDone) {
      actions.appendChild(makePillBtn('播放首集', 'play_arrow', true, () => {
        import('./media.js').then((mod) => mod.openTaskMedia(firstDone, tasks));
      }));
    }
  }
  if (hasFailed && !isDownloading) {
    actions.appendChild(makePillBtn('重试失败项', 'refresh', true, () => retryCollection(col)));
  }
  if (isAllDone || hasFailed) {
    actions.appendChild(makeIconBtn('删除合集', 'delete', () => deleteCollection(col), true));
  }

  return card;
}

/* ---------- UI 构造小助手 ---------- */
function makePillBtn(text, iconName, primary, handler) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = `btn btn-sm ${primary ? 'btn-primary' : 'btn-tonal'}`;
  btn.innerHTML = `${iconSvg(iconName)}<span>${escapeHtml(t(text))}</span>`;
  btn.addEventListener('click', handler);
  return btn;
}

function makeIconBtn(title, iconName, handler, danger = false) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = `icon-btn${danger ? ' icon-btn--danger' : ''}`;
  const label = t(title);
  btn.title = label;
  btn.setAttribute('aria-label', label);
  btn.innerHTML = iconSvg(iconName);
  btn.addEventListener('click', handler);
  return btn;
}

/* ---------- 动作 ---------- */
async function cancelTask(id) {
  try {
    await post(`/api/tasks/${encodeURIComponent(id)}/cancel`);
    toast('已请求取消任务', 'info');
  } catch (e) {
    toast(e.message || '取消失败', 'error');
  }
}

async function retryTask(id) {
  try {
    await post(`/api/tasks/${encodeURIComponent(id)}/retry`);
    await fetchTasks();
    toast('已重新提交任务', 'info');
  } catch (e) {
    toast(e.message || '重试失败', 'error');
  }
}

async function locateInExplorer(id) {
  try {
    await post(`/api/tasks/${encodeURIComponent(id)}/open`);
  } catch (e) {
    toast(e.message || '文件不存在或无法打开目录', 'error');
  }
}

async function deleteSingleTask(id, allowDeleteFile) {
  const res = await confirmDialog({
    title: '删除任务记录',
    message: '确定移除这条任务记录吗？',
    confirmText: '删除',
    danger: true,
    checkboxLabel: allowDeleteFile ? '同时删除本地文件' : '',
    checkboxDefault: false,
  });
  if (!res) return;
  const deleteFile = typeof res === 'object' ? Boolean(res.checked) : false;
  try {
    await del(`/api/tasks/${encodeURIComponent(id)}?deleteFile=${deleteFile}`);
    toast(deleteFile ? '已删除记录与本地文件' : '已删除任务记录');
  } catch (e) {
    toast(e.message || '删除失败', 'error');
  }
}

async function cancelCollection(col) {
  const ok = await confirmDialog({
    title: '取消合集下载',
    message: t('确定要终止该合集下所有正在下载与等待中的任务吗？'),
    confirmText: '终止并清理',
    danger: true,
  });
  if (!ok) return;
  const active = col.tasks.filter((t) => ['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(t.status));
  await Promise.allSettled(active.map((t) => post(`/api/tasks/${encodeURIComponent(t.id)}/cancel`)));
  toast('已取消合集任务', 'info');
}

async function retryCollection(col) {
  const targets = col.tasks.filter((t) => ['FAILED', 'WAITING_FOR_AUTH', 'CANCELLED'].includes(t.status));
  await Promise.allSettled(targets.map((t) => post(`/api/tasks/${encodeURIComponent(t.id)}/retry`)));
  await fetchTasks();
  toast('已重试失败项', 'info');
}

async function deleteCollection(col) {
  const res = await confirmDialog({
    title: '删除合集任务',
    message: t('确定要删除该合集下的全部 %d 个分集任务记录吗？', col.tasks.length),
    confirmText: '删除',
    danger: true,
    checkboxLabel: '同时删除本地文件',
    checkboxDefault: false,
  });
  if (!res) return;
  const deleteFile = typeof res === 'object' ? Boolean(res.checked) : false;
  await Promise.allSettled(col.tasks.map((t) => del(`/api/tasks/${encodeURIComponent(t.id)}?deleteFile=${deleteFile}`)));
  await fetchTasks();
  toast(deleteFile ? '已删除合集及本地文件' : '已删除合集记录');
}

async function openFolder() {
  try { await post('/api/open-folder'); } catch (e) { toast(e.message || '无法打开目录', 'error'); }
}

async function clearHistory() {
  if (state.filter === 'active') {
    const running = state.tasks.filter((t) => ['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(t.status));
    if (!running.length) return;
    const ok = await confirmDialog({
      title: '终止未完成任务',
      message: t('确定要终止当前 %d 个未完成的下载任务吗？', running.length),
      confirmText: '终止并清理',
      danger: true,
    });
    if (!ok) return;
    await Promise.allSettled(running.map((t) => post(`/api/tasks/${encodeURIComponent(t.id)}/cancel`)));
    await fetchTasks();
    toast('已终止所有未完成任务', 'info');
    return;
  }

  const finished = state.tasks.filter((task) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status));
  if (!finished.length) {
    toast('暂无可清空的历史记录', 'info');
    return;
  }
  const ok = await confirmDialog({
    title: '清除下载记录',
    message: t('确定要清除所有已结束的任务记录吗？（共 %d 条，本地已保存的视频文件不会被删除）', finished.length),
    confirmText: '立即清除',
    danger: true,
  });
  if (!ok) return;
  try {
    await post('/api/tasks/clear');
    await fetchTasks();
    toast(t('已清空 %d 条记录', finished.length));
  } catch (e) {
    toast(e.message || '清空失败', 'error');
  }
}

/* ---------- SSE 实时推送 ---------- */
function initSSE() {
  if (state.eventSource) {
    try { state.eventSource.close(); } catch (e) { /* ignore */ }
  }
  const source = new EventSource('/api/events');
  state.eventSource = source;

  source.onmessage = (event) => {
    let data;
    try { data = JSON.parse(event.data); } catch (e) { return; }

    if (data.type === 'task_update') {
      const updated = data.task;
      const index = state.tasks.findIndex((task) => task.id === updated.id);
      const previous = index !== -1 ? state.tasks[index] : null;
      if (index !== -1) state.tasks[index] = updated;
      else state.tasks.unshift(updated);

      if (updated.status === 'COMPLETED' && previous && previous.status !== 'COMPLETED') {
        const auto = localStorage.getItem('omni_auto_browser_download') !== 'false';
        if (auto) {
          toast(t('任务已完成，正在调起浏览器保存：%s', updated.title));
          triggerBrowserDownload(updated.id);
        }
      }
      scheduleRender();
    } else if (data.type === 'task_deleted') {
      state.tasks = state.tasks.filter((task) => task.id !== data.taskId);
      scheduleRender();
    } else if (data.type === 'tasks_cleared') {
      fetchTasks();
    } else if (data.type === 'focus_page') {
      try { window.focus(); } catch (e) { /* ignore */ }
      toast('OmniDownloader 页面已就绪', 'info');
    }
  };

  source.onerror = () => {
    try { source.close(); } catch (e) { /* ignore */ }
    setTimeout(initSSE, 1500);
  };
}

function scheduleRender() {
  if (state.renderRaf) return;
  state.renderRaf = requestAnimationFrame(() => {
    state.renderRaf = null;
    renderAll();
  });
}

function triggerBrowserDownload(id) {
  const link = document.createElement('a');
  link.href = `/api/tasks/${encodeURIComponent(id)}/download`;
  link.setAttribute('download', '');
  document.body.appendChild(link);
  link.click();
  link.remove();
}
