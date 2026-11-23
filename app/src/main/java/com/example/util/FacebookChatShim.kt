package com.example.util

/**
 * Makes Facebook's desktop layout fully visible on the phone-width WebView
 * without shifting the whole page.
 *
 * Facebook's desktop chat dock and expanded chat panel are anchored to the
 * bottom-left corner, so on a phone-width viewport their left edge sticks out
 * past the screen. Instead of translating the entire document (which the old
 * approach did), this clamps only the individual panels that are actually
 * clipped past the left edge back to `left: 0` and hides horizontal overflow.
 * Nothing moves unless it is off-screen, and no toast is shown.
 *
 * `window.__igFbShim` exposes `fit()` (re-run the clamp), `toggle()` (enable /
 * disable automatic clamping) and `state()` for the top-bar button in
 * WebAppActivity.
 */
object FacebookChatShim {
    const val ENABLED = true
    const val HOST_MARKER = "facebook.com"

    val JS: String = """
        (function() {
            if (window.__fbChatShim__) return;
            window.__fbChatShim__ = true;


            var enabled = true;
            var fixedCount = 0;
            var clamped = [];

            function alreadyClamped(el) {
                for (var i = 0; i < clamped.length; i++) if (clamped[i] === el) return true;
                return false;
            }

            function clampPanel(el) {
                try {
                    if (alreadyClamped(el)) return;
                    el.style.left = '0px';
                    clamped.push(el);
                    fixedCount++;
                } catch (e) {}
            }

            function fit() {
                if (!enabled) return fixedCount;
                // Only hide horizontal overflow when the page actually has it,
                // and never touch body — that can lock vertical scrolling.
                try {
                    if (document.documentElement.scrollWidth > document.documentElement.clientWidth) {
                        document.documentElement.style.overflowX = 'hidden';
                    }
                } catch (e) {}
                try {
                    var vw = window.innerWidth || document.documentElement.clientWidth;
                    var vh = window.innerHeight || document.documentElement.clientHeight;
                    var all = document.querySelectorAll('div, aside, section, nav, form');
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        try {
                            if (alreadyClamped(el)) continue;
                            // Cheap filter first: only positioned panels.
                            var pos = window.getComputedStyle(el).position;
                            if (pos !== 'fixed' && pos !== 'absolute') continue;
                            var r = el.getBoundingClientRect();
                            if (r.width <= 0 || r.height <= 0) continue;
                            if (r.left > -4) continue;
                            // Only panels narrower than the screen.
                            if (r.width > vw) continue;
                            // Never touch full-height containers (scroll columns).
                            if (r.height > (vh * 0.9)) continue;
                            // Only bottom-anchored panels (chat dock / chat panel).
                            var bottomish = (vh - r.bottom) < (vh * 0.35);
                            if (bottomish) clampPanel(el);
                        } catch (e) {}
                    }
                } catch (e) {}
                return fixedCount;
            }

            function toggle() {
                enabled = !enabled;
                return enabled;
            }
            function state() { return enabled; }

            window.__igFbShim = { fit: fit, toggle: toggle, state: state };

            function start() {
                fit();
                [1000, 2500, 5000, 10000].forEach(function(ms) {
                    setTimeout(function() { try { fit(); } catch (e) {} }, ms);
                });
                // Throttled re-scan instead of a MutationObserver on FB's
                // constantly-changing DOM (which froze scrolling).
                setInterval(function() { try { fit(); } catch (e) {} }, 3000);
            }
            if (document.readyState === 'complete') start();
            else window.addEventListener('load', start);
        })();
    """
}
