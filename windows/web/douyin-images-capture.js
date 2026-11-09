(() => {
  const target = window.__omniCaptureId;
  if (!/^\d{10,24}$/.test(target || '') || window.__omniCaptureInstalledFor === target) return;
  if (!['www.douyin.com', 'www.iesdouyin.com'].includes(location.hostname)) return;
  window.__omniCaptureInstalledFor = target;
  const find = (value, depth = 0) => {
    if (!value || typeof value !== 'object' || depth > 25) return null;
    if (String(value.aweme_id || value.awemeId || '') === target && (value.images?.length || value.image_post_info?.images?.length)) return value;
    for (const child of Object.values(value)) { const item = find(child, depth + 1); if (item) return item; }
    return null;
  };
  const keep = data => {
    const item = find(data);
    if (item) window.__omniCapturedImages = {aweme_id: target, desc: item.desc, author: {nickname: item.author?.nickname || ''}, images: item.images || item.image_post_info?.images};
  };
  const allowed = url => {
    try { const u = new URL(url, location.href); return u.origin === location.origin && u.pathname.startsWith('/aweme/v1/'); } catch (_) { return false; }
  };
  // Observe the site's own normal responses; credentials and request headers are never read.
  const originalFetch = window.fetch;
  let reading = 0;
  window.fetch = function(...args) {
    const promise = originalFetch.apply(this, args);
    if (allowed(args[0]?.url || args[0])) promise.then(async response => {
      if (!response.ok || !response.body || reading >= 3 || Number(response.headers.get('Content-Length')) > 4194304) return;
      reading++;
      const reader = response.clone().body.getReader(); const decoder = new TextDecoder(); let text = '', size = 0;
      try {
        while (true) {
          const chunk = await reader.read(); if (chunk.done) break;
          size += chunk.value.byteLength;
          if (size > 4194304) { reader.cancel(); return; }
          text += decoder.decode(chunk.value, {stream: true});
        }
        text += decoder.decode(); keep(JSON.parse(text));
      } catch (_) {} finally { reading--; reader.releaseLock(); }
    }).catch(() => {});
    return promise;
  };
  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    this.addEventListener('load', () => {
      if (!allowed(url)) return;
      try {
        if (this.responseType === '' || this.responseType === 'text') {
          const text = this.responseText; if (text.length <= 4194304) keep(JSON.parse(text));
        } else if (this.responseType === 'json') keep(this.response);
      } catch (_) {}
    }, {once: true});
    return open.call(this, method, url, ...rest);
  };
  keep(window._ROUTER_DATA);
})();
