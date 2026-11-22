/* 应用启动入口：图标注水、导航切换、弹窗托管、快捷键与哈希路由 */
import { hydrateIcons } from './icons.js';
import { initI18n } from './i18n.js';
import { initPlatforms, checkApplicationUpdate } from './platforms.js';
import { initRelay } from './relay.js';
import { initHome, focusInput, pasteFromShortcut, cancelAnalyzing, fillAndAnalyze } from './home.js';
import { initImages } from './images.js';
import { initBatch } from './batch.js';
import { initTasks, refreshTasks } from './tasks.js';
import { initSettings, showCategory } from './settings.js';
import { openDialog, closeDialog, closeTopDialog } from './ui.js';

const PAGES = ['home', 'batch', 'tasks', 'settings'];
let currentPage = 'home';

function navigate(page, options = {}) {
  if (page === 'images') page = 'home';
  if (!PAGES.includes(page)) page = 'home';

  if (page !== currentPage) {
    const previous = document.getElementById(`page-${currentPage}`);
    const next = document.getElementById(`page-${page}`);
    if (previous) previous.hidden = true;
    if (next) {
      next.hidden = false;
      next.classList.remove('is-entering');
      void next.offsetWidth;
      next.classList.add('is-entering');
    }
    currentPage = page;
  }

  document.querySelectorAll('.nav-rail .m3-nav-item[data-nav]').forEach((button) => {
    const active = button.dataset.nav === page;
    button.classList.toggle('is-active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });

  const workspace = document.getElementById('workspace');
  if (workspace) workspace.scrollTop = 0;

  if (options.hash !== false) {
    const target = page === 'home' ? '' : `#${page}${options.cat && options.cat !== 'root' ? '/' + options.cat : ''}`;
    if (location.hash !== target) history.replaceState(null, '', target || location.pathname);
  }

  if (page === 'tasks') refreshTasks();
  if (page === 'settings') showCategory(options.cat || 'root', options.hash !== false);
  if (page === 'home' && options.analyzeUrl) fillAndAnalyze(options.analyzeUrl);
  else if (page === 'home' && options.focusInput) focusInput();
}

function initNavigation() {
  document.addEventListener('click', (event) => {
    const trigger = event.target.closest('[data-nav]');
    if (!trigger) return;
    navigate(trigger.dataset.nav, { cat: trigger.dataset.settingsCat });
  });

  document.addEventListener('omni:navigate', (event) => {
    const detail = event.detail || {};
    navigate(detail.page, detail);
  });
}

function initDialogs() {
  document.addEventListener('click', (event) => {
    const closer = event.target.closest('[data-close]');
    if (closer) {
      closeDialog(closer.dataset.close);
      return;
    }
    if (event.target.classList && (event.target.classList.contains('dialog-overlay') || event.target.classList.contains('sheet-overlay'))) {
      if (event.target.id === 'confirm-modal') {
        document.getElementById('confirm-cancel')?.click();
      } else {
        closeDialog(event.target);
      }
    }
  });
}

function initGuide() {
  const open = () => {
    switchGuideLang('zh-cn', 0);
    openDialog('guide-modal');
  };
  document.getElementById('nav-help')?.addEventListener('click', open);
  document.getElementById('btn-home-help')?.addEventListener('click', open);
  document.getElementById('btn-open-guide')?.addEventListener('click', open);

  document.getElementById('guide-lang')?.addEventListener('click', (event) => {
    const button = event.target.closest('[data-lang]');
    if (button) switchGuideLang(button.dataset.lang, Number(button.dataset.segIdx || 0));
  });
}

function switchGuideLang(lang, segIdx) {
  const seg = document.getElementById('guide-lang');
  if (seg) seg.style.setProperty('--seg-index', String(segIdx));
  document.querySelectorAll('#guide-lang [data-lang]').forEach((button) => {
    button.classList.toggle('is-active', button.dataset.lang === lang);
  });
  document.querySelectorAll('[data-guide]').forEach((block) => {
    block.hidden = block.dataset.guide !== lang;
  });
}

function initKeyboard() {
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      if (closeTopDialog()) return;
      cancelAnalyzing();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') {
      const target = event.target;
      const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA');
      if (!typing && currentPage === 'home') {
        event.preventDefault();
        pasteFromShortcut();
      }
    }
  });
}

function readHash() {
  const raw = location.hash.replace(/^#/, '');
  const [page, cat] = raw.split('/');
  return { page: PAGES.includes(page) ? page : 'home', cat };
}

async function boot() {
  hydrateIcons(document);
  initNavigation();
  initDialogs();
  initGuide();
  initKeyboard();

  await initI18n();

  initRelay();
  initHome();
  initPlatforms();
  checkApplicationUpdate();
  document.getElementById('btn-check-update')?.addEventListener('click', () => checkApplicationUpdate(true));
  initImages();
  initBatch();
  initTasks();
  initSettings();

  const { page, cat } = readHash();
  navigate(page, { cat });
}

window.addEventListener('hashchange', () => {
  const { page, cat } = readHash();
  navigate(page, { cat, hash: false });
});

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}
