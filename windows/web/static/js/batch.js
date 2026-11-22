/* S02 批量下载（对齐 BatchDownloadScreen.kt） */
import { get, post } from './api.js';
import { toast, escapeHtml, confirmDialog } from './ui.js';
import { iconSvg } from './icons.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);
const STATUS_MAP = {
  PENDING: { label: '等待解析', pill: '' },
  RESOLVING: { label: '解析中...', pill: 'omni-pill--primary' },
  QUEUED: { label: '已加入队列', pill: 'omni-pill--success' },
  FAILED: { label: '失败', pill: 'omni-pill--error' },
  CANCELLED: { label: '已停止', pill: 'omni-pill--warning' },
};

const URL_RE = /https?:\/\/[^\s"'<>]+|BV[0-9A-Za-z]{10}/gi;

const state = {
  manualMode: false,
  draftLinks: [],
  refreshing: false,
};

function loadDraftStorage() {
  try {
    const saved = JSON.parse(localStorage.getItem('omni.batchLinks') || '[]');
    if (Array.isArray(saved)) state.draftLinks = saved;
  } catch (e) {
    state.draftLinks = [];
  }
}

function saveDraftStorage() {
  try { localStorage.setItem('omni.batchLinks', JSON.stringify(state.draftLinks)); } catch (e) { /* ignore */ }
}

function extractLinks(raw, manual = false) {
  const text = (raw || '').trim();
  if (!text) return [];
  const found = manual ? text.split(/[\r\n]+/).map((s) => s.trim()).filter(Boolean) : (text.match(URL_RE) || []);
  const dedup = [];
  const seen = new Set(state.draftLinks);
  for (const item of found) {
    const clean = item.replace(/[。，,！!？?]+$/, '');
    if (!clean || seen.has(clean)) continue;
    seen.add(clean);
    dedup.push(clean);
  }
  return dedup;
}

function setMode(manual) {
  state.manualMode = manual;
  $('batch-mode-smart')?.classList.toggle('is-selected', !manual);
  $('batch-mode-manual')?.classList.toggle('is-selected', manual);
  const ta = $('batch-text');
  if (ta) {
    ta.rows = manual ? 2 : 4;
    ta.placeholder = manual ? '粘贴单条链接...' : '粘贴包含多个视频或图文链接的分享文本，每批最多 100 条...';
  }
  const addBtn = $('batch-add');
  if (addBtn) addBtn.textContent = manual ? '添加链接' : '识别并添加';
}

function showError(msg) {
  const el = $('batch-error');
  if (!el) return;
  el.textContent = msg || '';
  el.hidden = !msg;
}

function renderDraftLinks() {
  const section = $('batch-draft-section');
  const list = $('batch-draft-list');
  const title = $('batch-draft-title');
  if (!section || !list) return;

  if (!state.draftLinks.length) {
    section.hidden = true;
    list.replaceChildren();
    return;
  }
  section.hidden = false;
  title.textContent = t('待下载链接 (%d)', state.draftLinks.length);
  list.replaceChildren(...state.draftLinks.map((url) => {
    const card = document.createElement('div');
    card.className = 'batch-link-card';
    card.innerHTML = `
      <div class="batch-link-card__body"><div class="batch-link-card__url">${escapeHtml(url)}</div></div>
      <button class="btn btn-text btn-sm" type="button">移除</button>`;
    card.querySelector('button').addEventListener('click', () => {
      state.draftLinks = state.draftLinks.filter((u) => u !== url);
      saveDraftStorage();
      renderDraftLinks();
    });
    return card;
  }));
}

function addFromText(rawText) {
  const added = extractLinks(rawText, state.manualMode);
  if (!added.length && !state.draftLinks.length) {
    showError('未识别到有效链接（支持 http/https 链接或 BV 号）');
    return false;
  }
  if (state.draftLinks.length + added.length > 100) {
    showError('单批最多添加 100 条去重链接');
    return false;
  }
  state.draftLinks.push(...added);
  saveDraftStorage();
  renderDraftLinks();
  showError('');
  if (added.length) {
    $('batch-text').value = '';
    localStorage.removeItem('omni.batchDraft');
  }
  return true;
}

export function initBatch() {
  loadDraftStorage();
  renderDraftLinks();

  const ta = $('batch-text');
  ta.value = localStorage.getItem('omni.batchDraft') || '';
  ta.addEventListener('input', () => {
    localStorage.setItem('omni.batchDraft', ta.value);
    showError('');
  });

  $('batch-mode-smart')?.addEventListener('click', () => setMode(false));
  $('batch-mode-manual')?.addEventListener('click', () => setMode(true));

  $('batch-type')?.addEventListener('change', () => {
    const audioSel = $('batch-audio');
    if (audioSel) audioSel.hidden = $('batch-type').value !== 'AUDIO_ONLY';
  });

  $('batch-paste')?.addEventListener('click', async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (!text || !text.trim()) { toast('剪贴板为空', 'warning'); return; }
      ta.value = text.trim();
      localStorage.setItem('omni.batchDraft', ta.value);
      addFromText(ta.value);
    } catch (e) {
      toast('无法读取剪贴板，请使用 Ctrl+V 粘贴', 'warning');
    }
  });

  $('batch-file')?.addEventListener('change', async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > 100000) { toast('TXT 文件最多 100KB', 'warning'); return; }
    ta.value = (ta.value ? ta.value + '\n' : '') + await file.text();
    ta.dispatchEvent(new Event('input'));
    event.target.value = '';
  });

  $('batch-add')?.addEventListener('click', () => {
    addFromText(ta.value);
  });

  $('batch-clear-draft')?.addEventListener('click', async () => {
    const ok = await confirmDialog({
      title: '清空输入',
      message: '确定清空已识别的待下载链接列表吗？',
      confirmText: '清空',
    });
    if (!ok) return;
    state.draftLinks = [];
    ta.value = '';
    localStorage.removeItem('omni.batchDraft');
    saveDraftStorage();
    renderDraftLinks();
  });

  $('batch-start')?.addEventListener('click', async () => {
    if (ta.value.trim()) {
      if (!addFromText(ta.value)) return;
    }
    if (!state.draftLinks.length) {
      showError('请先粘贴链接或导入 TXT 文件');
      return;
    }
    const btn = $('batch-start');
    btn.disabled = true;
    showError('');
    try {
      await post('/api/batches', {
        text: state.draftLinks.join('\n'),
        type: $('batch-type').value,
        audio: $('batch-audio').value,
        allEpisodes: $('batch-all').checked,
      });
      state.draftLinks = [];
      saveDraftStorage();
      renderDraftLinks();
      toast('批量解析任务已开始');
      await refresh();
    } catch (error) {
      showError(error.message);
    } finally {
      btn.disabled = false;
    }
  });

  async function refresh() {
    if (state.refreshing) return;
    state.refreshing = true;
    try {
      const [jobs, downloads] = await Promise.all([get('/api/batches'), get('/api/tasks')]);
      const histSection = $('batch-history-section');
      const container = $('batch-jobs');
      const summary = $('batch-summary');

      const allItems = jobs.flatMap((j) => j.items || []);
      const queuedCount = allItems.filter((i) => i.status === 'QUEUED').length;
      const taskSum = allItems.reduce((acc, i) => acc + (i.taskIds?.length || 0), 0);
      if (summary) {
        summary.textContent = allItems.length ? t('已提交 %d / %d 条 · 生成 %d 个任务', queuedCount, allItems.length, taskSum) : '';
      }

      if (!jobs.length) {
        histSection.hidden = true;
        container.replaceChildren();
        return;
      }
      histSection.hidden = false;
      container.replaceChildren();

      for (const job of [...jobs].reverse()) {
        const card = document.createElement('section');
        card.className = 'omni-card';

        const isRunning = job.items.some((r) => ['PENDING', 'RESOLVING'].includes(r.status));
        const hasRetryable = job.items.some((r) => ['FAILED', 'CANCELLED'].includes(r.status));

        const head = document.createElement('div');
        head.className = 'row';
        head.style.marginBottom = '10px';
        head.innerHTML = `
          <span style="font-size:15px;font-weight:700;flex:1">${escapeHtml(t('批次 · %d 条链接', job.items.length))}</span>
          <span class="omni-pill ${isRunning ? 'omni-pill--primary' : 'omni-pill--success'}">${escapeHtml(t(isRunning ? '正在处理' : '已完成处理'))}</span>`;
        card.append(head);

        if (isRunning) {
          const done = job.items.filter((r) => !['PENDING', 'RESOLVING'].includes(r.status)).length;
          const pct = job.items.length ? (done / job.items.length) * 100 : 0;
          const prog = document.createElement('div');
          prog.className = 'omni-progress';
          prog.style.marginBottom = '10px';
          prog.innerHTML = `<div class="omni-progress__bar" style="width:${pct}%"></div>`;
          card.append(prog);
        }

        for (const row of job.items) {
          const files = downloads.filter((task) => (row.taskIds || []).includes(task.id));
          const completed = files.filter((task) => task.status === 'COMPLETED').length;
          const failed = files.filter((task) => task.status === 'FAILED').length;
          const meta = STATUS_MAP[row.status] || { label: row.status, pill: '' };
          const progText = files.length ? `完成 ${completed}/${files.length}${failed ? ` · 失败 ${failed}` : ''}` : '';

          const itemEl = document.createElement('div');
          itemEl.className = 'batch-job-row';
          itemEl.innerHTML = `
            <div class="row" style="gap:8px;align-items:flex-start">
              <span class="omni-pill ${meta.pill}">${escapeHtml(meta.label)}</span>
              <span style="flex:1;min-width:0;font-size:13px;word-break:break-all">${escapeHtml(row.url)}</span>
              ${!isRunning && row.status === 'FAILED' ? '<button class="btn btn-text btn-sm" data-single type="button">单条解析</button>' : ''}
            </div>
            ${progText || row.note ? `<div class="caption" style="color:${row.status === 'FAILED' ? 'var(--status-error)' : 'var(--text-2)'}">${escapeHtml([progText, row.note].filter(Boolean).join(' · '))}</div>` : ''}`;

          const singleBtn = itemEl.querySelector('[data-single]');
          if (singleBtn) {
            singleBtn.addEventListener('click', () => {
              document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'home', analyzeUrl: row.url } }));
            });
          }
          card.append(itemEl);
        }

        const footer = document.createElement('div');
        footer.className = 'row-wrap';
        footer.style.justifyContent = 'flex-end';
        footer.style.marginTop = '10px';
        if (isRunning) {
          const stopBtn = document.createElement('button');
          stopBtn.type = 'button';
          stopBtn.className = 'btn btn-tonal btn-sm';
          stopBtn.textContent = '停止添加下载';
          stopBtn.onclick = async () => {
            stopBtn.disabled = true;
            try { await post(`/api/batches/${job.id}/cancel`); await refresh(); }
            catch (e) { toast(e.message, 'error'); }
          };
          footer.append(stopBtn);
        }
        if (!isRunning && hasRetryable) {
          const retryBtn = document.createElement('button');
          retryBtn.type = 'button';
          retryBtn.className = 'btn btn-primary btn-sm';
          retryBtn.innerHTML = `${iconSvg('refresh')}<span>重试失败或停止的链接</span>`;
          retryBtn.onclick = async () => {
            retryBtn.disabled = true;
            try { await post(`/api/batches/${job.id}/retry`); await refresh(); }
            catch (e) { toast(e.message, 'error'); }
          };
          footer.append(retryBtn);
        }
        if (footer.childElementCount) card.append(footer);
        container.append(card);
      }
    } catch (error) {
      showError(error.message);
    } finally {
      state.refreshing = false;
    }
  }

  refresh();
  setInterval(() => { if (!document.hidden) refresh(); }, 2200);
  document.addEventListener('omni:language', () => { renderDraftLinks(); refresh(); });
}
