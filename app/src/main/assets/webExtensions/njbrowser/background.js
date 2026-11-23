/*
 * nj-browser-controls background relay.
 *
 * Content scripts CANNOT open a native port on GeckoView (native messaging
 * from content scripts is broken/unreliable, see mozilla/geckoview#220), so
 * this background page opens one `runtime.connectNative` port PER content
 * script port and pumps messages between them:
 *
 *   content script <-> background (runtime.connect / port)
 *   background    <-> app        (runtime.connectNative / port)
 *
 * For every content port we first tell the app where this side lives (the
 * page URL) so the app can associate the port with the right GeckoSession.
 */
(() => {
  let seq = 0;

  const openNative = (contentPort, url) => {
    let nativePort = null;
    try {
      nativePort = browser.runtime.connectNative('njbrowser');
    } catch (e) {
      return;
    }
    if (!nativePort) return;

    nativePort.onMessage.addListener((m) => {
      try { contentPort.postMessage(m); } catch (e) { /* ignore */ }
    });
    nativePort.onDisconnect.addListener(() => {
      try { contentPort.disconnect(); } catch (e) { /* ignore */ }
    });
    contentPort.onMessage.addListener((m) => {
      try { nativePort.postMessage(m); } catch (e) { /* ignore */ }
    });
    contentPort.onDisconnect.addListener(() => {
      try { nativePort.disconnect(); } catch (e) { /* ignore */ }
    });

    // Introduce this side to the app BEFORE relaying anything so the app can
    // bind the native port to the matching session right at connect time.
    try {
      nativePort.postMessage({ type: 'ident', url: url || '', tag: String(++seq) });
    } catch (e) { /* ignore */ }
  };

  browser.runtime.onConnect.addListener((contentPort) => {
    let url = '';
    try {
      const s = contentPort.sender || {};
      url = (s.tab && s.tab.url) || s.url || '';
    } catch (e) { /* ignore */ }
    openNative(contentPort, url);
  });
})();