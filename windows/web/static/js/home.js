/* S01 首页：品牌区、平台标签、输入卡片、FormatSelectorSheet 抽屉、错误弹窗、最近任务（对齐 HomeScreen.kt + FormatSelectorSheet.kt） */
import { post, get } from './api.js';
import { toast, escapeHtml, confirmDialog, openDialog, closeDialog, showErrorDetailDialog } from './ui.js';
import { statusInfo, typeLabel } from './labels.js';
import { iconSvg } from './icons.js';
import { showPost, clearPost } from './images.js';
import { t } from './i18n.js';

const PLATFORM_COLORS = {
  '哔哩哔哩': '#FB7299',
  '抖音': '#6E7382',
  '快手': '#FF5000',
  '小红书': '#FF2442',
  '微博': '#E34B53',
  'YouTube': '#FF0000',
  '西瓜视频': '#3B82F6',
  'TikTok': '#00B4C6',
  'X (Twitter)': '#1DA1F2',
  'Instagram': '#E1306C',
  'Facebook': '#1877F2',
  'Pixiv': '#0096FA',
  '小黑盒': '#64748B',
};

const state = {
  tabs: [],
  activeTabId: null,
  tabSeq: 0,
  metadata: null,
  selectedMultiIndex: 0,
  isCollectionMode: false,
  selectedEpisodes: new Set(),
  saveCoverWithDownload: false,
  type: 'VIDEO_WITH_AUDIO',
  formatId: '',
  audioFormat: 'mp3',
  controller: null,
  lastErrorText: '',
  recent: [],
  coverLightboxUrl: '',
  coverLightboxTitle: '',
};

const $ = (id) => document.getElementById(id);

function copyTextWithToast(text, successMsg) {
  const val = String(text || '').trim();
  if (!val) return;
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(val)
      .then(() => toast(t(successMsg), 'success', 1800))
      .catch(() => toast(t('复制失败，请手动选中复制'), 'warning'));
  }
}

function saveActiveTabSnapshot() {
  if (!state.activeTabId) return;
  const tab = state.tabs.find((item) => item.id === state.activeTabId);
  if (!tab || tab.kind !== 'video') return;
  tab.selectedMultiIndex = state.selectedMultiIndex;
  tab.isCollectionMode = state.isCollectionMode;
  tab.selectedEpisodes = new Set(state.selectedEpisodes);
  tab.saveCoverWithDownload = state.saveCoverWithDownload;
  tab.type = state.type;
  tab.container = $('quality-container')?.value || 'auto';
  tab.formatId = state.formatId;
  tab.audioFormat = state.audioFormat;
}

function renderWorkspaceTabs() {
  const bar = $('workspace-tabs-bar');
  const list = $('workspace-tabs-list');
  if (!bar || !list) return;
  if (!state.tabs.length) {
    bar.hidden = true;
    list.replaceChildren();
    return;
  }
  bar.hidden = false;
  list.replaceChildren();
  for (const tab of state.tabs) {
    const active = tab.id === state.activeTabId;
    const btn = document.createElement('div');
    btn.className = `bd-tab${active ? ' is-active' : ''}`;
    btn.setAttribute('role', 'tab');
    btn.setAttribute('tabindex', '0');
    btn.setAttribute('aria-selected', String(active));
    btn.title = `${tab.title}\n${t('单击切换 · 双击关闭')}`;

    const dotColor = PLATFORM_COLORS[tab.siteName] || 'var(--primary)';
    let badgeText = '';
    if (tab.kind === 'images') {
      const n = tab.imagePost?.resources?.length || 0;
      badgeText = n > 0 ? `${t('图文')} ${n}P` : t('图文');
    } else if ((tab.metadata?.multiMediaList?.length || 0) > 1) {
      badgeText = `${tab.metadata.multiMediaList.length}P`;
    }

    btn.innerHTML = `
      <span class="bd-tab__dot" style="background:${dotColor}" aria-hidden="true"></span>
      <span class="bd-tab__title" data-no-i18n>${escapeHtml(tab.title || t('未命名媒体'))}</span>
      ${badgeText ? `<span class="bd-tab__badge">${escapeHtml(badgeText)}</span>` : ''}
      <button class="bd-tab__close" type="button" aria-label="${escapeHtml(t('关闭标签页'))}" title="${escapeHtml(t('关闭标签页'))}">${iconSvg('close')}</button>`;

    btn.addEventListener('click', (event) => {
      if (event.target.closest('.bd-tab__close')) {
        event.stopPropagation();
        closeWorkspaceTab(tab.id);
        return;
      }
      activateWorkspaceTab(tab.id);
    });
    btn.addEventListener('dblclick', (event) => {
      event.stopPropagation();
      closeWorkspaceTab(tab.id);
    });
    btn.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        activateWorkspaceTab(tab.id);
      } else if (event.key === 'Delete') {
        event.preventDefault();
        closeWorkspaceTab(tab.id);
      }
    });
    list.appendChild(btn);
  }
}

function upsertWorkspaceTab(kind, rawUrl, data) {
  saveActiveTabSnapshot();
  const canonicalUrl = (data?.url || rawUrl || '').trim();
  let tab = state.tabs.find((item) => item.url === canonicalUrl);
  const title = data?.title || t(kind === 'images' ? '已解析图文' : '未命名媒体');
  const siteName = data?.siteName || data?.platform || t('网络媒体');

  if (!tab) {
    state.tabSeq += 1;
    tab = {
      id: `tab-${state.tabSeq}`,
      url: canonicalUrl,
      kind,
      title,
      siteName,
      metadata: null,
      imagePost: null,
      selectedMultiIndex: 0,
      isCollectionMode: false,
      selectedEpisodes: new Set(),
      saveCoverWithDownload: false,
      type: 'VIDEO_WITH_AUDIO',
      container: 'auto',
      formatId: '',
      audioFormat: 'mp3',
    };
    state.tabs.push(tab);
    if (state.tabs.length > 12) state.tabs.shift();
  }

  tab.kind = kind;
  tab.title = title;
  tab.siteName = siteName;

  if (kind === 'images') {
    tab.imagePost = data;
    tab.metadata = null;
  } else {
    tab.metadata = data;
    tab.imagePost = null;
    const multi = data.multiMediaList || [];
    const first = (multi.length ? multi[0] : data) || null;
    tab.selectedMultiIndex = 0;
    tab.isCollectionMode = false;
    tab.selectedEpisodes = new Set(multi.map((_, idx) => idx));
    tab.saveCoverWithDownload = false;
    tab.type = first && first.isGif ? 'GIF' : 'VIDEO_WITH_AUDIO';
    tab.container = $('quality-container')?.value || 'auto';
    tab.formatId = (first && first.availableVideoFormats && first.availableVideoFormats[0]?.formatId) || '';
    tab.audioFormat = state.audioFormat || 'mp3';
  }

  state.activeTabId = null;
  activateWorkspaceTab(tab.id);
}

function activateWorkspaceTab(tabId) {
  if (state.activeTabId && state.activeTabId !== tabId) {
    saveActiveTabSnapshot();
  }
  const tab = state.tabs.find((item) => item.id === tabId);
  if (!tab) return;
  state.activeTabId = tab.id;
  hideError();

  if ($('input-url') && tab.url) {
    $('input-url').value = tab.url;
    updateInputUi();
  }

  if (tab.kind === 'images') {
    state.metadata = null;
    closeDialog('format-sheet-overlay');
    if ($('parsed-resume-bar')) $('parsed-resume-bar').hidden = true;
    showPost(tab.imagePost);
  } else {
    clearPost();
    state.metadata = tab.metadata;
    state.selectedMultiIndex = tab.selectedMultiIndex;
    state.isCollectionMode = tab.isCollectionMode;
    state.selectedEpisodes = new Set(tab.selectedEpisodes);
    state.saveCoverWithDownload = tab.saveCoverWithDownload;
    state.type = tab.type;
    state.formatId = tab.formatId;
    state.audioFormat = tab.audioFormat;
    if ($('toggle-save-cover')) $('toggle-save-cover').checked = tab.saveCoverWithDownload;
    if ($('quality-container')) $('quality-container').value = tab.container || 'auto';
    renderSheet();
    openDialog('format-sheet-overlay');
  }
  renderWorkspaceTabs();
}

function closeWorkspaceTab(tabId) {
  const idx = state.tabs.findIndex((item) => item.id === tabId);
  if (idx === -1) return;
  const wasActive = state.activeTabId === tabId;
  state.tabs.splice(idx, 1);

  if (!state.tabs.length) {
    state.activeTabId = null;
    state.metadata = null;
    closeDialog('format-sheet-overlay');
    clearPost();
    if ($('parsed-resume-bar')) $('parsed-resume-bar').hidden = true;
    renderWorkspaceTabs();
    return;
  }

  if (wasActive) {
    state.activeTabId = null;
    const nextTab = state.tabs[Math.min(idx, state.tabs.length - 1)];
    activateWorkspaceTab(nextTab.id);
  } else {
    renderWorkspaceTabs();
  }
}

function closeAllWorkspaceTabs() {
  state.tabs = [];
  state.activeTabId = null;
  state.metadata = null;
  closeDialog('format-sheet-overlay');
  clearPost();
  if ($('parsed-resume-bar')) $('parsed-resume-bar').hidden = true;
  renderWorkspaceTabs();
}

function openCoverPreviewDialog(coverUrl, title) {
  if (!coverUrl) {
    toast(t('暂无封面可预览'), 'warning');
    return;
  }
  let dialog = $('bd-cover-preview-dialog');
  if (!dialog) {
    dialog = document.createElement('dialog');
    dialog.id = 'bd-cover-preview-dialog';
    dialog.className = 'bd-cover-dialog';
    dialog.innerHTML = `
      <div class="dialog__header" style="padding:12px 16px;border-bottom:1px solid var(--border)">
        <div class="dialog__title truncate" id="bd-cover-dlg-title" data-no-i18n style="flex:1;min-width:0"></div>
        <button class="icon-btn icon-btn--sm" id="bd-cover-dlg-close" type="button" aria-label="${escapeHtml(t('关闭'))}">${iconSvg('close')}</button>
      </div>
      <div style="padding:12px;background:#0B0C10;text-align:center">
        <img class="bd-cover-dialog__img" id="bd-cover-dlg-img" alt="">
      </div>
      <div class="dialog__footer" style="padding:10px 16px;border-top:1px solid var(--border);justify-content:flex-end;gap:8px">
        <button class="btn btn-tonal btn-sm" id="bd-cover-dlg-copy" type="button">${iconSvg('link')}<span>${escapeHtml(t('复制封面链接'))}</span></button>
        <button class="btn btn-primary btn-sm" id="bd-cover-dlg-save" type="button">${iconSvg('download')}<span>${escapeHtml(t('单独保存封面'))}</span></button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.querySelector('#bd-cover-dlg-close').addEventListener('click', () => dialog.close());
    dialog.addEventListener('click', (e) => { if (e.target === dialog) dialog.close(); });
    dialog.querySelector('#bd-cover-dlg-copy').addEventListener('click', () => {
      copyTextWithToast(state.coverLightboxUrl, '已复制封面链接');
    });
    dialog.querySelector('#bd-cover-dlg-save').addEventListener('click', () => {
      saveCoverDirectly();
      dialog.close();
    });
  }
  state.coverLightboxUrl = coverUrl;
  state.coverLightboxTitle = title || t('封面预览');
  dialog.querySelector('#bd-cover-dlg-title').textContent = state.coverLightboxTitle;
  dialog.querySelector('#bd-cover-dlg-img').src = coverUrl;
  if (!dialog.open) dialog.showModal();
}

export function initHome() {
  const input = $('input-url');
  const analyzeBtn = $('btn-analyze');

  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      if (!state.controller && input.value.trim()) analyze();
    }
  });
  input.addEventListener('input', () => {
    updateInputUi();
  });

  analyzeBtn.addEventListener('click', () => {
    if (state.controller) cancelAnalyze();
    else analyze();
  });

  // 行内小图标：为空时粘贴（不自动解析），非空时清空输入（保留已解析多标签页）
  $('btn-input-inline').addEventListener('click', () => {
    if (input.value.trim()) {
      clearInputOnly();
    } else {
      pasteClipboard(false);
    }
  });

  $('btn-tab-new')?.addEventListener('click', () => {
    saveActiveTabSnapshot();
    clearInputOnly();
    closeDialog('format-sheet-overlay');
    clearPost();
    state.metadata = null;
    state.activeTabId = null;
    renderWorkspaceTabs();
  });

  $('btn-tabs-close-all')?.addEventListener('click', closeAllWorkspaceTabs);

  $('btn-copy-title')?.addEventListener('click', () => {
    copyTextWithToast($('sheet-media-title')?.textContent, '已复制标题');
  });
  $('sheet-media-title')?.addEventListener('dblclick', () => {
    copyTextWithToast($('sheet-media-title')?.textContent, '已复制标题');
  });
  $('meta-author')?.addEventListener('click', () => {
    copyTextWithToast($('meta-author')?.textContent, '已复制作者名称');
  });
  $('meta-media-id')?.addEventListener('click', () => {
    const media = currentMedia();
    copyTextWithToast(media?.mediaId || state.metadata?.mediaId || media?.url || state.metadata?.url, '已复制媒体标识');
  });
  $('btn-copy-cover-url')?.addEventListener('click', () => {
    const media = currentMedia();
    copyTextWithToast(media?.thumbnailUrl || state.metadata?.thumbnailUrl, '已复制封面链接');
  });
  $('sheet-thumb-box')?.addEventListener('click', () => {
    const media = currentMedia();
    const coverUrl = media?.thumbnailUrl || state.metadata?.thumbnailUrl || '';
    openCoverPreviewDialog(coverUrl, media?.title || state.metadata?.title || '');
  });
  $('sheet-thumb-box')?.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      const media = currentMedia();
      openCoverPreviewDialog(media?.thumbnailUrl || state.metadata?.thumbnailUrl || '', media?.title || state.metadata?.title || '');
    }
  });

  // 左侧大按钮：粘贴剪贴板并立即解析（对齐 Android checkClipboardAndPaste(autoDownload = true)）
  $('btn-paste')?.addEventListener('click', () => pasteClipboard(true));

  // 浏览器辅助按钮
  $('btn-browser-assist')?.addEventListener('click', openBrowserAssist);
  $('btn-capture-media')?.addEventListener('click', capturePlayedMedia);
  $('btn-capture-images')?.addEventListener('click', captureBrowserImages);

  // 重新打开上次解析结果
  const resumeBar = $('parsed-resume-bar');
  if (resumeBar) {
    resumeBar.addEventListener('click', () => {
      if (state.metadata) openFormatSheet();
    });
    resumeBar.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && state.metadata) openFormatSheet();
    });
  }

  // Sheet 内事件绑定
  $('btn-mode-single')?.addEventListener('click', () => setCollectionMode(false));
  $('btn-mode-collection')?.addEventListener('click', () => setCollectionMode(true));

  $('btn-episodes-all')?.addEventListener('click', () => setEpisodes('all'));
  $('btn-episodes-invert')?.addEventListener('click', () => setEpisodes('invert'));
  $('btn-episodes-none')?.addEventListener('click', () => setEpisodes('none'));

  $('btn-save-cover-direct')?.addEventListener('click', saveCoverDirectly);
  $('toggle-save-cover')?.addEventListener('change', (e) => {
    state.saveCoverWithDownload = e.target.checked;
    updateFooterButtons();
  });

  $('download-type-container')?.addEventListener('click', (event) => {
    const chip = event.target.closest('[data-type]');
    if (chip) selectType(chip.dataset.type);
  });

  $('download-type-select')?.addEventListener('change', (event) => {
    selectType(event.target.value);
  });

  $('format-options-container')?.addEventListener('click', (event) => {
    const row = event.target.closest('[data-format]');
    if (!row) return;
    state.formatId = row.dataset.format;
    renderFormats(currentMedia()?.availableVideoFormats || []);
    updateFooterButtons();
  });

  $('quality-container')?.addEventListener('change', () => {
    updateFooterButtons();
  });

  $('audio-options-container')?.addEventListener('click', (event) => {
    const row = event.target.closest('[data-audio]');
    if (!row) return;
    state.audioFormat = row.dataset.audio;
    updateAudioRows();
    updateFooterButtons();
  });

  $('btn-submit-download')?.addEventListener('click', () => submitDownload(false));
  $('btn-sheet-secondary')?.addEventListener('click', () => {
    if (state.isCollectionMode) {
      // 全部下载
      submitDownload(true);
    } else {
      // 切换为合集多选模式
      setCollectionMode(true);
    }
  });

  // 回到顶部 FAB
  const ws = $('workspace');
  const topBtn = $('btn-home-top');
  if (ws && topBtn) {
    ws.addEventListener('scroll', () => {
      const hasImages = !$('image-result')?.hidden;
      topBtn.hidden = !(hasImages && ws.scrollTop > 320);
    });
    topBtn.addEventListener('click', () => {
      ws.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  document.addEventListener('omni:tasks', (event) => renderRecent(event.detail || []));
  document.addEventListener('omni:settings', () => renderRecent(state.allRecentSource || state.recent));
  document.addEventListener('omni:language', () => {
    renderWorkspaceTabs();
    if (state.metadata) renderSheet();
    if (state.recent.length) renderRecent(state.allRecentSource || state.recent);
  });
  updateInputUi();
}

export function cancelAnalyzing() {
  if (state.controller) state.controller.abort();
}

export function fillAndAnalyze(url) {
  const input = $('input-url');
  if (!input) return;
  input.value = url;
  updateInputUi();
  analyze();
}

/* ---------- 输入状态 ---------- */
function updateInputUi() {
  const input = $('input-url');
  const inlineBtn = $('btn-input-inline');
  const analyzeBtn = $('btn-analyze');
  const hasValue = Boolean(input && input.value.trim());

  if (inlineBtn) {
    inlineBtn.innerHTML = iconSvg(hasValue ? 'close' : 'content_paste');
    inlineBtn.title = t(hasValue ? '清除' : '粘贴');
    inlineBtn.setAttribute('aria-label', t(hasValue ? '清除' : '粘贴'));
  }
  if (analyzeBtn && !state.controller) {
    analyzeBtn.disabled = !hasValue;
  }
}

async function pasteClipboard(autoAnalyze = false) {
  try {
    const text = await navigator.clipboard.readText();
    if (!text || !text.trim()) {
      toast(t('剪贴板为空'), 'warning');
      return;
    }
    $('input-url').value = text.trim();
    updateInputUi();
    if (autoAnalyze) {
      toast(t('已粘贴，开始解析...'), 'info', 2000);
      analyze();
    } else {
      $('input-url').focus();
      toast(t('已粘贴剪贴板内容'), 'info', 1800);
    }
  } catch (e) {
    toast(t('无法读取剪贴板，请使用 Ctrl+V 手动粘贴'), 'warning');
    $('input-url').focus();
  }
}

export function focusInput() {
  const input = $('input-url');
  if (input) { input.focus(); input.select(); }
}

export function pasteFromShortcut() {
  const active = document.activeElement;
  const typing = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA');
  if (!typing) pasteClipboard(false);
}

function clearInputOnly() {
  $('input-url').value = '';
  updateInputUi();
  hideError();
  $('input-url').focus();
}

function clearAll() {
  $('input-url').value = '';
  updateInputUi();
  hideError();
  clearPost();
  state.metadata = null;
  closeDialog('format-sheet-overlay');
  if ($('parsed-resume-bar')) $('parsed-resume-bar').hidden = true;
  $('input-url').focus();
}

/* ---------- 解析 ---------- */
async function analyze() {
  const url = $('input-url').value.trim();
  if (!url) {
    toast(t('请输入视频链接或分享文本'), 'warning');
    $('input-url').focus();
    return;
  }
  hideError();
  setAnalyzing(true);
  state.controller = new AbortController();
  try {
    const parsed = await post('/api/media/analyze', { url }, { signal: state.controller.signal });
    if (parsed.kind === 'images') {
      upsertWorkspaceTab('images', url, parsed.data);
      return;
    }
    upsertWorkspaceTab('video', url, parsed.data);
  } catch (error) {
    if (error && error.name === 'AbortError') {
      toast(t('已取消解析'), 'info');
    } else {
      showAnalyzeError(error);
    }
  } finally {
    state.controller = null;
    setAnalyzing(false);
    updateInputUi();
  }
}

function cancelAnalyze() {
  if (state.controller) state.controller.abort();
}

function setAnalyzing(analyzing) {
  const btn = $('btn-analyze');
  const iconWrap = $('analyze-btn-icon');
  const label = $('analyze-label');
  if (!btn || !label) return;
  btn.toggleAttribute('aria-busy', analyzing);
  btn.disabled = false;
  if (analyzing) {
    if (iconWrap) iconWrap.innerHTML = '<span class="spinner-ring"></span>';
    label.textContent = t('解析中...');
  } else {
    if (iconWrap) iconWrap.innerHTML = iconSvg('bolt');
    label.textContent = t('立即解析');
  }
}

/* ---------- 错误处理 ---------- */
function hideError() {
  $('parse-error').hidden = true;
  $('parse-error-actions').replaceChildren();
}

function describeError(error) {
  const raw = String((error && (error.detail || error.message)) || '');
  state.lastErrorText = raw;
  const text = raw.toLowerCase();
  if (/unsupported|not supported|不支持|无法识别|no suitable/.test(text)) {
    return { title: t('解析未能成功'), hint: raw || t('请确认链接来自受支持的平台，或尝试复制完整的分享文本。'), action: 'detail' };
  }
  if (/login|登录|cookie|sessdata|auth|验证|captcha|auth_required|challenge/.test(text)) {
    return { title: t('需要登录或 Cookie 已失效'), hint: raw || t('请在「设置 → 账号」中导入会话或 Cookie 后重试。'), action: 'accounts' };
  }
  if (/network|timeout|timed out|connection|网络|连接|dns/.test(text)) {
    return { title: t('网络连接失败'), hint: raw || t('请检查网络或代理设置后重试。'), action: 'network' };
  }
  return { title: t('解析未能成功'), hint: raw || t('请稍后重试。'), action: 'detail' };
}

function showAnalyzeError(error) {
  const info = describeError(error);
  const minimal = document.documentElement.dataset.client === 'web-lite';
  if (minimal && info.action === 'accounts') info.hint = t('请在桌面客户端的「设置 → 账号」完成登录或导入会话，然后重试。');
  if (minimal && info.action === 'network') info.hint = t('请检查网络；代理设置可在桌面客户端中调整，然后重试。');
  $('parse-error-title').textContent = info.title;
  $('parse-error-hint').textContent = info.hint;
  const actions = $('parse-error-actions');
  actions.replaceChildren();

  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'btn btn-tonal btn-sm';
  retry.textContent = t('重试');
  retry.addEventListener('click', analyze);
  actions.appendChild(retry);

  const detail = document.createElement('button');
  detail.type = 'button';
  detail.className = 'btn btn-text btn-sm';
  detail.textContent = t('查看详情');
  detail.addEventListener('click', () => {
    showErrorDetailDialog({
      title: info.title,
      message: state.lastErrorText || info.hint,
      showSettingsAction: !minimal && (info.action === 'accounts' || info.action === 'network'),
      onGoSettings: () => {
        const cat = info.action === 'accounts' ? 'accounts' : 'network';
        document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'settings', cat } }));
      },
    });
  });
  actions.appendChild(detail);

  if (!minimal && (info.action === 'accounts' || info.action === 'network')) {
    const goBtn = document.createElement('button');
    goBtn.type = 'button';
    goBtn.className = 'btn btn-text btn-sm';
    goBtn.textContent = t(info.action === 'accounts' ? '前往账号设置' : '设置代理');
    goBtn.addEventListener('click', () => {
      const cat = info.action === 'accounts' ? 'accounts' : 'network';
      document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'settings', cat } }));
    });
    actions.appendChild(goBtn);
  }

  $('parse-error').hidden = false;
}

/* ---------- FormatSelectorSheet 渲染与交互 ---------- */
function currentMedia() {
  const m = state.metadata;
  if (!m) return null;
  if (Array.isArray(m.multiMediaList) && m.multiMediaList.length > 0) {
    return m.multiMediaList[state.selectedMultiIndex] || m.multiMediaList[0];
  }
  return m;
}

function renderResumeBar(data) {
  const bar = $('parsed-resume-bar');
  if (!bar) return;
  $('parsed-resume-title').textContent = data.title || t('已解析媒体');
  $('parsed-resume-sub').textContent = `${t(data.siteName || '网络视频')}${data.durationText ? ' · ' + data.durationText : ''} · ${t('点击选择画质并开始下载')}`;
  bar.hidden = $('format-sheet-overlay')?.dataset.inline === 'true';
}

function openFormatSheet() {
  const panel = $('format-sheet-overlay');
  if (panel?.dataset.inline === 'true') {
    panel.hidden = false;
    return;
  }
  openDialog('format-sheet-overlay');
}

function renderSheet() {
  const metadata = state.metadata;
  const media = currentMedia();
  if (!metadata || !media) return;

  $('sheet-media-title').textContent = media.title || metadata.title || t('未命名媒体');
  $('meta-site').textContent = t(media.siteName || metadata.siteName || '网络视频');
  $('meta-gif-badge').hidden = !media.isGif;
  $('meta-duration').textContent = media.durationText || '';
  $('meta-author').textContent = media.author || metadata.author || '';

  const thumbBox = $('sheet-thumb-box');
  const thumb = $('meta-thumb');
  const coverUrl = media.thumbnailUrl || metadata.thumbnailUrl || '';
  if (coverUrl) {
    thumb.src = coverUrl;
    thumbBox.hidden = false;
    $('save-cover-toggle-wrap').hidden = false;
  } else {
    thumb.removeAttribute('src');
    thumbBox.hidden = true;
    $('save-cover-toggle-wrap').hidden = true;
  }

  const durEl = $('meta-duration');
  if (durEl) {
    durEl.textContent = media.durationText || metadata.durationText || '';
    durEl.hidden = !durEl.textContent;
  }
  const authorEl = $('meta-author');
  if (authorEl) {
    authorEl.textContent = media.author || metadata.author || '';
    authorEl.hidden = !authorEl.textContent;
  }
  const mediaIdEl = $('meta-media-id');
  if (mediaIdEl) {
    const rawId = media.mediaId || metadata.mediaId || '';
    mediaIdEl.textContent = rawId;
    mediaIdEl.hidden = !rawId;
  }

  const accessEl = $('meta-access');
  if ((media.siteName || metadata.siteName) === '哔哩哔哩') {
    accessEl.textContent = t('当前账号：%s · 访问状态：%s', media.accountName || t('未验证'), media.accessState || 'UNKNOWN');
    accessEl.hidden = false;
  } else {
    accessEl.hidden = true;
  }

  const multi = metadata.multiMediaList || [];
  const colWrap = $('collection-wrapper');
  if (multi.length > 1) {
    colWrap.hidden = false;
    $('btn-mode-collection-label').textContent = t('下载合集 (%d)', multi.length);
    renderMultiParts(metadata, multi);
    renderEpisodes(multi);
    updateCollectionModeUi();
  } else {
    colWrap.hidden = true;
  }

  const evEl = $('sheet-quality-evidence');
  if (media.qualityEvidence) {
    evEl.textContent = media.qualityEvidence;
    evEl.hidden = false;
  } else {
    evEl.hidden = true;
  }

  const warnEl = $('sheet-quality-warning');
  if (media.qualityWarning) {
    warnEl.textContent = media.qualityWarning;
    warnEl.hidden = false;
  } else {
    warnEl.hidden = true;
  }

  selectType(state.type);
  renderFormats(media.availableVideoFormats || []);
  updateAudioRows();
  updateFooterButtons();
}

function setCollectionMode(enabled) {
  state.isCollectionMode = Boolean(enabled);
  updateCollectionModeUi();
  updateFooterButtons();
}

function updateCollectionModeUi() {
  const singleBtn = $('btn-mode-single');
  const colBtn = $('btn-mode-collection');
  singleBtn?.classList.toggle('is-active', !state.isCollectionMode);
  colBtn?.classList.toggle('is-active', state.isCollectionMode);
  $('single-parts-panel').hidden = state.isCollectionMode;
  $('collection-section').hidden = !state.isCollectionMode;
}

function renderMultiParts(metadata, list) {
  const prompt = $('single-parts-prompt');
  if (prompt) {
    prompt.textContent = t(metadata.siteName === '哔哩哔哩'
      ? '点击切换当前预览与下载的分P/分集:'
      : '点击切换当前预览与独立下载的视频:');
  }
  const row = $('single-parts-row');
  row.replaceChildren();
  list.forEach((item, index) => {
    const selected = index === state.selectedMultiIndex;
    const chipName = (item.title || '').includes(' - P') ? `P${index + 1}` : t('视频 %d', index + 1);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `multi-media-chip${selected ? ' is-selected' : ''}`;
    btn.innerHTML = `<span>${escapeHtml(chipName)}</span>${item.durationText ? `<span class="muted">${escapeHtml(item.durationText)}</span>` : ''}${item.isGif ? '<span class="sheet-tag sheet-tag--gif">GIF</span>' : ''}`;
    btn.addEventListener('click', () => {
      state.selectedMultiIndex = index;
      const cur = currentMedia();
      state.formatId = (cur?.availableVideoFormats && cur.availableVideoFormats[0]?.formatId) || 'best';
      renderSheet();
    });
    row.appendChild(btn);
  });
}

function renderEpisodes(list) {
  const container = $('episodes-list');
  container.replaceChildren();
  list.forEach((ep, index) => {
    const checked = state.selectedEpisodes.has(index);
    const pNum = (ep.title || '').includes(' - P') ? `P${index + 1}` : `${index + 1}`;
    const label = document.createElement('label');
    label.className = `episode-check-item${checked ? ' is-checked' : ''}`;
    label.innerHTML = `
      <input type="checkbox" ${checked ? 'checked' : ''}>
      <span class="episode-check-item__badge">${escapeHtml(pNum)}</span>
      <span class="episode-check-item__title">${escapeHtml(ep.title || t('第 %d 集', index + 1))}</span>
      ${ep.durationText ? `<span class="episode-check-item__dur">${escapeHtml(ep.durationText)}</span>` : ''}`;
    const box = label.querySelector('input');
    box.addEventListener('change', () => {
      if (box.checked) state.selectedEpisodes.add(index);
      else state.selectedEpisodes.delete(index);
      label.classList.toggle('is-checked', box.checked);
      updateEpisodesCount();
      updateFooterButtons();
    });
    container.appendChild(label);
  });
  updateEpisodesCount();
}

function setEpisodes(mode) {
  const multi = state.metadata?.multiMediaList || [];
  if (mode === 'all') state.selectedEpisodes = new Set(multi.map((_, i) => i));
  else if (mode === 'none') state.selectedEpisodes.clear();
  else {
    const next = new Set();
    multi.forEach((_, i) => { if (!state.selectedEpisodes.has(i)) next.add(i); });
    state.selectedEpisodes = next;
  }
  renderEpisodes(multi);
  updateFooterButtons();
}

function updateEpisodesCount() {
  const total = state.metadata?.multiMediaList?.length || 0;
  $('collection-count-badge').textContent = t('已选 %d / %d 集', state.selectedEpisodes.size, total);
}

function selectType(type) {
  state.type = type;
  const typeSelect = $('download-type-select');
  if (typeSelect && typeSelect.value !== type) typeSelect.value = type;
  document.querySelectorAll('#download-type-container .mode-tab-chip').forEach((chip) => {
    chip.classList.toggle('is-selected', chip.dataset.type === type);
  });
  const resSec = $('resolution-section');
  const audioSec = $('audio-format-section');
  const gifSec = $('gif-format-section');
  const containerField = $('quality-container-field');
  const hint = $('resolution-hint');

  const isVideo = type === 'VIDEO_WITH_AUDIO' || type === 'VIDEO_ONLY';
  resSec.hidden = !isVideo;
  if (containerField) containerField.hidden = !isVideo;
  audioSec.hidden = type !== 'AUDIO_ONLY';
  gifSec.hidden = type !== 'GIF';
  if (hint) {
    hint.textContent = t(type === 'VIDEO_ONLY'
      ? '纯画面模式：自动去除音轨导出静音视频'
      : '视频模式：自动匹配并无损合并最佳音轨');
  }
  updateFooterButtons();
}

function isHdrFormat(fmt) {
  const dr = String(fmt?.dynamicRange || '').trim().toUpperCase();
  if (dr && dr !== 'SDR') return true;
  const note = String(fmt?.note || '');
  return /\b(HDR|HLG|DOLBY)\b/i.test(note);
}

function formatCodecTag(vcodec) {
  if (!vcodec || String(vcodec).toLowerCase() === 'none') return '';
  const lower = String(vcodec).toLowerCase();
  if (lower.startsWith('avc') || lower.includes('h264')) return 'H.264';
  if (lower.startsWith('hev') || lower.startsWith('hvc') || lower.includes('h265')) return 'HEVC';
  if (lower.startsWith('av01') || lower === 'av1') return 'AV1';
  if (lower.startsWith('vp09') || lower.startsWith('vp9')) return 'VP9';
  return String(vcodec).split('.')[0].toUpperCase();
}

function tierBadgeText(fmt) {
  const w = Number(fmt?.width || 0);
  const h = Number(fmt?.height || 0);
  const edge = (w > 0 && h > 0) ? Math.min(w, h) : Math.max(w, h);
  if (edge <= 0) return '';
  if (edge >= 4320) return '8K';
  if (edge >= 3240) return '6K';
  if (edge >= 2880) return '5K';
  if (edge >= 2160) return '4K';
  return `${edge}p`;
}

function tileFormatLine(fmt) {
  const parts = [(fmt.ext || 'MP4').toUpperCase()];
  const codec = formatCodecTag(fmt.vcodec);
  if (codec) parts.push(codec);
  if (Number(fmt.fps || 0) >= 50) parts.push(`${fmt.fps}fps`);
  const dr = String(fmt.dynamicRange || '').trim().toUpperCase();
  if (dr && dr !== 'SDR') parts.push(dr);
  return parts.join(' · ');
}

function tileSizeLine(fmt) {
  if (fmt.approximateSize) return t(fmt.approximateSize);
  const kbps = Number(fmt.videoBitrateKbps || 0);
  if (kbps >= 1000) return `${(kbps / 1000).toFixed(1)} Mbps`;
  if (kbps > 0) return `${kbps.toFixed(0)} kbps`;
  if (fmt.note && !formatCodecTag(fmt.vcodec)) return t(fmt.note);
  return t('自动体积');
}

function renderFormats(formats) {
  const container = $('format-options-container');
  container.replaceChildren();
  if (!formats.length) {
    const empty = document.createElement('div');
    empty.className = 'caption';
    empty.textContent = t('将自动选择最佳可用源');
    container.appendChild(empty);
    return;
  }
  formats.forEach((fmt, index) => {
    const selected = fmt.formatId === state.formatId || (index === 0 && !state.formatId);
    const isHdr = isHdrFormat(fmt);
    const resText = (fmt.width > 0 && fmt.height > 0) ? `${fmt.width}×${fmt.height}` : t(fmt.resolutionLabel || '自动分辨率');
    const tier = tierBadgeText(fmt);
    const drLabel = (String(fmt.dynamicRange || '').trim().toUpperCase() !== 'SDR' && String(fmt.dynamicRange || '').trim())
      ? String(fmt.dynamicRange).trim().toUpperCase()
      : 'HDR';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `format-tile${selected ? ' is-selected' : ''}${isHdr ? ' is-hdr' : ''}`;
    btn.dataset.format = fmt.formatId;
    btn.innerHTML = `
      <div class="format-tile__top">
        <span data-no-i18n class="format-tile__res">${escapeHtml(resText)}</span>
        ${isHdr ? `<span data-no-i18n class="format-tile__hdr-tag">${escapeHtml(drLabel)}</span>` : ''}
        ${tier ? `<span data-no-i18n class="format-tile__tier">${escapeHtml(tier)}</span>` : ''}
        ${selected ? `<span class="format-tile__check">${iconSvg('check')}</span>` : ''}
      </div>
      <div data-no-i18n class="format-tile__meta">${escapeHtml(tileFormatLine(fmt))}</div>
      <div data-no-i18n class="format-tile__size">${escapeHtml(tileSizeLine(fmt))}</div>`;
    container.appendChild(btn);
  });
}

function updateAudioRows() {
  document.querySelectorAll('#audio-options-container .format-row-item').forEach((row) => {
    const selected = row.dataset.audio === state.audioFormat;
    row.classList.toggle('is-selected', selected);
    const radio = row.querySelector('input[type="radio"]');
    if (radio) radio.checked = selected;
  });
}

function updateFooterButtons() {
  const media = currentMedia();
  const metadata = state.metadata;
  if (!media || !metadata) return;

  const multi = metadata.multiMediaList || [];
  const mainBtn = $('btn-submit-download');
  const mainLabel = $('submit-download-label');
  const secBtn = $('btn-sheet-secondary');
  const secLabel = $('sheet-secondary-label');

  const coverSuffix = state.saveCoverWithDownload && (media.thumbnailUrl || metadata.thumbnailUrl) ? t(' + 封面') : '';
  const selectedFmt = (media.availableVideoFormats || []).find((f) => f.formatId === state.formatId);
  const resText = selectedFmt?.resolutionLabel || tierBadgeText(selectedFmt) || t('最高源画质');

  if (state.isCollectionMode && multi.length > 1) {
    const count = state.selectedEpisodes.size;
    mainBtn.disabled = count === 0;
    if (!count) {
      mainLabel.textContent = t('请至少勾选一个视频');
    } else if (state.type === 'AUDIO_ONLY') {
      mainLabel.textContent = t('下载所选音频 (%d 首 · %s)', count, state.audioFormat.toUpperCase()) + coverSuffix;
    } else if (state.type === 'GIF') {
      mainLabel.textContent = t('下载所选动图 (%d 个)', count) + coverSuffix;
    } else {
      mainLabel.textContent = t('下载所选合集 (%d 集 · %s)', count, resText) + coverSuffix;
    }
    secBtn.hidden = false;
    secLabel.textContent = t('全部下载 (%d 集)', multi.length) + coverSuffix;
  } else {
    mainBtn.disabled = false;
    const epTag = (media.title || '').includes(' - P')
      ? `P${media.title.split(' - P')[1].split(' ')[0]} · `
      : '';
    if (state.type === 'VIDEO_WITH_AUDIO') mainLabel.textContent = `${t('开始下载')} (${epTag}${resText}${coverSuffix})`;
    else if (state.type === 'VIDEO_ONLY') mainLabel.textContent = `${t('纯画面')} (${epTag}${resText}${coverSuffix})`;
    else if (state.type === 'AUDIO_ONLY') mainLabel.textContent = `${t('音频')} (${epTag}${state.audioFormat.toUpperCase()}${coverSuffix})`;
    else if (state.type === 'GIF') mainLabel.textContent = `${t('开始下载')} (GIF${coverSuffix})`;
    else mainLabel.textContent = t('开始下载');

    if (multi.length > 1) {
      secBtn.hidden = false;
      secLabel.textContent = t('切换为合集多选下载 (%d 集)', multi.length);
    } else {
      secBtn.hidden = true;
    }
  }
}

/* ---------- 画质策略构造 ---------- */
function qualityPolicy(batch = false) {
  const media = currentMedia();
  const container = $('quality-container')?.value || 'auto';
  const format = (media?.availableVideoFormats || []).find((item) => item.formatId === state.formatId);
  if (!format) {
    return {
      mode: 'BEST_AVAILABLE',
      allowLowerQuality: true,
      container,
    };
  }
  if (batch) {
    return {
      mode: 'MAX_HEIGHT',
      maxHeight: Math.min(format.width || format.height || 0, format.height || 0),
      allowLowerQuality: true,
      container,
    };
  }
  return {
    mode: 'EXACT_FORMAT',
    allowLowerQuality: true,
    container,
  };
}

/* ---------- 提交下载 ---------- */
async function saveCoverDirectly() {
  const metadata = state.metadata;
  const media = currentMedia();
  const coverUrl = media?.thumbnailUrl || metadata?.thumbnailUrl || '';
  if (!coverUrl) { toast(t('未找到封面图地址'), 'warning'); return; }
  try {
    await post('/api/download', {
      tasks: [{
        url: coverUrl,
        title: `${media?.title || metadata?.title || t('视频')} - ${t('封面')}`,
        downloadType: 'COVER',
        thumbnailUrl: coverUrl,
        author: media?.author || metadata?.author || '',
      }],
    });
    toast(t('已加入封面下载任务'));
  } catch (e) {
    toast(e.message || t('保存封面失败'), 'error');
  }
}

async function submitDownload(allEpisodes = false) {
  const metadata = state.metadata;
  const media = currentMedia();
  if (!metadata || !media) return;

  const tasks = [];
  const multi = metadata.multiMediaList || [];

  if (state.isCollectionMode && multi.length > 1) {
    const indices = allEpisodes ? multi.map((_, i) => i) : Array.from(state.selectedEpisodes).sort((a, b) => a - b);
    if (!indices.length) { toast(t('请至少勾选一个视频'), 'warning'); return; }
    const policy = qualityPolicy(true);
    indices.forEach((index) => {
      const episode = multi[index];
      tasks.push({
        url: episode.url,
        title: `${metadata.title} - ${episode.title}`,
        downloadType: state.type,
        selectedResolution: 'best',
        mediaId: episode.mediaId || '',
        qualitySelection: policy,
        audioFormat: state.audioFormat,
        thumbnailUrl: episode.thumbnailUrl || metadata.thumbnailUrl,
        author: metadata.author,
        collectionId: metadata.url,
        collectionTitle: metadata.title,
        episodeIndex: index + 1,
        episodeTotal: multi.length,
      });
    });
  } else {
    const policy = qualityPolicy(false);
    tasks.push({
      url: media.url || metadata.url,
      title: media.title || metadata.title,
      downloadType: state.type,
      selectedResolution: policy.mode === 'EXACT_FORMAT' ? state.formatId : 'best',
      selectedVideoFormatId: policy.mode === 'EXACT_FORMAT' ? state.formatId : '',
      mediaId: media.mediaId || '',
      qualitySelection: policy,
      audioFormat: state.audioFormat,
      thumbnailUrl: media.thumbnailUrl || metadata.thumbnailUrl,
      author: media.author || metadata.author,
    });
  }

  if (state.saveCoverWithDownload) {
    const coverUrl = media.thumbnailUrl || metadata.thumbnailUrl || '';
    if (coverUrl) {
      tasks.push({
        url: coverUrl,
        title: `${media.title || metadata.title} - ${t('封面')}`,
        downloadType: 'COVER',
        thumbnailUrl: coverUrl,
        author: media.author || metadata.author,
      });
    }
  }

  const btn = $('btn-submit-download');
  btn.disabled = true;
  try {
    await post('/api/download', { tasks });
    closeDialog('format-sheet-overlay');
    toast(t('已加入 %d 个下载任务', tasks.length));
    document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'tasks' } }));
  } catch (error) {
    toast(error.message || t('提交下载失败'), 'error');
  } finally {
    btn.disabled = false;
  }
}

/* ---------- 浏览器辅助 ---------- */
async function openBrowserAssist() {
  const url = $('input-url').value.trim();
  try {
    await post('/api/browser-assist/open', { url });
    toast('请在新窗口完成验证并播放目标内容，再返回读取媒体');
  } catch (e) {
    toast(e.message || '无法打开浏览器辅助', 'error');
  }
}

async function capturePlayedMedia() {
  try {
    const data = await get('/api/browser-assist/candidates');
    if (!Array.isArray(data) || !data.length) {
      toast('尚未发现可保存的已播放源；blob 或加密媒体不适用', 'warning');
      return;
    }
    const source = data[0];
    let host = source.url;
    try { host = new URL(source.url).hostname; } catch (e) { /* keep */ }
    const ok = await confirmDialog({
      title: t('保存已播放媒体'),
      message: `${source.title || t('未命名媒体')}\n${source.width}×${source.height} · ${host}`,
      confirmText: t('开始下载'),
      danger: false,
    });
    if (!ok) return;
    await post('/api/download', {
      tasks: [{
        url: source.url,
        title: source.title || t('已播放媒体'),
        downloadType: 'VIDEO_WITH_AUDIO',
        selectedResolution: 'best',
        qualitySelection: { mode: 'BEST_AVAILABLE' },
      }],
    });
    toast('已加入下载队列');
    document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'tasks' } }));
  } catch (e) {
    toast(e.message || '读取已播放媒体失败', 'error');
  }
}

async function captureBrowserImages() {
  try {
    const data = await post('/api/browser-assist/images');
    hideError();
    showPost(data);
  } catch (e) {
    toast(e.message || '提取图文失败', 'error');
  }
}

/* ---------- 首页正在下载 / 最近任务（最多展示 2 条，对齐 HomeActiveTasksSection） ---------- */
function renderRecent(tasks) {
  const all = Array.isArray(tasks) ? tasks : [];
  state.allRecentSource = all;
  const active = all.filter((item) => ['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(item.status));
  const shown = active.length ? active.slice(0, 2) : all.slice(0, 2);
  state.recent = shown;

  const section = $('recent-section');
  const list = $('recent-list');
  const titleEl = $('recent-section-title');
  if (!section || !list) return;

  if (window.__omniShowRecentTasks === false || !shown.length) {
    section.hidden = true;
    list.replaceChildren();
    return;
  }

  section.hidden = false;
  if (titleEl) {
    titleEl.textContent = active.length ? t('正在下载的任务 (%d)', active.length) : t('最近任务');
  }

  list.replaceChildren(...shown.map((task) => {
    const info = statusInfo(task.status);
    const progress = Math.min(Math.max(Number(task.progress) || 0, 0), 100);
    const card = document.createElement('div');
    card.className = 'task-card omni-card--compact omni-card--clickable';
    card.innerHTML = `
      <div class="task-card__head">
        <div class="task-cover">
          ${iconSvg('videocam')}
          ${task.thumbnailUrl ? `<img src="${escapeHtml(task.thumbnailUrl)}" alt="" loading="lazy">` : ''}
        </div>
        <div class="task-card__info">
          <div data-no-i18n class="task-card__title">${escapeHtml(task.title || t('下载任务'))}</div>
          <div class="task-card__badges">
            <span class="omni-pill omni-pill--primary">${escapeHtml(t(typeLabel(task.downloadType)))}</span>
            <span class="omni-pill ${info.pill}">${escapeHtml(t(info.label))}</span>
          </div>
        </div>
      </div>
      ${['DOWNLOADING', 'PROCESSING'].includes(task.status) ? `
      <div class="task-progress-block">
        <div class="omni-progress"><div class="omni-progress__bar ${info.progress}" style="width:${progress}%"></div></div>
        <div class="task-progress-meta">
          <span>${task.status === 'PROCESSING' ? escapeHtml(t('正在合并')) : `${progress.toFixed(0)}%${task.speedText ? ' · ' + escapeHtml(task.speedText) : ''}`}</span>
          <span>${task.etaText ? escapeHtml(t('剩余 %s', t(task.etaText))) : ''}</span>
        </div>
      </div>` : ''}`;

    const img = card.querySelector('.task-cover img');
    if (img) img.addEventListener('error', () => img.remove());

    card.addEventListener('click', () => {
      if (task.status === 'COMPLETED') {
        import('./media.js').then((mod) => mod.openTaskMedia(task, [task]));
      } else {
        document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'tasks' } }));
      }
    });
    return card;
  }));
}
