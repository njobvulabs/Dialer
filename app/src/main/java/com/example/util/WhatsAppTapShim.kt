package com.example.util

/**
 * Experimental tap shim for WhatsApp Web in WebView.
 *
 * WhatsApp's desktop UI is mouse-first: many controls (attach "+" button, message
 * "down-arrow" menu, etc.) rely on real click events. In a WebView, touch is
 * emulated as mouse events and some controls lose the click, so taps appear to
 * "do nothing." This script, injected only on whatsapp.com pages, uses a
 * no-double-firing fallback: when a touch lands on an interactive element and the
 * browser does NOT deliver a real click within ~140 ms, it dispatches a synthetic
 * mousedown/mouseup/click itself. If the browser's real click arrives first, the
 * fallback is cancelled.
 *
 * Call controls (voice/video call buttons) are special-cased: WhatsApp rejects
 * synthetic (untrusted) clicks for them. Instead the shim focuses the call
 * control and asks the host (WebAppJsBridge.activate) to send a real ENTER key
 * event, which Chromium turns into a trusted click on the focused element. If the
 * control can't be focused, a richer synthetic sequence (pointer + mouse +
 * click, detail:1) is dispatched on the exact call element as a last resort.
 *
 * Disabled everywhere by setting [ENABLED] to false. Text-entry elements
 * (input/textarea/contenteditable) are deliberately excluded so caret/focus
 * behaviour isn't disturbed.
 */

object WhatsAppTapShim {
    const val ENABLED = true
    const val HOST_MARKER = "whatsapp.com"

    val JS: String = """
        (function() {
            if (window.__tapShimActive__) return;
            window.__tapShimActive__ = true;
            var pending = null;
            function testid(el) {
                try { return el.getAttribute('data-testid') || ''; } catch (err) { return ''; }
            }
            function aria(el) {
                try { return el.getAttribute('aria-label') || ''; } catch (err) { return ''; }
            }
            function interactive(el) {
                if (!el || !el.getAttribute) return false;
                var tag = el.tagName;
                var tid = testid(el);
                // WhatsApp attachment menu items are divs with data-testid="attach-*"
                if (tid.indexOf('attach-') === 0) return true;
                if (tag === 'BUTTON' || tag === 'A') return true;
                if (el.getAttribute('aria-haspopup') === 'true') return true;
                if (tid.indexOf('conversation') === 0 || tid === 'popup-controls') return true;
                var r = el.getAttribute('role');
                if (r) {
                    if (r === 'button' || r === 'menuitem' || r === 'menu' ||
                        r === 'listbox' || r === 'option' || r === 'menuitemcheckbox' ||
                        r === 'menuitemradio' || r === 'radio' || r === 'checkbox' ||
                        r === 'switch' || r === 'tab' || r === 'link') return true;
                }
                return false;
            }
            function describe(el) {
                if (!el) return 'none';
                var tid = testid(el);
                var a = aria(el);
                return el.tagName + (tid ? '[' + tid + ']' : '') +
                    (a ? '(' + a + ')' : '') +
                    ' role=' + (el.getAttribute && el.getAttribute('role') || 'none');
            }
            function nearest(el) {
                for (var n = el; n && n !== document; n = n.parentNode) {
                    if (interactive(n)) return n;
                }
                return null;
            }
            function isCallControl(el) {
                if (!el || !el.getAttribute) return false;
                var tid = testid(el);
                if (tid === 'voice-call' || tid === 'video-call') return true;
                if (tid.indexOf('call-') === 0) return true;
                var a = aria(el).toLowerCase();
                return /voice.{0,8}call|video.{0,8}call|audio.{0,8}call|chiamata|llamada|llamar|appel/.test(a);
            }
            function findCallControl(el) {
                for (var n = el; n && n !== document; n = n.parentNode) {
                    if (isCallControl(n)) return n;
                }
                return null;
            }
            function report(m) {
                try { if (window.__igAndroid) window.__igAndroid.report(m); } catch (err) {}
            }
            function fire(target, x, y) {
                try {
                    var opts = {bubbles: true, cancelable: true, view: window, clientX: x, clientY: y};
                    target.dispatchEvent(new MouseEvent('mousedown', opts));
                    target.dispatchEvent(new MouseEvent('mouseup', opts));
                    target.dispatchEvent(new MouseEvent('click', opts));
                    report('tap-shim fired: ' + describe(target));
                    // Don't let a synthetic tap leave the keyboard open on a stray focus.
                    var a = document.activeElement;
                    if (a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA')) {
                        try { a.blur(); } catch (err) {}
                    }
                } catch (err) {}
            }
            function fireRich(target, x, y) {
                try {
                    var opts = {bubbles: true, cancelable: true, view: window,
                        clientX: x, clientY: y, detail: 1, button: 0};
                    if (window.PointerEvent) {
                        var pd = {bubbles: true, cancelable: true, view: window,
                            clientX: x, clientY: y, detail: 1, button: 0,
                            pointerId: 1, pointerType: 'touch', isPrimary: true,
                            width: 1, height: 1, pressure: 0.5};
                        target.dispatchEvent(new PointerEvent('pointerdown', pd));
                        target.dispatchEvent(new PointerEvent('pointerup', pd));
                    }
                    target.dispatchEvent(new MouseEvent('mousedown', opts));
                    target.dispatchEvent(new MouseEvent('mouseup', opts));
                    target.dispatchEvent(new MouseEvent('click', opts));
                    report('tap-shim call-fallback fired: ' + describe(target));
                } catch (err) {}
            }
            // Call controls get a trusted activation (focus + real ENTER) instead
            // of a synthetic click, which WhatsApp ignores for calls.
            function tryCall(el, x, y) {
                var ctrl = findCallControl(el);
                if (!ctrl) return false;
                var focused = false;
                try {
                    ctrl.focus();
                    var active = document.activeElement;
                    if (active === ctrl || (ctrl.contains && ctrl.contains(active))) focused = true;
                } catch (err) {}
                if (focused && window.__igAndroid && window.__igAndroid.activate) {
                    window.__igAndroid.activate();
                    report('call-fix focus+enter: ' + describe(ctrl));
                } else {
                    report('call-fix focus-fail rich-synth: ' + describe(ctrl));
                    fireRich(ctrl, x, y);
                }
                return true;
            }
            document.addEventListener('touchend', function(e) {
                var el = e.target;
                var touch = (e.changedTouches && e.changedTouches[0]) || null;
                var x = touch ? touch.clientX : (e.clientX || 0);
                var y = touch ? touch.clientY : (e.clientY || 0);
                if (tryCall(el, x, y)) return;
                var t = nearest(el);
                if (!t) return;
                pending = {t: t, x: x, y: y};
                setTimeout(function() {
                    if (!pending) return;
                    var target = pending.t;
                    var cx = pending.x;
                    var cy = pending.y;
                    pending = null;
                    fire(target, cx, cy);
                }, 140);
            }, true);
            document.addEventListener('click', function(e) {
                var el = e.target;
                if (pending && el && el.getAttribute &&
                    (el === pending.t || pending.t.contains(el) || el.contains(pending.t))) {
                    pending = null;
                }
            }, true);
        })();
    """
}
