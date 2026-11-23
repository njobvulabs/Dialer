/*
 * nj-browser-controls content script.
 * Connects a port to the native app (nj-dialer) and carries page commands:
 *  - detectVideo: sniff the page for a video/media URL (base64 JSON reply)
 *  - fit: on facebook.com, clamp the desktop chat dock (auto at load + on demand)
 * Note: page zoom and the video fullscreen commands were removed (browser⋮-menu
 * fullscreen/zoom controls were dropped from the app).
 */
(() => {
  const APP = 'njbrowser';
  let port = null;
  try {
    port = browser.runtime.connect({ name: APP });
  } catch (e) {
    // runtime not available (e.g. about: pages) — nothing to do.
    return;
  }
  if (!port) return;

  const send = (obj) => {
    try { port.postMessage(obj); } catch (e) { /* ignore */ }
  };

  // Page-side video detector, mirroring QuickDownloadDialog.DETECT_JS.
  // Runs in content-script scope: DOM + anchors + performance entries are
  // readable here; the YouTube `ytInitialPlayerResponse` branch is excluded
  // (page scope only) — the canonical/watch-link fallback covers YouTube.
  const detectVideo = () => {
    const ok = (u) => !!u && typeof u === 'string' && u.indexOf('http') === 0;
    const b64 = (obj) => {
      try {
        return btoa(unescape(encodeURIComponent(JSON.stringify(obj))));
      } catch (e) { return ''; }
    };
    const pageLink = (u) => {
      if (!ok(u)) return null;
      if (/(watch\?v=|\/shorts\/|\/reel\/|\/videos\/|video\.php|\/watch\/|\/p\/|\/tv\/)/.test(u)) return u.split('?__')[0];
      return null;
    };
    let v = document.querySelector('video');
    let s = v && (v.currentSrc || v.src);
    if (ok(s) && s.indexOf('blob:') !== 0 && s.indexOf('data:') !== 0) return b64({ kind: 'direct video', url: s });
    const c = document.querySelector('link[rel="canonical"]');
    let p = pageLink(c && c.href); if (p) return b64({ kind: 'video page link', url: p });
    p = pageLink(location.href); if (p) return b64({ kind: 'video page link', url: p });
    if (v) {
      let a = null, n = v;
      for (let i = 0; i < 6 && !a && n; i++) {
        a = n.closest ? n.closest('a[href]') : null;
        if (!a && n.querySelectorAll) {
          const list = n.querySelectorAll('a[href]');
          for (let j = 0; j < list.length; j++) {
            if (pageLink(list[j].href)) { a = list[j]; break; }
          }
        }
        n = n.parentElement;
      }
      p = a && pageLink(a.href); if (p) return b64({ kind: 'video page link', url: p });
    }
    try {
      const m = document.querySelector('meta[property="og:video"]');
      if (m && ok(m.content) && m.content.indexOf('blob:') !== 0) return b64({ kind: 'direct video', url: m.content });
    } catch (e) { /* ignore */ }
    try {
      const ents = performance.getEntries() || [];
      for (let k = ents.length - 1; k >= 0; k--) {
        const u = ents[k].name || '';
        if (/(\.mp4([?#]|$)|\.m3u8([?#]|$)|\.mpd([?#]|$)|videoplayback|googlevideo)/.test(u)) {
          return b64({ kind: 'media stream', url: u });
        }
      }
    } catch (e) { /* ignore */ }
    return '';
  };

  // FB chat-dock fit. Runs directly in the CONTENT-SCRIPT world, NOT via a
  // page <script> element: facebook.com ships a strict CSP that BLOCKS inline
  // page scripts, so the page-scope injection silently never ran — that was
  // the "still cut off on the left edge" / call-buttons-not-clickable
  // regression (WebView's evaluateJavascript ignored CSP, which is why the old
  // auto-fit "worked"). Content scripts share the real DOM/CSSOM with the
  // page, so getComputedStyle + el.style.left from HERE clamps the desktop
  // chat dock/panel back to left:0 identically, without tripping CSP.
  const isFacebookPage = () => location.hostname.indexOf('facebook.com') !== -1;

  let fbEnabled = true;
  const fbClamped = [];

  const fbAlreadyClamped = (el) => fbClamped.indexOf(el) !== -1;

  const fbClamp = (el) => {
    try {
      if (fbAlreadyClamped(el)) return;
      el.style.left = '0px';
      fbClamped.push(el);
    } catch (e) { /* ignore */ }
  };

  const fitFb = () => {
    let n = 0;
    if (fbEnabled) {
      try {
        if (document.documentElement.scrollWidth > document.documentElement.clientWidth) {
          document.documentElement.style.overflowX = 'hidden';
        }
      } catch (e) { /* ignore */ }
      try {
        const vw = window.innerWidth || document.documentElement.clientWidth;
        const vh = window.innerHeight || document.documentElement.clientHeight;
        const all = document.querySelectorAll('div, aside, section, nav, form');
        for (let i = 0; i < all.length; i++) {
          const el = all[i];
          try {
            if (fbAlreadyClamped(el)) continue;
            const pos = window.getComputedStyle(el).position;
            if (pos !== 'fixed' && pos !== 'absolute') continue;
            const r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) continue;
            if (r.left > -4) continue;
            if (r.width > vw) continue;
            if (r.height > (vh * 0.9)) continue;
            if ((vh - r.bottom) < (vh * 0.35)) {
              fbClamp(el);
              n++;
            }
          } catch (e) { /* ignore */ }
        }
      } catch (e) { /* ignore */ }
    }
    return n;
  };

  // AUTO-FIT on facebook.com (mirrors the WebView web apps, which re-injected
  // the shim on every page): clamp at load and keep re-clamping as the SPA's
  // DOM moves. Safe for the mobile-mode in-app browser (nothing to clamp).
  if (isFacebookPage()) {
    const fbTick = () => {
      try { fitFb(); } catch (e) { /* ignore */ }
    };
    const fbStart = () => {
      fbTick();
      [1000, 2500, 5000, 10000].forEach((ms) => setTimeout(fbTick, ms));
      setInterval(fbTick, 3000);
    };
    if (document.readyState === 'complete') {
      fbStart();
    } else {
      document.addEventListener('DOMContentLoaded', fbTick, { once: true });
      window.addEventListener('load', fbStart, { once: true });
    }
  }

  const fitFacebook = () => {
    let ok = true;
    try {
      fitFb();
    } catch (e) {
      ok = false;
    }
    send({ type: 'fitResult', value: ok });
  };

  // Auto-scroll the focused input into view when it gains focus, mirroring the
  // WhatsApp extension (whatsapp/content.js). The host Activity pads the web
  // content down by the soft-keyboard height (imePadding); this makes sure the
  // focused field also scrolls into the shrunken visible viewport so the user
  // can see what they type — like in any native app. Runs in content-script
  // world, so it bypasses page CSP.
  document.addEventListener('focusin', function (ev) {
    const el = ev.target;
    if (!el || typeof el.scrollIntoView !== 'function') return;
    setTimeout(function () {
      try {
        el.scrollIntoView({ block: 'center', behavior: 'auto' });
      } catch (e) { /* ignore */ }
    }, 350);
  }, true);

  port.onMessage.addListener((msg) => {
    if (!msg || !msg.cmd) return;
    switch (msg.cmd) {
      case 'detectVideo':
        send({ type: 'detectVideoResult', value: detectVideo() });
        break;
      case 'fit':
        if (isFacebookPage()) fitFacebook();
        else send({ type: 'fitResult', value: false });
        break;
    }
  });

  send({ type: 'connected', url: location.href });
})();