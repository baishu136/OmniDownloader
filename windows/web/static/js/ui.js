/* 共享 UI 基元：Snackbar/Toast、弹窗、OmniOptionMenu 浮动菜单、错误详情弹窗、主题控制 */
import { iconSvg } from './icons.js';
import { t } from './i18n.js';

const TOAST_ICONS = {
  success: 'check_circle',
  error: 'error',
  warning: 'warning',
  info: 'info',
};

export function escapeHtml(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

export function debounce(fn, delay) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

/* ---------- Toast / Snackbar ---------- */
export function toast(message, type = 'success', timeout = 3600) {
  const stack = document.getElementById('toast-stack');
  if (!stack) return;
  const node = document.createElement('div');
  node.className = `toast toast--${type}`;
  node.setAttribute('role', type === 'error' ? 'alert' : 'status');
  node.innerHTML = `${iconSvg(TOAST_ICONS[type] || 'info')}<span>${escapeHtml(t(message))}</span>`;
  stack.appendChild(node);
  const remove = () => {
    node.classList.add('is-leaving');
    setTimeout(() => node.remove(), 180);
  };
  const timer = setTimeout(remove, timeout);
  node.addEventListener('click', () => { clearTimeout(timer); remove(); });
}

/* ---------- Dialogs & Sheets ---------- */
export function openDialog(id) {
  const overlay = typeof id === 'string' ? document.getElementById(id) : id;
  if (!overlay) return;
  if (overlay._closeTimer) { clearTimeout(overlay._closeTimer); overlay._closeTimer = null; }
  overlay.classList.remove('is-closing');
  overlay.hidden = false;
  const focusable = overlay.querySelector('input:not([type="checkbox"]), select, textarea, button:not([data-close])');
  if (focusable) setTimeout(() => focusable.focus(), 40);
}

export function closeDialog(id) {
  const overlay = typeof id === 'string' ? document.getElementById(id) : id;
  if (!overlay || overlay.hidden) return;
  if (overlay._closeTimer) clearTimeout(overlay._closeTimer);
  overlay.hidden = true;
  overlay.classList.remove('is-closing');
  overlay._closeTimer = null;
}

export function closeTopDialog() {
  const openMenu = document.querySelector('.omni-option-menu');
  if (openMenu) {
    openMenu.remove();
    return true;
  }
  const open = Array.from(document.querySelectorAll('.dialog-overlay, .sheet-overlay')).filter((el) => !el.hidden);
  if (open.length) {
    const top = open[open.length - 1];
    if (top.id === 'confirm-modal') {
      const cancel = document.getElementById('confirm-cancel');
      if (cancel) cancel.click();
    } else {
      closeDialog(top);
    }
    return true;
  }
  return false;
}

/* ---------- Confirm Dialog（支持可选复选框：如同时删除本地文件） ---------- */
export function confirmDialog({
  title = '确认操作',
  message = '',
  confirmText = '确认',
  cancelText = '取消',
  danger = true,
  checkboxLabel = '',
  checkboxDefault = false,
} = {}) {
  const modal = document.getElementById('confirm-modal');
  if (!modal) return Promise.resolve(window.confirm(message || title));

  document.getElementById('confirm-title').textContent = t(title);
  document.getElementById('confirm-message').textContent = t(message);
  const ok = document.getElementById('confirm-ok');
  const cancel = document.getElementById('confirm-cancel');
  ok.textContent = t(confirmText);
  cancel.textContent = t(cancelText);
  ok.className = danger ? 'btn btn-danger' : 'btn btn-primary';

  const cbRow = document.getElementById('confirm-checkbox-row');
  const cb = document.getElementById('confirm-checkbox');
  const cbLabel = document.getElementById('confirm-checkbox-label');
  if (checkboxLabel && cbRow && cb && cbLabel) {
    cbRow.hidden = false;
    cb.checked = Boolean(checkboxDefault);
    cbLabel.textContent = t(checkboxLabel);
  } else if (cbRow && cb) {
    cbRow.hidden = true;
    cb.checked = false;
  }

  openDialog(modal);
  return new Promise((resolve) => {
    const finish = (accepted) => {
      ok.removeEventListener('click', onOk);
      cancel.removeEventListener('click', onCancel);
      closeDialog(modal);
      if (checkboxLabel) {
        resolve(accepted ? { confirmed: true, checked: cb.checked } : false);
      } else {
        resolve(accepted);
      }
    };
    const onOk = () => finish(true);
    const onCancel = () => finish(false);
    ok.addEventListener('click', onOk);
    cancel.addEventListener('click', onCancel);
  });
}

/* ---------- 错误详情弹窗（对齐 HomeScreen / TaskErrorSection） ---------- */
export function showErrorDetailDialog({ title = '失败日志详情', message = '', showSettingsAction = false, onGoSettings = null } = {}) {
  const modal = document.getElementById('error-detail-modal');
  if (!modal) return;
  document.getElementById('error-detail-title').textContent = t(title);
  document.getElementById('error-detail-text').textContent = String(message || '');

  const settingsBtn = document.getElementById('btn-error-go-settings');
  settingsBtn.hidden = !showSettingsAction;
  settingsBtn.onclick = () => {
    closeDialog(modal);
    if (onGoSettings) onGoSettings();
  };

  document.getElementById('btn-copy-error-log').onclick = async () => {
    try {
      await navigator.clipboard.writeText(String(message || ''));
      toast('已复制错误日志', 'success');
    } catch (e) {
      toast('复制失败，请手动选中文本复制', 'warning');
    }
    closeDialog(modal);
  };

  openDialog(modal);
}

/* ---------- OmniOptionMenu（对齐 OmniOptionMenu.kt） ---------- */
export function openOptionMenu(anchorEl, options, currentValue, onSelect) {
  document.querySelectorAll('.omni-option-menu').forEach((m) => m.remove());
  if (!anchorEl) return;

  const menu = document.createElement('div');
  menu.className = 'omni-option-menu';
  menu.setAttribute('role', 'menu');

  options.forEach(({ value, label }) => {
    const selected = value === currentValue;
    const item = document.createElement('button');
    item.type = 'button';
    item.className = `omni-option-menu__item${selected ? ' is-selected' : ''}`;
    item.setAttribute('role', 'menuitemradio');
    item.setAttribute('aria-checked', String(selected));
    item.innerHTML = `<span>${escapeHtml(t(label))}</span>${selected ? `<span class="omni-option-menu__check">${iconSvg('check')}</span>` : ''}`;
    item.addEventListener('click', (event) => {
      event.stopPropagation();
      cleanup();
      if (onSelect) onSelect(value);
    });
    menu.appendChild(item);
  });

  document.body.appendChild(menu);

  // 依照 OptionMenuPlacement.position 对齐右边缘与视口安全距
  const rect = anchorEl.getBoundingClientRect();
  const margin = 16;
  const gap = 8;
  const panelRect = menu.getBoundingClientRect();
  const vw = window.innerWidth;
  const vh = window.innerHeight;

  const below = vh - margin - rect.bottom - gap;
  const above = rect.top - margin - gap;
  const opensAbove = below < panelRect.height && above > below;
  const left = Math.max(margin, Math.min(rect.right - panelRect.width, vw - margin - panelRect.width));
  const preferredTop = opensAbove ? rect.top - panelRect.height - gap : rect.bottom + gap;
  const top = Math.max(margin, Math.min(preferredTop, vh - margin - panelRect.height));

  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
  menu.style.transformOrigin = opensAbove ? 'bottom right' : 'top right';

  const onOutside = (e) => {
    if (!menu.contains(e.target) && !anchorEl.contains(e.target)) cleanup();
  };
  const onResize = () => cleanup();
  function cleanup() {
    menu.remove();
    document.removeEventListener('mousedown', onOutside, true);
    window.removeEventListener('resize', onResize);
  }
  setTimeout(() => {
    document.addEventListener('mousedown', onOutside, true);
    window.addEventListener('resize', onResize, { once: true });
  }, 0);
}

/* ---------- Theme & AMOLED ---------- */
const themeMedia = window.matchMedia('(prefers-color-scheme: dark)');

export function resolveTheme(theme) {
  if (theme === 'dark' || theme === 'light') return theme;
  return themeMedia.matches ? 'dark' : 'light';
}

export function applyTheme(theme, persist = true) {
  const resolved = resolveTheme(theme);
  const amoled = storedAmoled();
  const root = document.documentElement;
  root.dataset.theme = resolved;
  root.dataset.amoled = (resolved === 'dark' && amoled) ? 'true' : 'false';
  root.style.colorScheme = resolved;
  if (persist) {
    try { localStorage.setItem('omni_theme', theme); } catch (e) { /* ignore */ }
  }
  document.dispatchEvent(new CustomEvent('omni:theme', { detail: { theme, resolved, amoled } }));
}

export function storedTheme() {
  try { return localStorage.getItem('omni_theme') || 'auto'; } catch (e) { return 'auto'; }
}

export function storedAmoled() {
  try { return localStorage.getItem('omni_amoled') === 'true'; } catch (e) { return false; }
}

export function applyAmoled(enabled) {
  try { localStorage.setItem('omni_amoled', enabled ? 'true' : 'false'); } catch (e) { /* ignore */ }
  applyTheme(storedTheme(), false);
}

themeMedia.addEventListener('change', () => {
  if (storedTheme() === 'auto') applyTheme('auto', false);
});
