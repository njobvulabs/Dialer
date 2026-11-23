package com.example.util

/**
 * Viewport telemetry for the WhatsApp "menu opens at top-left" bug.
 *
 * When a page inside a scaled WebView computes popup menus with position:fixed,
 * their anchor coordinates come from the layout viewport (~980px) while the page
 * is painted into a scaled visual viewport (~412px), so menus collapse to the
 * top-left. This script reports the real geometry through the __igAndroid JS
 * bridge so the correct fix (force scale=1 / move to a matched coordinate space)
 * can be picked from data instead of guesswork.
 */
object ViewportDiagnostics {
    const val JS: String = """
        (function() {
            if (window.__vpDiagActive__) return;
            window.__vpDiagActive__ = true;
            function report(m) {
                try { if (window.__igAndroid) window.__igAndroid.report(m); } catch (e) {}
            }
            function meta() {
                var m = document.querySelector('meta[name=viewport]');
                return m ? (m.getAttribute('content') || 'empty') : 'none';
            }
            function vstats() {

                var vv = window.visualViewport || null;
                return 'innerW=' + window.innerWidth +
                    ' clientW=' + document.documentElement.clientWidth +
                    ' scrollW=' + document.documentElement.scrollWidth +
                    ' vvScale=' + (vv ? vv.scale : 'n/a') +
                    ' vvOffL=' + (vv ? vv.offsetLeft : 'n/a') +
                    ' dpr=' + (window.devicePixelRatio || 1) +
                    ' meta=' + meta();
            }
            function rect(el) {
                if (!el) return 'none';
                var r = el.getBoundingClientRect();
                var id = el.getAttribute('data-testid') || '';
                return el.tagName + (id ? '[' + id + ']' : '') +
                    ' L' + Math.round(r.left) + ' T' + Math.round(r.top) +
                    ' R' + Math.round(r.right) + ' B' + Math.round(r.bottom);
            }
            function menuRects() {
                var out = [];
                var sels = '[role=menu],[role=dialog],[role=listbox],[role=menuitem],[aria-haspopup=true],[data-testid*=menu],[data-testid*=popup],[data-testid*=context]';
                var all = document.querySelectorAll(sels);
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    var r = el.getBoundingClientRect();
                    if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.left > -5000) {
                        out.push(rect(el));
                    }
                }
                return out.length ? out.join(' | ') : 'no-menu-el';
            }
            function data() {
                return 'vv: ' + vstats() + ' | menu: ' + menuRects();
            }
            report('vpdiag load ' + vstats());
            document.addEventListener('touchend', function(e) {
                var target = window.__vpLastTarget || e.target;
                window.__vpLastTarget = null;
                setTimeout(function() {
                    report('vpdiag tap target=' + rect(target) + ' | ' + data());
                }, 300);
            }, true);
            document.addEventListener('touchstart', function(e) {
                window.__vpLastTarget = e.target;
            }, true);
        })();
    """
}