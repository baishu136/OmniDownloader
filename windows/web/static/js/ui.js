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
export function openOptionMenu(anchorEl, options, currentValue, onSelect, config = {}) {
  document.querySelectorAll('.omni-option-menu').forEach((m) => {
    if (typeof m._omniCleanup === 'function') m._omniCleanup(false);
    else m.remove();
  });
  if (!anchorEl) return;

  const {
    align = 'right',
    minWidth = 0,
    extraClass = '',
    rawLabel = false,
    onClose = null,
  } = config;

  const menu = document.createElement('div');
  menu.className = extraClass ? `omni-option-menu ${extraClass}` : 'omni-option-menu';
  menu.setAttribute('role', 'menu');
  if (minWidth > 0) menu.style.minWidth = `${minWidth}px`;

  const items = [];
  options.forEach(({ value, label }) => {
    const selected = value === currentValue;
    const item = document.createElement('button');
    item.type = 'button';
    item.className = `omni-option-menu__item${selected ? ' is-selected' : ''}`;
    item.setAttribute('role', 'menuitemradio');
    item.setAttribute('aria-checked', String(selected));
    const displayText = rawLabel ? String(label ?? '') : t(label);
    item.innerHTML = `<span>${escapeHtml(displayText)}</span>${selected ? `<span class="omni-option-menu__check">${iconSvg('check')}</span>` : ''}`;
    item.addEventListener('click', (event) => {
      event.stopPropagation();
      cleanup(true);
      if (onSelect) onSelect(value);
    });
    menu.appendChild(item);
    items.push(item);
  });

  document.body.appendChild(menu);

  // 依照 OptionMenuPlacement.position 对齐边界与视口安全距
  const rect = anchorEl.getBoundingClientRect();
  const margin = 12;
  const gap = 6;
  const panelRect = menu.getBoundingClientRect();
  const vw = window.innerWidth;
  const vh = window.innerHeight;

  const below = vh - margin - rect.bottom - gap;
  const above = rect.top - margin - gap;
  const opensAbove = below < panelRect.height && above > below;
  const preferredLeft = align === 'left' ? rect.left : (rect.right - panelRect.width);
  const left = Math.max(margin, Math.min(preferredLeft, vw - margin - panelRect.width));
  const preferredTop = opensAbove ? rect.top - panelRect.height - gap : rect.bottom + gap;
  const top = Math.max(margin, Math.min(preferredTop, vh - margin - panelRect.height));

  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
  menu.style.transformOrigin = `${opensAbove ? 'bottom' : 'top'} ${align === 'left' ? 'left' : 'right'}`;

  let closed = false;
  const onOutside = (e) => {
    if (!menu.contains(e.target) && !anchorEl.contains(e.target)) cleanup(false);
  };
  const onKeyDown = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      cleanup(true);
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const idx = items.indexOf(document.activeElement);
      const step = e.key === 'ArrowDown' ? 1 : -1;
      const nextIdx = idx < 0 ? 0 : (idx + step + items.length) % items.length;
      items[nextIdx]?.focus();
    }
  };
  const onResize = () => cleanup(false);
  function cleanup(restoreFocus = false) {
    if (closed) return;
    closed = true;
    menu.remove();
    document.removeEventListener('mousedown', onOutside, true);
    document.removeEventListener('keydown', onKeyDown, true);
    window.removeEventListener('resize', onResize);
    if (typeof onClose === 'function') onClose();
    if (restoreFocus && typeof anchorEl.focus === 'function') anchorEl.focus();
  }
  menu._omniCleanup = cleanup;

  setTimeout(() => {
    if (closed) return;
    document.addEventListener('mousedown', onOutside, true);
    document.addEventListener('keydown', onKeyDown, true);
    window.addEventListener('resize', onResize, { once: true });
    const activeItem = items.find((el) => el.classList.contains('is-selected')) || items[0];
    if (activeItem) activeItem.focus({ preventScroll: true });
  }, 0);
}

/* ---------- Fluent 自定义下拉选择器（替代原生效用受限弹窗） ---------- */
const selectValueDesc = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
const selectIndexDesc = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'selectedIndex');
let selectLangBound = false;

export function syncAllSelects(root = document) {
  root.querySelectorAll('select[data-omni-enhanced="true"]').forEach((sel) => {
    if (typeof sel._omniSync === 'function') sel._omniSync();
  });
}

export function enhanceSelects(root = document) {
  if (!selectLangBound) {
    selectLangBound = true;
    document.addEventListener('omni:language', () => requestAnimationFrame(() => syncAllSelects(document)));
  }

  root.querySelectorAll('select:not([data-omni-enhanced="true"]):not([data-omni-native="true"])').forEach((sel) => {
    const parent = sel.parentNode;
    if (!parent) return;

    const wrap = document.createElement('div');
    wrap.className = 'omni-select-wrap';
    parent.insertBefore(wrap, sel);
    wrap.appendChild(sel);

    sel.dataset.omniEnhanced = 'true';
    sel.classList.add('omni-native-select');
    sel.tabIndex = -1;
    sel.setAttribute('aria-hidden', 'true');

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'omni-select-btn';
    btn.setAttribute('aria-haspopup', 'listbox');
    btn.setAttribute('aria-expanded', 'false');

    const labelSpan = document.createElement('span');
    labelSpan.className = 'omni-select-btn__label';
    labelSpan.dataset.noI18n = 'true';

    const arrowSpan = document.createElement('span');
    arrowSpan.className = 'omni-select-btn__arrow';
    arrowSpan.setAttribute('aria-hidden', 'true');
    arrowSpan.innerHTML = iconSvg('expand_more');

    btn.appendChild(labelSpan);
    btn.appendChild(arrowSpan);
    wrap.appendChild(btn);

    const sync = () => {
      wrap.hidden = sel.hidden;
      btn.disabled = sel.disabled;
      const aria = sel.getAttribute('aria-label');
      if (aria) btn.setAttribute('aria-label', aria);
      const selected = sel.selectedOptions?.[0] || sel.options?.[sel.selectedIndex];
      labelSpan.textContent = selected ? selected.textContent.trim() : '';
    };
    sel._omniSync = sync;

    if (selectValueDesc?.get && selectValueDesc?.set) {
      Object.defineProperty(sel, 'value', {
        configurable: true,
        get() { return selectValueDesc.get.call(this); },
        set(val) {
          selectValueDesc.set.call(this, val);
          sync();
        },
      });
    }
    if (selectIndexDesc?.get && selectIndexDesc?.set) {
      Object.defineProperty(sel, 'selectedIndex', {
        configurable: true,
        get() { return selectIndexDesc.get.call(this); },
        set(idx) {
          selectIndexDesc.set.call(this, idx);
          sync();
        },
      });
    }

    sel.addEventListener('change', sync);

    const obs = new MutationObserver(sync);
    obs.observe(sel, {
      attributes: true,
      attributeFilter: ['hidden', 'disabled', 'aria-label'],
      childList: true,
      subtree: true,
      characterData: true,
    });

    btn.addEventListener('click', () => {
      if (sel.disabled) return;
      if (btn.getAttribute('aria-expanded') === 'true') {
        document.querySelectorAll('.omni-option-menu').forEach((m) => {
          if (typeof m._omniCleanup === 'function') m._omniCleanup(true);
          else m.remove();
        });
        return;
      }
      const options = Array.from(sel.options).map((opt) => ({
        value: opt.value,
        label: opt.textContent.trim(),
      }));
      btn.setAttribute('aria-expanded', 'true');
      const rect = btn.getBoundingClientRect();
      openOptionMenu(
        btn,
        options,
        sel.value,
        (val) => {
          if (sel.value !== val) {
            sel.value = val;
            sel.dispatchEvent(new Event('change', { bubbles: true }));
          } else {
            sync();
          }
        },
        {
          align: 'left',
          minWidth: Math.max(140, Math.round(rect.width)),
          extraClass: 'omni-option-menu--select',
          rawLabel: true,
          onClose: () => btn.setAttribute('aria-expanded', 'false'),
        },
      );
    });

    sync();
  });
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

function normalizeHexColor(hex) {
  if (!hex || typeof hex !== 'string') return '';
  const m = hex.trim().match(/^#?([0-9a-fA-F]{6})$/);
  return m ? `#${m[1].toUpperCase()}` : '';
}

function blendHex(hex, targetHex, weight) {
  const a = [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
  const b = [parseInt(targetHex.slice(1, 3), 16), parseInt(targetHex.slice(3, 5), 16), parseInt(targetHex.slice(5, 7), 16)];
  const ch = a.map((v, i) => Math.max(0, Math.min(255, Math.round(v + (b[i] - v) * weight))));
  return '#' + ch.map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase();
}

export function storedAccentColor() {
  try { return localStorage.getItem('omni_accent_color') || 'system'; } catch (e) { return 'system'; }
}

export function applyAccentColor(accentColor, persist = true) {
  const raw = (accentColor || 'system').trim();
  const hex = normalizeHexColor(raw);
  const effective = hex || 'system';
  if (persist) {
    try { localStorage.setItem('omni_accent_color', effective); } catch (e) { /* ignore */ }
  }
  const rootStyle = document.documentElement.style;
  const props = ['--primary', '--primary-hover', '--on-primary', '--primary-container', '--on-primary-container', '--primary-soft', '--primary-soft-strong', '--info', '--info-soft'];
  if (!hex) {
    props.forEach(p => rootStyle.removeProperty(p));
    return;
  }
  const dark = document.documentElement.dataset.theme === 'dark';
  const primary = dark ? blendHex(hex, '#FFFFFF', 0.34) : hex;
  const hover = dark ? blendHex(hex, '#FFFFFF', 0.48) : blendHex(hex, '#000000', 0.14);
  const r = parseInt(primary.slice(1, 3), 16), g = parseInt(primary.slice(3, 5), 16), b = parseInt(primary.slice(5, 7), 16);
  const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  rootStyle.setProperty('--primary', primary);
  rootStyle.setProperty('--primary-hover', hover);
  rootStyle.setProperty('--on-primary', lum > 0.58 ? '#111318' : '#FFFFFF');
  rootStyle.setProperty('--primary-container', dark ? blendHex(hex, '#000000', 0.32) : blendHex(hex, '#FFFFFF', 0.80));
  rootStyle.setProperty('--on-primary-container', dark ? blendHex(hex, '#FFFFFF', 0.85) : blendHex(hex, '#000000', 0.55));
  rootStyle.setProperty('--primary-soft', `rgba(${r}, ${g}, ${b}, ${dark ? 0.16 : 0.12})`);
  rootStyle.setProperty('--primary-soft-strong', `rgba(${r}, ${g}, ${b}, ${dark ? 0.24 : 0.18})`);
  rootStyle.setProperty('--info', primary);
  rootStyle.setProperty('--info-soft', `rgba(${r}, ${g}, ${b}, ${dark ? 0.16 : 0.12})`);
}

themeMedia.addEventListener('change', () => {
  if (storedTheme() === 'auto') applyTheme('auto', false);
  applyAccentColor(storedAccentColor(), false);
});
