/* S04 设置页：两级分层导航 + OmniOptionMenu 主题与语言选择器（对齐 SettingsScreen.kt & SettingsNavigation.kt） */
import { get, post, del, api } from './api.js';
import { toast, applyTheme, applyAmoled, storedTheme, storedAmoled, confirmDialog, openOptionMenu } from './ui.js';
import { currentLanguage, setLanguage, t } from './i18n.js';

const $ = (id) => document.getElementById(id);

const SUBPAGES = {
  root: '偏好设置',
  downloads: '下载与存储',
  network: '网络代理',
  relay: '中转站',
  accounts: '账号',
  engine: '下载引擎',
  about: '关于',
};

const THEME_OPTIONS = [
  { value: 'auto', label: '跟随系统' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
];

const LANG_OPTIONS = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'zh-TW', label: '繁體中文' },
  { value: 'en', label: 'English' },
  { value: 'ja', label: '日本語' },
];

const state = {
  currentPage: 'root',
  settings: null,
  sessions: [],
  loginPlatform: '',
  loginPolling: false,
};

export function initSettings() {
  $('settings-category-list')?.addEventListener('click', (event) => {
    const row = event.target.closest('[data-cat]');
    if (row) showCategory(row.dataset.cat);
  });

  $('btn-settings-back')?.addEventListener('click', () => showCategory('root'));

  // 右上角外观主题 OmniOptionMenu
  $('theme-dropdown-btn')?.addEventListener('click', () => {
    const btn = $('theme-dropdown-btn');
    const cur = state.settings?.theme || storedTheme();
    openOptionMenu(btn, THEME_OPTIONS, cur, async (val) => {
      applyTheme(val, true);
      updateThemeLabel(val);
      try {
        await post('/api/settings', { theme: val });
        if (state.settings) state.settings.theme = val;
      } catch (e) { /* 保留本地存储 */ }
    });
  });

  // 首行语言 OmniOptionMenu
  $('lang-dropdown-btn')?.addEventListener('click', () => {
    const btn = $('lang-dropdown-btn');
    openOptionMenu(btn, LANG_OPTIONS, currentLanguage(), (val) => {
      setLanguage(val);
      updateLangLabel(val);
      toast('界面语言已切换', 'info', 2000);
    });
  });

  // 下载与存储即时/显式操作
  $('btn-open-download-dir')?.addEventListener('click', () => {
    post('/api/open-folder').catch((e) => toast(e.message, 'error'));
  });
  $('btn-save-downloads')?.addEventListener('click', async () => {
    const downloadDir = $('setting-download-dir').value.trim();
    try {
      const res = await post('/api/settings', { downloadDir });
      if (res?.config) state.settings = res.config;
      $('setting-download-dir-preview').textContent = downloadDir || '默认目录';
      toast('保存路径已更新');
    } catch (e) {
      toast(e.message || '保存失败', 'error');
    }
  });
  $('setting-concurrent')?.addEventListener('change', async () => {
    const val = parseInt($('setting-concurrent').value, 10);
    try {
      await post('/api/settings', { maxConcurrentTasks: val });
      toast('并发数已更新', 'info', 1800);
    } catch (e) {
      toast(e.message, 'error');
    }
  });
  $('setting-auto-browser-download')?.addEventListener('change', (e) => {
    localStorage.setItem('omni_auto_browser_download', e.target.checked ? 'true' : 'false');
    toast(e.target.checked ? '已开启自动调起浏览器下载' : '已关闭自动调起浏览器下载', 'info', 1800);
  });
  $('setting-autostart')?.addEventListener('change', async (e) => {
    try {
      await post('/api/settings', { autoStart: e.target.checked });
      toast(e.target.checked ? '已开启开机自启动' : '已关闭开机自启动', 'info', 2000);
    } catch (err) {
      toast(err.message || '设置自启动失败', 'error');
    }
  });
  $('setting-amoled')?.addEventListener('change', (e) => {
    applyAmoled(e.target.checked);
    toast(e.target.checked ? '已开启纯黑背景 (AMOLED)' : '已关闭纯黑背景', 'info', 1800);
  });

  // 网络代理
  $('btn-save-proxy')?.addEventListener('click', async () => {
    const proxyUrl = $('setting-proxy').value.trim();
    try {
      await post('/api/settings', { proxyUrl });
      $('btn-clear-proxy').hidden = !proxyUrl;
      toast(proxyUrl ? '代理已更新' : '已切换为直连网络');
    } catch (e) {
      toast(e.message || '保存代理失败', 'error');
    }
  });
  $('btn-clear-proxy')?.addEventListener('click', async () => {
    $('setting-proxy').value = '';
    try {
      await post('/api/settings', { proxyUrl: '' });
      $('btn-clear-proxy').hidden = true;
      toast('代理已清空');
    } catch (e) {
      toast(e.message || '清空代理失败', 'error');
    }
  });

  // 平台会话 & B站凭证
  $('session-platform')?.addEventListener('change', () => {
    $('session-platform-label').textContent = $('session-platform').value;
    loadSessionPolicy();
  });
  $('session-disable-cookie')?.addEventListener('change', toggleSession);
  $('session-file')?.addEventListener('change', importSessionFromInput);
  $('btn-clear-session')?.addEventListener('click', clearSession);
  $('btn-browser-login')?.addEventListener('click', async () => {
    const platform = $('session-platform').value;
    try {
      await post(`/api/sessions/${encodeURIComponent(platform)}/login`);
      state.loginPlatform = platform;
      await pollBrowserLogin();
    } catch (e) { toast(e.message, 'error'); }
  });
  $('btn-save-browser-session')?.addEventListener('click', async () => {
    const button = $('btn-save-browser-session');
    button.disabled = true;
    try {
      const result = await post(`/api/sessions/${encodeURIComponent($('session-platform').value)}/capture`);
      toast(`已保存 ${result.cookieCount} 项 Cookie 并关闭登录窗口；请重新解析验证账号`);
      await loadSessionPolicy();
      await pollBrowserLogin();
    } catch (e) { toast(e.message, 'error'); }
    finally { button.disabled = false; }
  });
  setInterval(() => { if (state.currentPage === 'accounts' && state.loginPlatform) pollBrowserLogin(); }, 2000);

  $('btn-save-bili')?.addEventListener('click', async () => {
    const cookie = $('setting-bili-cookie').value.trim();
    try {
      await post('/api/settings', { bilibiliCookie: cookie });
      $('bili-cookie-status').hidden = !cookie;
      $('btn-clear-bili').hidden = !cookie;
      $('setting-bili-cookie').value = '';
      toast(cookie ? 'B站凭据已保存，请重新解析验证账号' : 'B站凭证已清空');
    } catch (e) {
      toast(e.message || '保存失败', 'error');
    }
  });
  $('btn-clear-bili')?.addEventListener('click', async () => {
    $('setting-bili-cookie').value = '';
    try {
      await post('/api/settings', { bilibiliCookie: '' });
      $('bili-cookie-status').hidden = true;
      $('btn-clear-bili').hidden = true;
      toast('B站凭证已清空');
    } catch (e) {
      toast(e.message || '清空失败', 'error');
    }
  });

  // 下载引擎
  $('btn-check-engine')?.addEventListener('click', checkEngine);

  updateThemeLabel(storedTheme());
  updateLangLabel(currentLanguage());
  loadSettings();
  loadSessionPolicy();
  document.addEventListener('omni:language', () => {
    updateThemeLabel(state.settings?.theme || storedTheme());
    $('settings-toolbar-title').textContent = t(SUBPAGES[state.currentPage]);
  });
}

export function showCategory(cat = 'root', updateHash = true) {
  const page = SUBPAGES[cat] ? cat : 'root';
  state.currentPage = page;

  const isRoot = page === 'root';
  $('btn-settings-back').hidden = isRoot;
  $('theme-dropdown-wrap').hidden = !isRoot;
  $('settings-toolbar-title').textContent = t(SUBPAGES[page]);
  $('settings-root-view').hidden = !isRoot;

  document.querySelectorAll('#page-settings .settings-subpage').forEach((panel) => {
    panel.hidden = panel.dataset.panel !== page;
  });

  if (page === 'engine') checkEngine();
  if (page === 'accounts') loadSessionPolicy();

  if (updateHash) {
    const target = isRoot ? '#settings' : `#settings/${page}`;
    if (location.hash !== target) history.replaceState(null, '', target);
  }
  const ws = $('workspace');
  if (ws) ws.scrollTop = 0;
}

function updateThemeLabel(val) {
  const found = THEME_OPTIONS.find((o) => o.value === val) || THEME_OPTIONS[0];
  const el = $('theme-dropdown-label');
  if (el) el.textContent = t(found.label);
}

function updateLangLabel(val) {
  const found = LANG_OPTIONS.find((o) => o.value === val) || LANG_OPTIONS[0];
  const el = $('lang-dropdown-label');
  if (el) el.textContent = found.label;
}

/* ---------- 数据加载 ---------- */
async function loadSettings() {
  try {
    const data = await get('/api/settings');
    state.settings = data;
    const theme = data.theme || storedTheme() || 'auto';
    applyTheme(theme, true);
    updateThemeLabel(theme);

    const dir = data.downloadDir || '';
    $('setting-download-dir').value = dir;
    $('setting-download-dir-preview').textContent = dir || '默认目录';
    $('setting-concurrent').value = String(data.maxConcurrentTasks || 3);
    $('setting-auto-browser-download').checked = localStorage.getItem('omni_auto_browser_download') !== 'false';
    $('setting-autostart').checked = Boolean(data.autoStart);
    $('setting-amoled').checked = storedAmoled();

    const proxy = data.proxyUrl || '';
    $('setting-proxy').value = proxy;
    $('btn-clear-proxy').hidden = !proxy;

    $('setting-bili-cookie').value = data.bilibiliCookie || '';
    const hasBili = Boolean(data.hasBilibiliSession || data.bilibiliCookie);
    $('bili-cookie-status').hidden = !hasBili;
    $('btn-clear-bili').hidden = !hasBili;
  } catch (e) {
    toast('无法读取设置，本地服务可能未启动', 'error');
  }
}

/* ---------- 平台会话 ---------- */
async function loadSessions() {
  try {
    const data = await get('/api/sessions');
    state.sessions = Array.isArray(data) ? data : [];
  } catch (e) {
    state.sessions = [];
  }
}

async function loadSessionPolicy() {
  const platform = $('session-platform').value;
  $('session-platform-label').textContent = platform;
  await loadSessions();
  const info = state.sessions.find((row) => row.platform === platform);
  const enabled = !(info && info.enabled === false);
  $('session-disable-cookie').checked = !enabled;

  const pill = $('session-status-pill');
  const st = info?.state || 'MISSING';
  if (!enabled) {
    pill.textContent = '已停用（保留原凭证）';
    pill.className = 'omni-pill';
  } else if (st === 'IMPORTED') {
    pill.textContent = '已保存（未验证账号）';
    pill.className = 'omni-pill omni-pill--success';
  } else if (['NEEDS_AUTH', 'EXPIRED', 'CORRUPT'].includes(st)) {
    pill.textContent = '需重新验证';
    pill.className = 'omni-pill omni-pill--warning';
  } else {
    pill.textContent = '未导入';
    pill.className = 'omni-pill';
  }

  $('session-policy-status').textContent = enabled
    ? '关闭停用开关时，新解析与新下载可使用该平台已保存的 Cookie'
    : '已停用：新解析与新下载使用匿名请求，已保存会话仍保留';
}

async function toggleSession() {
  const platform = $('session-platform').value;
  const disabled = $('session-disable-cookie').checked;
  const enabled = !disabled;
  try {
    await api(`/api/sessions/${encodeURIComponent(platform)}`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    });
    toast(enabled ? 'Cookie 已启用，请重新解析' : 'Cookie 已停用，会话保留', 'info');
  } catch (e) {
    toast(e.message || '保存开关失败', 'error');
  }
  await loadSessionPolicy();
}

async function pollBrowserLogin() {
  if (state.loginPolling || !state.loginPlatform) return;
  state.loginPolling = true;
  const platform = $('session-platform').value;
  try {
    const result = await get(`/api/sessions/${encodeURIComponent(platform)}/login`);
    if ($('session-platform').value !== platform) return;
    const messages = {
      OPENING: '正在打开独立 Edge 登录窗口…',
      WAITING_LOGIN: '请在 Edge 中完成登录；Google 登录后返回目标平台，再点击「保存会话并关闭」。',
      CAPTURED: `已保存目标平台 Cookie（${result.cookieCount} 项）；未验证账号。请关闭登录窗口后重新解析。`,
      CLOSED: '登录窗口已关闭。可以重新打开继续登录，或导入 Cookie 文件。',
      ERROR: `登录捕获未完成（${result.errorCode || 'CAPTURE_FAILED'}），原有凭据已保留。请关闭旧登录窗口后重试。`,
    };
    $('browser-login-status').textContent = messages[result.phase] || messages.CLOSED;
    if (result.skippedPartitionedCookies) $('browser-login-status').textContent += ` 已跳过 ${result.skippedPartitionedCookies} 项分区 Cookie；部分登录能力可能仅在浏览器中可用。`;
    await loadSessionPolicy();
  } catch (e) { $('browser-login-status').textContent = e.message; }
  finally { state.loginPolling = false; }
}

async function importSessionFromInput(event) {
  const input = event.target;
  const file = input.files && input.files[0];
  if (!file) return;
  if (file.size > 1024 * 1024) {
    toast('Cookies 文件不得超过 1MB', 'warning');
    input.value = '';
    return;
  }
  try {
    const text = await file.text();
    await post('/api/sessions', { platform: $('session-platform').value, cookies: text });
    toast('会话已导入，请重新解析原链接');
    await loadSessionPolicy();
  } catch (e) {
    toast(e.message || 'Cookies 文件无效或导入失败', 'error');
  } finally {
    input.value = '';
  }
}

async function clearSession() {
  const platform = $('session-platform').value;
  const ok = await confirmDialog({
    title: '清除会话',
    message: `确定清除「${platform}」的 Cookie 和独立登录浏览器状态吗？操作后需要重新登录。`,
    confirmText: '清除',
    danger: true,
  });
  if (!ok) return;
  try {
    await del(`/api/sessions/${encodeURIComponent(platform)}`);
    toast('会话已清除');
    await loadSessionPolicy();
  } catch (e) {
    toast(e.message || '清除失败，请在本机操作', 'error');
  }
}

/* ---------- 引擎诊断 ---------- */
async function checkEngine() {
  const status = $('engine-status');
  const pill = $('engine-ready-pill');
  if (status) status.textContent = '正在探测本机引擎环境...';
  try {
    const data = await get('/api/engine/diagnostics');
    if (status) {
      status.textContent = `Python ${data.python} · yt-dlp ${data.ytDlp} · EJS ${data.ejs} · FFmpeg ${data.ffmpegAvailable ? '就绪' : '未就绪'} · JS 运行时 ${data.denoAvailable ? '可用' : '缺失'}`;
    }
    if (pill) {
      const ok = Boolean(data.ytDlp);
      pill.textContent = ok ? '正常运行' : '未就绪';
      pill.className = `omni-pill ${ok ? 'omni-pill--success' : 'omni-pill--error'}`;
    }
  } catch (e) {
    if (status) status.textContent = e.message || '无法读取';
    if (pill) {
      pill.textContent = '未就绪';
      pill.className = 'omni-pill omni-pill--error';
    }
  }
}
