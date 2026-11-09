(() => {
  if (window.__omniVideoObserverInstalled) return;
  window.__omniVideoObserverInstalled = true;
  const target = String(window.__omniCaptureId || '');
  const playable = video => video && [video, ...(video.bit_rate || video.bitRateList || [])].some(variant =>
    Object.entries(variant || {}).some(([key, address]) => /^(play_addr|playAddr)/.test(key) && Array.isArray(address?.url_list || address?.urlList) &&
      (address.url_list || address.urlList).some(url => typeof url === 'string' && /^https?:\/\//.test(url))));
  const keep = value => {
    let visited = 0;
    const seen = new WeakSet();
    const items = Array.isArray(window.__omniCapturedVideos) ? window.__omniCapturedVideos.slice() : [];
    const walk = (item, depth = 0) => {
      if (!item || typeof item !== 'object' || depth > 25 || ++visited > 4096 || seen.has(item)) return;
      seen.add(item);
      if (String(item.aweme_id || item.awemeId || '') === target && playable(item.video)) {
        const entry = {aweme_id: target, desc: item.desc, author: {nickname: item.author?.nickname || ''},
          video: item.video, duration: item.duration};
        const signature = JSON.stringify(entry.video);
        if (!items.some(old => JSON.stringify(old.video) === signature)) items.push(entry);
        return;
      }
      for (const child of Object.values(item)) walk(child, depth + 1);
    };
    walk(value);
    // Retain distinct renditions from later detail/player requests; a lower
    // response must not overwrite the higher rendition already observed.
    if (items.length) {
      window.__omniCapturedVideos = items.slice(-16);
      window.__omniCapturedVideo = items[0];
    }
  };
  const allowed = value => {
    try { const url = new URL(value, location.href); return url.origin === location.origin && url.pathname.startsWith('/aweme/v1/'); }
    catch (_) { return false; }
  };
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
          if (this.responseText.length <= 4194304) keep(JSON.parse(this.responseText));
        } else if (this.responseType === 'json') keep(this.response);
      } catch (_) {}
    }, {once: true});
    return open.call(this, method, url, ...rest);
  };
  keep(window._ROUTER_DATA);
})();
