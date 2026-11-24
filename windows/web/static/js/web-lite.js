import { hydrateIcons, iconSvg } from './icons.js';
import { initI18n, currentLanguage, setLanguage, t } from './i18n.js';
import { get } from './api.js';
import { initHome, focusInput, pasteFromShortcut, cancelAnalyzing, fillAndAnalyze } from './home.js';
import { initImages } from './images.js';
import { initBatch } from './batch.js';
import { initTasks, refreshTasks } from './tasks.js';
import { applyAccentColor, applyTheme, closeDialog, closeTopDialog, enhanceSelects, openOptionMenu, storedAccentColor, toast } from './ui.js';

const pages = ['home', 'batch', 'tasks'];
const LANG_OPTIONS = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'zh-TW', label: '繁體中文' },
  { value: 'en', label: 'English' },
  { value: 'ja', label: '日本語' },
];
let current = 'home';

function navigate(page, options = {}) {
  if (!pages.includes(page)) page = 'home';
  current = page;
  for (const key of pages) {
    const el = document.getElementById(`page-${key}`);
    if (!el) continue;
    const show = key === page;
    el.hidden = !show;
    if (show) {
      el.classList.remove('is-entering');
      void el.offsetWidth;
      el.classList.add('is-entering');
    }
  }
  document.querySelectorAll('.web-rail [data-nav]').forEach(button => {
    const active = button.dataset.nav === page;
    button.classList.toggle('is-active', active);
    if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
  });
  document.querySelectorAll('.download-modes [data-nav]').forEach(button => {
    const active = button.dataset.nav === page;
    button.classList.toggle('is-active', active);
    button.setAttribute('aria-pressed', String(active));
  });
  if (options.hash !== false) history.replaceState(null, '', page === 'home' ? location.pathname : `#${page}`);
  document.getElementById('workspace').scrollTop = 0;
  if (page === 'tasks') refreshTasks();
  if (page === 'home' && options.analyzeUrl) fillAndAnalyze(options.analyzeUrl);
  else if (page === 'home' && options.focusInput) focusInput();
}

function updateLangButton() {
  const cur = currentLanguage();
  const labelEl = document.getElementById('web-lang-label');
  const found = LANG_OPTIONS.find(item => item.value === cur);
  if (labelEl) labelEl.textContent = found ? found.label : '简体中文';
}

function updateThemeButton() {
  const dark = document.documentElement.dataset.theme === 'dark';
  const button = document.getElementById('web-theme-toggle');
  if (!button) return;
  const icon = button.querySelector('.m-icon');
  if (icon) icon.outerHTML = iconSvg(dark ? 'light_mode' : 'dark_mode');
  const label = document.getElementById('web-theme-label');
  if (label) label.textContent = t(dark ? '浅色外观' : '深色外观');
  button.setAttribute('aria-label', t(dark ? '切换到浅色' : '切换到深色'));
  button.setAttribute('aria-pressed', String(dark));
  button.title = button.getAttribute('aria-label');
}

async function boot() {
  hydrateIcons(document);

  document.addEventListener('click', event => {
    const nav = event.target.closest('[data-nav]');
    if (nav) navigate(nav.dataset.nav);
    const closer = event.target.closest('[data-close]');
    if (closer) closeDialog(closer.dataset.close);
    if (event.target.classList?.contains('sheet-overlay') || event.target.classList?.contains('dialog-overlay')) {
      if (event.target.id === 'confirm-modal') document.getElementById('confirm-cancel').click();
      else closeDialog(event.target);
    }
  });
  document.addEventListener('omni:navigate', event => navigate(event.detail?.page, event.detail || {}));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !closeTopDialog()) cancelAnalyzing();
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v' && current === 'home'
      && !['INPUT', 'TEXTAREA'].includes(event.target.tagName)) { event.preventDefault(); pasteFromShortcut(); }
  });
  document.getElementById('web-theme-toggle')?.addEventListener('click', () => {
    applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark', true);
    updateThemeButton();
  });
  document.getElementById('web-lang-toggle')?.addEventListener('click', () => {
    const btn = document.getElementById('web-lang-toggle');
    openOptionMenu(btn, LANG_OPTIONS, currentLanguage(), (val) => {
      setLanguage(val);
      updateLangButton();
      updateThemeButton();
      toast(t('界面语言已切换'), 'info', 1800);
    }, { align: 'right', minWidth: 132, rawLabel: true });
  });
  updateThemeButton();
  updateLangButton();
  applyAccentColor(storedAccentColor(), false);
  document.addEventListener('omni:theme', () => {
    updateThemeButton();
    applyAccentColor(storedAccentColor(), false);
  });
  document.addEventListener('omni:language', () => {
    updateLangButton();
    updateThemeButton();
  });
  await initI18n();
  enhanceSelects(document);
  updateLangButton();
  updateThemeButton();
  initHome(); initImages(); initBatch(); initTasks();
  navigate(location.hash.slice(1), { hash: true });
  try {
    const settings = await get('/api/settings');
    if (settings.version) {
      const ver = `v${settings.version}`;
      const headerVer = document.getElementById('web-version');
      const heroVer = document.getElementById('hero-version-pill');
      if (headerVer) headerVer.textContent = ver;
      if (heroVer) heroVer.textContent = ver;
    }
    if (settings.accentColor) {
      applyAccentColor(settings.accentColor, true);
    }
    window.__omniShowRecentTasks = settings.showRecentTasksOnHome !== false;
    document.dispatchEvent(new CustomEvent('omni:settings', { detail: settings }));
  } catch {
    const conn = document.getElementById('web-conn-status');
    const connText = document.getElementById('web-conn-text');
    if (conn) conn.classList.add('is-offline');
    if (connText) connText.textContent = t('本地服务未连接');
  }
}

window.addEventListener('hashchange', () => navigate(location.hash.slice(1), { hash: false }));
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
