/* S01 首页：品牌区、平台标签、输入卡片、FormatSelectorSheet 抽屉、错误弹窗、最近任务（对齐 HomeScreen.kt + FormatSelectorSheet.kt） */
import { post, get } from './api.js';
import { toast, escapeHtml, confirmDialog, openDialog, closeDialog, showErrorDetailDialog } from './ui.js';
import { statusInfo, typeLabel } from './labels.js';
import { iconSvg } from './icons.js';
import { showPost, clearPost } from './images.js';
import { t } from './i18n.js';

const state = {
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
};

const $ = (id) => document.getElementById(id);

export function initHome() {
  const input = $('input-url');
  const analyzeBtn = $('btn-analyze');

  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      if (!state.controller && input.value.trim()) analyze();
    }
  });
  input.addEventListener('input', updateInputUi);

  analyzeBtn.addEventListener('click', () => {
    if (state.controller) cancelAnalyze();
    else analyze();
  });

  // 行内小图标：为空时粘贴（不自动解析），非空时清空
  $('btn-input-inline').addEventListener('click', () => {
    if (input.value.trim()) {
      clearAll();
    } else {
      pasteClipboard(false);
    }
  });

  // 左侧大按钮：粘贴剪贴板并立即解析（对齐 Android checkClipboardAndPaste(autoDownload = true)）
  $('btn-paste').addEventListener('click', () => pasteClipboard(true));

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

  $('format-options-container')?.addEventListener('click', (event) => {
    const row = event.target.closest('[data-format]');
    if (!row) return;
    state.formatId = row.dataset.format;
    $('quality-policy').value = 'EXACT_FORMAT';
    renderFormats(currentMedia()?.availableVideoFormats || []);
    updateFooterButtons();
  });

  $('quality-policy')?.addEventListener('change', () => {
    renderFormats(currentMedia()?.availableVideoFormats || []);
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
  document.addEventListener('omni:language', () => {
    if (state.metadata) renderSheet();
    if (state.recent.length) renderRecent(state.recent);
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
    inlineBtn.title = hasValue ? '清除' : '粘贴';
    inlineBtn.setAttribute('aria-label', hasValue ? '清除' : '粘贴');
  }
  if (analyzeBtn && !state.controller) {
    analyzeBtn.disabled = !hasValue;
  }
}

async function pasteClipboard(autoAnalyze = false) {
  try {
    const text = await navigator.clipboard.readText();
    if (!text || !text.trim()) {
      toast('剪贴板为空', 'warning');
      return;
    }
    $('input-url').value = text.trim();
    updateInputUi();
    if (autoAnalyze) {
      toast('已粘贴，开始解析...', 'info', 2000);
      analyze();
    } else {
      $('input-url').focus();
      toast('已粘贴剪贴板内容', 'info', 1800);
    }
  } catch (e) {
    toast('无法读取剪贴板，请使用 Ctrl+V 手动粘贴', 'warning');
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

function clearAll() {
  $('input-url').value = '';
  updateInputUi();
  hideError();
  clearPost();
  state.metadata = null;
  $('parsed-resume-bar').hidden = true;
  $('input-url').focus();
}

/* ---------- 解析 ---------- */
async function analyze() {
  const url = $('input-url').value.trim();
  if (!url) {
    toast('请输入视频链接或分享文本', 'warning');
    $('input-url').focus();
    return;
  }
  hideError();
  setAnalyzing(true);
  state.controller = new AbortController();
  try {
    const parsed = await post('/api/media/analyze', { url }, { signal: state.controller.signal });
    if (parsed.kind === 'images') {
      $('parsed-resume-bar').hidden = true;
      closeDialog('format-sheet-overlay');
      showPost(parsed.data);
      return;
    }
    clearPost();
    const data = parsed.data;
    state.metadata = data;
    state.selectedMultiIndex = 0;
    state.isCollectionMode = false;
    state.saveCoverWithDownload = false;
    $('toggle-save-cover').checked = false;

    const multi = data.multiMediaList || [];
    state.selectedEpisodes = new Set(multi.map((_, idx) => idx));
    const first = currentMedia();
    state.type = first && first.isGif ? 'GIF' : 'VIDEO_WITH_AUDIO';
    state.formatId = (first && first.availableVideoFormats && first.availableVideoFormats[0]?.formatId) || 'best';
    $('quality-policy').value = 'BEST_AVAILABLE';

    renderResumeBar(data);
    renderSheet();
    openFormatSheet();
  } catch (error) {
    if (error && error.name === 'AbortError') {
      toast('已取消解析', 'info');
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
    label.textContent = '解析中...';
  } else {
    if (iconWrap) iconWrap.innerHTML = iconSvg('bolt');
    label.textContent = '立即解析';
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
    return { title: '解析未能成功', hint: raw || '请确认链接来自受支持的平台，或尝试复制完整的分享文本。', action: 'detail' };
  }
  if (/login|登录|cookie|sessdata|auth|验证|captcha|auth_required|challenge/.test(text)) {
    return { title: '需要登录或 Cookie 已失效', hint: raw || '请在「设置 → 账号」中导入会话或 Cookie 后重试。', action: 'accounts' };
  }
  if (/network|timeout|timed out|connection|网络|连接|dns/.test(text)) {
    return { title: '网络连接失败', hint: raw || '请检查网络或代理设置后重试。', action: 'network' };
  }
  return { title: '解析未能成功', hint: raw || '请稍后重试。', action: 'detail' };
}

function showAnalyzeError(error) {
  const info = describeError(error);
  $('parse-error-title').textContent = info.title;
  $('parse-error-hint').textContent = info.hint;
  const actions = $('parse-error-actions');
  actions.replaceChildren();

  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'btn btn-tonal btn-sm';
  retry.textContent = '重试';
  retry.addEventListener('click', analyze);
  actions.appendChild(retry);

  const detail = document.createElement('button');
  detail.type = 'button';
  detail.className = 'btn btn-text btn-sm';
  detail.textContent = '查看详情';
  detail.addEventListener('click', () => {
    showErrorDetailDialog({
      title: info.title,
      message: state.lastErrorText || info.hint,
      showSettingsAction: info.action === 'accounts' || info.action === 'network',
      onGoSettings: () => {
        const cat = info.action === 'accounts' ? 'accounts' : 'network';
        document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'settings', cat } }));
      },
    });
  });
  actions.appendChild(detail);

  if (info.action === 'accounts' || info.action === 'network') {
    const goBtn = document.createElement('button');
    goBtn.type = 'button';
    goBtn.className = 'btn btn-text btn-sm';
    goBtn.textContent = info.action === 'accounts' ? '前往账号设置' : '设置代理';
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
  $('parsed-resume-title').textContent = data.title || '已解析媒体';
  $('parsed-resume-sub').textContent = `${data.siteName || '网络视频'}${data.durationText ? ' · ' + data.durationText : ''} · 点击选择画质并开始下载`;
  bar.hidden = false;
}

function openFormatSheet() {
  openDialog('format-sheet-overlay');
}

function renderSheet() {
  const metadata = state.metadata;
  const media = currentMedia();
  if (!metadata || !media) return;

  $('sheet-media-title').textContent = media.title || metadata.title || '未命名媒体';
  $('meta-site').textContent = media.siteName || metadata.siteName || '网络视频';
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
    $('btn-mode-collection-label').textContent = `下载合集 (${multi.length})`;
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
    prompt.textContent = metadata.siteName === '哔哩哔哩'
      ? '点击切换当前预览与下载的分P/分集:'
      : '点击切换当前预览与独立下载的视频:';
  }
  const row = $('single-parts-row');
  row.replaceChildren();
  list.forEach((item, index) => {
    const selected = index === state.selectedMultiIndex;
    const chipName = (item.title || '').includes(' - P') ? `P${index + 1}` : `视频 ${index + 1}`;
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
      <span class="episode-check-item__title">${escapeHtml(ep.title || `第 ${index + 1} 集`)}</span>
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
  document.querySelectorAll('#download-type-container .mode-tab-chip').forEach((chip) => {
    chip.classList.toggle('is-selected', chip.dataset.type === type);
  });
  const resSec = $('resolution-section');
  const audioSec = $('audio-format-section');
  const gifSec = $('gif-format-section');
  const hint = $('resolution-hint');

  resSec.hidden = !(type === 'VIDEO_WITH_AUDIO' || type === 'VIDEO_ONLY');
  audioSec.hidden = type !== 'AUDIO_ONLY';
  gifSec.hidden = type !== 'GIF';
  if (hint) {
    hint.textContent = type === 'VIDEO_ONLY'
      ? '选择纯视频分辨率 (完全去除声音轨，静音素材)'
      : '选择视频分辨率 (自动合成最佳声音)';
  }
  updateFooterButtons();
}

function formatSummary(fmt) {
  const dims = (fmt.width > 0 && fmt.height > 0) ? `${fmt.width}×${fmt.height}` : (fmt.resolutionLabel || '待定分辨率');
  const ext = (fmt.ext || '').toUpperCase() || 'MP4';
  const size = fmt.approximateSize || fmt.note || '自动体积';
  return `${dims} · ${ext} · ${size}`;
}

function renderFormats(formats) {
  const container = $('format-options-container');
  container.replaceChildren();
  const isExact = $('quality-policy').value === 'EXACT_FORMAT';
  if (!formats.length) {
    const empty = document.createElement('div');
    empty.className = 'caption';
    empty.textContent = '将按画质策略自动选择最佳可用源';
    container.appendChild(empty);
    return;
  }
  formats.forEach((fmt, index) => {
    const selected = isExact
      ? fmt.formatId === state.formatId
      : (index === 0 && !state.formatId) || fmt.formatId === state.formatId;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `format-row-item${selected ? ' is-selected' : ''}`;
    btn.dataset.format = fmt.formatId;
    btn.innerHTML = `<input type="radio" name="video-fmt" ${selected ? 'checked' : ''} tabindex="-1"><span style="flex:1">${escapeHtml(formatSummary(fmt))}</span>`;
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

  const coverSuffix = state.saveCoverWithDownload && (media.thumbnailUrl || metadata.thumbnailUrl) ? ' + 封面' : '';
  const policy = $('quality-policy').value;
  const selectedFmt = (media.availableVideoFormats || []).find((f) => f.formatId === state.formatId);
  const resText = policy === 'EXACT_FORMAT'
    ? (selectedFmt?.resolutionLabel || '精确画质')
    : $('quality-policy').selectedOptions[0]?.textContent?.split('（')[0] || '最高画质';

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
    else if (state.type === 'AUDIO_ONLY') mainLabel.textContent = `${t('提取音频')} (${epTag}${state.audioFormat.toUpperCase()}${coverSuffix})`;
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
  const choice = $('quality-policy').value;
  const intent = {
    mode: choice,
    allowLowerQuality: $('quality-allow-lower').checked,
    container: $('quality-container').value,
  };
  if (choice.startsWith('MAX_') || choice.startsWith('TARGET_')) {
    intent.maxHeight = Number(choice.split('_')[1]);
    intent.mode = choice.startsWith('MAX_') ? 'MAX_HEIGHT' : 'TARGET_TIER';
  }
  if (choice === 'HDR' || choice === 'SDR') {
    intent.mode = 'BEST_AVAILABLE';
    intent.dynamicRangePreference = choice;
  }
  if (choice === 'FPS60') {
    intent.mode = 'BEST_AVAILABLE';
    intent.targetFps = 60;
  }
  if (choice === 'COMPATIBLE') {
    intent.mode = 'BEST_AVAILABLE';
    intent.codecPreference = 'avc';
  }
  if (batch && choice === 'EXACT_FORMAT') {
    const media = currentMedia();
    const format = (media?.availableVideoFormats || []).find((item) => item.formatId === state.formatId);
    intent.mode = 'MAX_HEIGHT';
    intent.maxHeight = format ? Math.min(format.width || format.height || 0, format.height || 0) : 0;
  }
  return intent;
}

/* ---------- 提交下载 ---------- */
async function saveCoverDirectly() {
  const metadata = state.metadata;
  const media = currentMedia();
  const coverUrl = media?.thumbnailUrl || metadata?.thumbnailUrl || '';
  if (!coverUrl) { toast('未找到封面图地址', 'warning'); return; }
  try {
    await post('/api/download', {
      tasks: [{
        url: coverUrl,
        title: `${media?.title || metadata?.title || '视频'} - 封面`,
        downloadType: 'COVER',
        thumbnailUrl: coverUrl,
        author: media?.author || metadata?.author || '',
      }],
    });
    toast('已加入封面下载任务');
  } catch (e) {
    toast(e.message || '保存封面失败', 'error');
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
    if (!indices.length) { toast('请至少勾选一个视频', 'warning'); return; }
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
        title: `${media.title || metadata.title} - 封面`,
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
    toast(`已加入 ${tasks.length} 个下载任务`);
    document.dispatchEvent(new CustomEvent('omni:navigate', { detail: { page: 'tasks' } }));
  } catch (error) {
    toast(error.message || '提交下载失败', 'error');
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
      title: '保存已播放媒体',
      message: `${source.title || '未命名媒体'}\n${source.width}×${source.height} · ${host}`,
      confirmText: '开始下载',
      danger: false,
    });
    if (!ok) return;
    await post('/api/download', {
      tasks: [{
        url: source.url,
        title: source.title || '已播放媒体',
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
  const active = all.filter((item) => ['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(item.status));
  const shown = active.length ? active.slice(0, 2) : all.slice(0, 2);
  state.recent = shown;

  const section = $('recent-section');
  const list = $('recent-list');
  const titleEl = $('recent-section-title');
  if (!section || !list) return;

  if (!shown.length) {
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
          <div class="task-card__title">${escapeHtml(task.title || '下载任务')}</div>
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
          <span>${task.etaText ? escapeHtml(t('剩余 %s', task.etaText)) : ''}</span>
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
