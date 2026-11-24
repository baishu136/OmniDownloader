/* 备用中转网站（对齐 RelaySitesSection.kt & RelaySiteCard.kt） */
import { get, post, del } from './api.js';
import { toast, openDialog, closeDialog, confirmDialog, escapeHtml } from './ui.js';
import { iconSvg } from './icons.js';
import { t } from './i18n.js';

const state = { sites: [], hasShownTip: false, manualName: false, lastFetched: '', expanded: false };
let fetchTimer = null;

function brandMeta(name, url) {
  const lowerName = (name || '').toLowerCase();
  const lowerUrl = (url || '').toLowerCase();
  if (lowerName.includes('bili') || lowerUrl.includes('bilibili')) return ['B', '#FB7299'];
  if (lowerName.includes('twitter') || lowerName.includes('x2') || lowerUrl.includes('twitter')) return ['X', '#1D9BF0'];
  if (lowerName.includes('green') || lowerUrl.includes('greenvideo')) return ['G', '#10B981'];
  if (lowerName.includes('snap') || lowerUrl.includes('snapany')) return ['S', '#8B5CF6'];
  if (lowerName.includes('tiktok') || lowerUrl.includes('tiktok')) return ['T', '#06B6D4'];
  if (lowerName.includes('youtube') || lowerUrl.includes('youtube')) return ['Y', '#EF4444'];
  const ch = (name || '').trim().slice(0, 1).toUpperCase() || 'W';
  const palette = ['#6366F1', '#EC4899', '#F59E0B', '#14B8A6', '#3B82F6', '#8B5CF6'];
  let hash = 0;
  for (let i = 0; i < (name || '').length; i++) hash = ((hash << 5) - hash + name.charCodeAt(i)) | 0;
  return [ch, palette[Math.abs(hash) % palette.length]];
}

function hostOf(url) {
  try {
    return new URL(url).host || url;
  } catch (e) {
    return (url || '').replace(/^https?:\/\//, '');
  }
}

export function relaySites() {
  return state.sites;
}

export async function loadRelaySites() {
  try {
    const data = await get('/api/relay-sites');
    state.sites = data.relaySites || [];
    state.hasShownTip = Boolean(data.hasShownRelayCompatTip);
  } catch (e) {
    state.sites = [];
  }
  renderHome();
  renderSettings();
}

export function renderHome() {
  const section = document.getElementById('relay-section');
  const list = document.getElementById('relay-home-list');
  const expandBtn = document.getElementById('btn-relay-expand');
  if (!section || !list) return;

  if (!state.sites.length) {
    section.hidden = true;
    list.replaceChildren();
    return;
  }

  section.hidden = false;
  const visible = state.expanded || state.sites.length <= 3 ? state.sites : state.sites.slice(0, 3);

  list.replaceChildren(...visible.map((site) => {
    const [ch, color] = brandMeta(site.name, site.url);
    const card = document.createElement('div');
    card.className = 'relay-site-card';
    card.innerHTML = `
      <div class="relay-site-card__logo" style="background:${color}22;color:${color}">${escapeHtml(ch)}</div>
      <div class="relay-site-card__body" title="${escapeHtml(site.url || '')}">
        <div class="relay-site-card__name">${escapeHtml(site.name || t('备用站点'))}</div>
        <div class="relay-site-card__domain">${escapeHtml(hostOf(site.url))}</div>
      </div>
      <button class="btn btn-primary-container btn-sm" type="button">
        ${iconSvg('open_in_new')}
        <span>${escapeHtml(t('访问解析'))}</span>
      </button>`;
    card.addEventListener('click', () => openSite(site.url));
    return card;
  }));

  if (expandBtn) {
    if (state.sites.length > 3) {
      expandBtn.hidden = false;
      expandBtn.innerHTML = state.expanded
        ? `${iconSvg('expand_less')}<span>${escapeHtml(t('收起'))}</span>`
        : `${iconSvg('expand_more')}<span>${escapeHtml(t('展开全部 (%d)', state.sites.length))}</span>`;
      expandBtn.onclick = () => {
        state.expanded = !state.expanded;
        renderHome();
      };
    } else {
      expandBtn.hidden = true;
    }
  }
}

export function renderSettings() {
  const list = document.getElementById('relay-settings-list');
  const title = document.getElementById('relay-header-title');
  if (title) title.textContent = t('第三方中转网站 (%d)', state.sites.length);
  if (!list) return;

  if (!state.sites.length) {
    list.innerHTML = `<div class="omni-card"><p class="caption" style="padding:12px 0">${escapeHtml(t('暂无配置任何中转网站，点击右上角添加'))}</p></div>`;
    return;
  }

  list.replaceChildren(...state.sites.map((site) => {
    const [ch, color] = brandMeta(site.name, site.url);
    const card = document.createElement('div');
    card.className = 'omni-card omni-card--tight';
    card.innerHTML = `
      <div class="row" style="gap:10px;padding:4px 6px;border-radius:12px;background:var(--surface-variant-30)">
        <div class="relay-site-card__logo" style="width:32px;height:32px;border-radius:9px;font-size:12px;background:${color}24;color:${color}">${escapeHtml(ch)}</div>
        <div style="flex:1;min-width:0">
          <div class="truncate" style="font-size:13.5px;font-weight:600">${escapeHtml(site.name || t('备用站点'))}</div>
          <div class="truncate caption">${escapeHtml(hostOf(site.url))}</div>
        </div>
        <button class="icon-btn icon-btn--sm" title="打开" aria-label="打开" type="button">${iconSvg('open_in_new')}</button>
        <button class="icon-btn icon-btn--sm icon-btn--danger" title="删除" aria-label="删除" type="button">${iconSvg('delete')}</button>
      </div>`;
    const [openBtn, deleteBtn] = card.querySelectorAll('button');
    openBtn.addEventListener('click', () => openSite(site.url));
    deleteBtn.addEventListener('click', () => deleteSite(site));
    return card;
  }));
}

function openSite(url) {
  if (!url) return;
  const input = document.getElementById('input-url');
  const value = (input && input.value || '').trim();
  if (value && navigator.clipboard) {
    navigator.clipboard.writeText(value).then(() => {
      toast('已自动复制当前链接到剪贴板，请在打开的中转网站粘贴使用', 'info');
    }).catch(() => {});
  }
  window.open(url, '_blank', 'noopener');
}

export function openAddFlow() {
  if (!state.hasShownTip) openDialog('relay-compat-modal');
  else openAddDialog();
}

export async function confirmCompat() {
  closeDialog('relay-compat-modal');
  state.hasShownTip = true;
  try { await post('/api/relay-sites/confirm-tip'); } catch (e) { /* ignore */ }
  openAddDialog();
}

function openAddDialog() {
  document.getElementById('input-site-url').value = '';
  document.getElementById('input-site-name').value = '';
  const errEl = document.getElementById('add-relay-error');
  errEl.textContent = '';
  errEl.hidden = true;
  document.getElementById('fetch-title-status').hidden = true;
  state.manualName = false;
  state.lastFetched = '';
  openDialog('add-relay-modal');
}

async function fetchTitle() {
  const url = document.getElementById('input-site-url').value.trim();
  if (!url) { toast('请先输入网址', 'warning'); return; }
  const status = document.getElementById('fetch-title-status');
  status.hidden = false;
  try {
    const data = await get(`/api/relay-sites/fetch-title?url=${encodeURIComponent(url)}`);
    if (data && data.title) {
      const nameInput = document.getElementById('input-site-name');
      if (!state.manualName || !nameInput.value || nameInput.value === state.lastFetched) {
        nameInput.value = data.title;
        state.lastFetched = data.title;
        state.manualName = false;
      }
    }
  } catch (e) {
    toast('未能自动获取名称，请手动输入', 'warning');
  } finally {
    status.hidden = true;
  }
}

async function submitSite() {
  const name = document.getElementById('input-site-name').value.trim();
  let url = document.getElementById('input-site-url').value.trim();
  const errorEl = document.getElementById('add-relay-error');
  errorEl.textContent = '';
  errorEl.hidden = true;
  if (!name || !url) {
    errorEl.textContent = t('请完整填写网站名称与网址。');
    errorEl.hidden = false;
    return;
  }
  if (!/^https?:\/\//i.test(url)) url = `https://${url}`;
  const button = document.getElementById('btn-submit-relay');
  button.disabled = true;
  try {
    const data = await post('/api/relay-sites', { name, url });
    state.sites = data.relaySites || [];
    renderHome();
    renderSettings();
    closeDialog('add-relay-modal');
    toast('已添加中转站');
  } catch (e) {
    errorEl.textContent = e.message;
    errorEl.hidden = false;
  } finally {
    button.disabled = false;
  }
}

async function deleteSite(site) {
  const ok = await confirmDialog({
    title: '删除中转网站',
    message: t('确定要删除中转网站「%s」吗？', site.name || site.url),
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    const data = await del(`/api/relay-sites/${site.id}`);
    state.sites = data.relaySites || [];
    renderHome();
    renderSettings();
    toast('已移除中转站');
  } catch (e) {
    toast(e.message, 'error');
  }
}

export function initRelay() {
  document.getElementById('btn-relay-compat-confirm')?.addEventListener('click', confirmCompat);
  document.getElementById('btn-add-relay')?.addEventListener('click', openAddFlow);
  document.getElementById('btn-submit-relay')?.addEventListener('click', submitSite);
  document.getElementById('btn-fetch-title')?.addEventListener('click', fetchTitle);

  const urlInput = document.getElementById('input-site-url');
  if (urlInput) {
    urlInput.addEventListener('input', () => {
      clearTimeout(fetchTimer);
      const value = urlInput.value.trim();
      if (!value || value.length < 5 || !value.includes('.')) return;
      fetchTimer = setTimeout(fetchTitle, 700);
    });
  }

  document.getElementById('input-site-name')?.addEventListener('input', () => { state.manualName = true; });
  document.addEventListener('omni:language', () => { renderHome(); renderSettings(); });
  loadRelaySites();
}
