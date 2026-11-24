/* 支持平台标签折叠流与 yt-dlp 平台目录搜索弹窗（对齐 HomePlatformChips & PlatformPacking.kt） */
import { get } from './api.js';
import { openDialog, toast, escapeHtml } from './ui.js';
import { iconSvg } from './icons.js';
import { t } from './i18n.js';

const PLATFORMS = [
  ['哔哩哔哩', '#FB7299'],
  ['抖音', 'var(--text-2)'],
  ['快手', '#FF5000'],
  ['小红书', '#FF2442'],
  ['微博', '#E34B53'],
  ['YouTube', '#FF0000'],
  ['西瓜视频', 'var(--primary)'],
  ['TikTok', '#00F2FE'],
  ['X (Twitter)', '#1DA1F2'],
  ['Instagram', '#E1306C'],
  ['Facebook', '#1877F2'],
  ['Reddit', 'var(--primary)'],
  ['Twitch', 'var(--primary)'],
  ['优酷', 'var(--primary)'],
  ['爱奇艺', 'var(--primary)'],
  ['AcFun', 'var(--primary)'],
  ['虎牙', 'var(--primary)'],
  ['TED', 'var(--primary)'],
  ['Internet Archive', 'var(--primary)'],
  ['Pinterest', '#E60023'],
  ['Pixiv', '#0096FA'],
  ['小黑盒', '#64748B'],
];

let expanded = false;

/** 对齐 Android collapsedPlatformCount：在两行以内尽量排布更多平台，并把展开箭头保留在前两行末尾 */
function computeVisibleCount(container, chips) {
  const width = container.clientWidth;
  if (!width) return chips.length;
  const gap = 7;
  const toggleWidth = 40;
  const widths = chips.map((el) => el.getBoundingClientRect().width || 80);

  for (let count = widths.length; count >= 0; count--) {
    let rows = 1;
    let used = 0;
    const seq = widths.slice(0, count).concat(toggleWidth);
    let ok = true;
    for (const item of seq) {
      const w = Math.min(item, width);
      if (used > 0 && used + gap + w > width) {
        rows++;
        used = w;
      } else {
        used += (used > 0 ? gap : 0) + w;
      }
      if (rows > 2) {
        ok = false;
        break;
      }
    }
    if (ok) return count;
  }
  return 0;
}

export function initPlatforms() {
  const wrap = document.getElementById('platform-wrap');
  const list = document.getElementById('platform-list');
  if (!wrap || !list) return;

  const chipEls = PLATFORMS.map(([name, color]) => {
    const chip = document.createElement('span');
    chip.className = 'platform-chip';
    chip._rawName = name;
    chip.innerHTML = `<span class="platform-chip__dot" style="background:${color}"></span><span data-no-i18n>${escapeHtml(t(name))}</span>`;
    list.append(chip);
    return chip;
  });

  const toggleBtn = document.createElement('button');
  toggleBtn.type = 'button';
  toggleBtn.className = 'platform-toggle-btn';
  toggleBtn.setAttribute('aria-label', t('展开或收起支持平台'));
  toggleBtn.setAttribute('aria-expanded', 'false');
  toggleBtn.innerHTML = iconSvg('expand_more');
  list.append(toggleBtn);

  const applyPacking = () => {
    chipEls.forEach((el) => {
      el.classList.remove('is-extra');
      const labelEl = el.querySelector('span[data-no-i18n]');
      if (labelEl) labelEl.textContent = t(el._rawName);
    });
    toggleBtn.setAttribute('aria-label', t('展开或收起支持平台'));
    const count = computeVisibleCount(list, chipEls);
    chipEls.forEach((el, idx) => {
      if (idx >= count) el.classList.add('is-extra');
    });
    // 把 toggle 按钮插到首批可见芯片末尾（折叠态在两行内，展开态在最末）
    if (expanded) {
      list.append(toggleBtn);
    } else if (count < chipEls.length) {
      list.insertBefore(toggleBtn, chipEls[count]);
    } else {
      list.append(toggleBtn);
    }
    list.classList.toggle('is-expanded', expanded);
    wrap.classList.toggle('is-expanded', expanded);
    toggleBtn.setAttribute('aria-expanded', String(expanded));
  };

  toggleBtn.addEventListener('click', () => {
    expanded = !expanded;
    applyPacking();
  });

  requestAnimationFrame(applyPacking);
  window.addEventListener('resize', () => requestAnimationFrame(applyPacking));
  document.addEventListener('omni:language', () => requestAnimationFrame(applyPacking));

  let entries = null;
  const catalogBtn = document.getElementById('btn-ytdlp-catalog');
  if (catalogBtn) {
    catalogBtn.addEventListener('click', async () => {
      try {
        if (!entries) entries = (await get('/api/platforms')).extractors || [];
        const rows = document.getElementById('extractor-rows');
        const search = document.getElementById('extractor-search');
        const render = () => {
          rows.replaceChildren();
          const q = (search.value || '').trim().toLowerCase();
          const matched = entries.filter((name) => name.toLowerCase().includes(q));
          if (!matched.length) {
            const empty = document.createElement('div');
            empty.className = 'caption';
            empty.textContent = t('未找到匹配的平台');
            rows.append(empty);
            return;
          }
          const frag = document.createDocumentFragment();
          for (const name of matched.slice(0, 300)) {
            const row = document.createElement('div');
            row.style.padding = '6px 4px';
            row.style.borderBottom = '0.5px solid var(--border-subtle)';
            row.textContent = name;
            frag.append(row);
          }
          if (matched.length > 300) {
            const more = document.createElement('div');
            more.className = 'caption';
            more.style.padding = '8px 4px';
            more.textContent = t('仅展示前 300 项（共 %d 项），请输入关键词缩小范围', matched.length);
            frag.append(more);
          }
          rows.append(frag);
        };
        search.oninput = render;
        search.value = '';
        render();
        openDialog('platform-catalog');
      } catch (error) {
        toast(error.message || '加载平台目录失败', 'error');
      }
    });
  }
}

export async function checkApplicationUpdate(manual = false) {
  try {
    const result = await get('/api/updates');
    const link = document.getElementById('application-update-link');
    if (result && result.update && link) {
      link.href = result.update.url;
      link.textContent = `${t('下载更新')} (${result.update.version})`;
      link.hidden = false;
      toast(t('发现新版 Web %s', result.update.version), 'info', 8000);
    } else if (manual) {
      toast('当前已是最新 Web 版本', 'info');
    }
  } catch (error) {
    if (manual) toast(error.message || '暂时无法检查应用更新', 'error');
  }
}
