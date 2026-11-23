/*
 * nj-cookie-exporter background script.
 *
 * Content scripts cannot open a native port on GeckoView
 * (mozilla/geckoview#220), so this background page connects to the app with
 * `runtime.connectNative`. The app posts { cmd: 'exportCookies', host } over
 * the port and we answer with that host's cookies formatted as a Netscape
 * HTTP Cookie File for yt-dlp — using our cookies permission directly (no
 * content script involved).
 */
(() => {
  let nativePort = null;
  try {
    nativePort = browser.runtime.connectNative('cookiejar');
  } catch (e) {
    return;
  }
  if (!nativePort) return;

  nativePort.onDisconnect.addListener(() => { nativePort = null; });

  const safeHost = (h) => {
    const s = String(h || '').replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    return /^[a-z0-9.-]+$/i.test(s) ? s : null;
  };

  const formatCookies = (host) => browser.cookies.getAll({ url: 'https://' + host })
    .then((cookies) => {
      const lines = ['# Netscape HTTP Cookie File', '# Exported by nj-dialer cookiejar'];
      for (const c of cookies || []) {
        const domain = String(c.domain || '').startsWith('.') ? c.domain : '.' + (c.domain || '');
        const path = c.path || '/';
        const secure = c.secure ? 'TRUE' : 'FALSE';
        const hostOnly = !String(c.domain || '').startsWith('.');
        const expires = c.expirationDate ? Math.floor(c.expirationDate) : 0;
        const prefix = c.httpOnly ? '#HttpOnly_' : '';
        lines.push([
          prefix + domain,
          hostOnly ? 'TRUE' : 'FALSE',
          path,
          secure,
          String(expires),
          String(c.name || ''),
          String(c.value || '')
        ].join('\t'));
      }
      return { text: lines.join('\n') };
    })
    .catch(() => ({ text: '' }));

  nativePort.onMessage.addListener((msg) => {
    if (!msg || msg.cmd !== 'exportCookies') return;
    const host = safeHost(msg.host);
    if (!host || !nativePort) return;
    formatCookies(host).then((resp) => {
      try { nativePort.postMessage({ type: 'cookies', host: host, text: resp.text }); }
      catch (e) { /* ignore */ }
    });
  });
})();