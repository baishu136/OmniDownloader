/* 多语言国际化引擎：复用 strings.json，兼容静态 DOM 文本、placeholder 属性及动态参数模板 */

const LOCALES = { 'zh-CN': 'zh-CN', 'zh-TW': 'zh-TW', en: 'en', ja: 'ja' };
const SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'CODE', 'PRE', 'INPUT']);

let dictionaries = null;
const reverse = new Map();
const sources = new Set();
let language = 'zh-CN';
let applying = false;
let observer = null;
let scheduled = false;

export function currentLanguage() {
  try {
    const saved = localStorage.getItem('omni.language') || 'zh-CN';
    return LOCALES[saved] || 'zh-CN';
  } catch (e) {
    return 'zh-CN';
  }
}

export function t(source, ...args) {
  let text = (dictionaries && dictionaries[language] && dictionaries[language][source]) || source;
  if (args.length) {
    let idx = 0;
    text = text.replace(/%[sd]/g, () => (idx < args.length ? String(args[idx++]) : ''));
    text = text.replace(/%%/g, '%');
  }
  return text;
}

function lookupRaw(trimmed) {
  const source = sources.has(trimmed) ? trimmed : reverse.get(trimmed);
  if (!source) return null;
  return (dictionaries[language] && dictionaries[language][source]) || source;
}

function translateText(node) {
  const raw = node.textContent;
  const trimmed = raw.trim();
  if (!trimmed) return;
  const next = lookupRaw(trimmed);
  if (next && next !== trimmed) {
    node.textContent = raw.replace(trimmed, next);
  }
}

function translateAttrs(root) {
  if (!dictionaries) return;
  root.querySelectorAll('[placeholder], [data-i18n-title], [title]').forEach((el) => {
    if (el.closest('[data-no-i18n]')) return;
    if (el.hasAttribute('placeholder')) {
      const val = el.getAttribute('placeholder').trim();
      const next = lookupRaw(val);
      if (next && next !== val) el.setAttribute('placeholder', next);
    }
    if (el.hasAttribute('title')) {
      const val = el.getAttribute('title').trim();
      const next = lookupRaw(val);
      if (next && next !== val) el.setAttribute('title', next);
    }
  });
}

function applyTo(root) {
  if (!dictionaries || applying) return;
  applying = true;
  try {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const node = walker.currentNode;
      const parent = node.parentElement;
      if (!parent || SKIP.has(parent.tagName)) continue;
      if (parent.closest('.guide-body, [data-no-i18n]')) continue;
      translateText(node);
    }
    translateAttrs(root);
  } finally {
    applying = false;
  }
}

export function applyLanguage() {
  applyTo(document.body);
}

export function setLanguage(lang) {
  language = LOCALES[lang] || 'zh-CN';
  try { localStorage.setItem('omni.language', language); } catch (e) { /* ignore */ }
  document.documentElement.lang = language;
  applyLanguage();
  document.dispatchEvent(new CustomEvent('omni:language', { detail: { language } }));
}

export async function initI18n() {
  language = currentLanguage();
  document.documentElement.lang = language;
  try {
    const response = await fetch('/static/strings.json');
    if (response.ok) {
      dictionaries = await response.json();
      for (const table of Object.values(dictionaries)) {
        for (const [source, value] of Object.entries(table)) {
          sources.add(source);
          if (value && !reverse.has(value)) reverse.set(value, source);
        }
      }
    }
  } catch (e) {
    dictionaries = null;
  }
  applyLanguage();
  if (!observer && window.MutationObserver) {
    observer = new MutationObserver(() => {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(() => {
        scheduled = false;
        applyLanguage();
      });
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  }
}
