package com.example.util

import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Makes WhatsApp Web treat this WebView as a supported desktop browser so
 * voice/video calls are enabled.
 *
 * WhatsApp gates the call button on the User-Agent platform string: a Linux
 * flavoured UA produces the "Your browser doesn't support calling" warning,
 * while a Windows Chrome UA is accepted (see karere issue #122). On Android
 * WebView the UA string change also fixes navigator.userAgent, but
 * navigator.userAgentData still exposes the real Android client hints, so the
 * main document is rewritten to spoof those APIs (plus the WAWebABProps
 * calling experiment flags) before WhatsApp's bundle runs.
 */
object WhatsAppCallEnabler {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/160.0.0.0 Safari/537.36"


    /**
     * UA for the GeckoView engine (WhatsApp on GeckoView). GeckoView is a real
     * Firefox engine, so a desktop Firefox UA makes WhatsApp take the natively
     * supported Firefox calling path instead of the Chromium path that the
     * Chrome UA spoof forces (which can silently bail on the call button).
     */
    const val DESKTOP_FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private const val SCRIPT = """(function(){
if (window.__waCallEnabler) return; window.__waCallEnabler = true;
try { Object.defineProperty(Navigator.prototype,"userAgentData",{configurable:true,get:function(){
  var b=[{brand:"Not/A)Brand",version:"99"},{brand:"Chromium",version:"160"},{brand:"Google Chrome",version:"160"}];
  return {brands:b,mobile:false,platform:"Windows",
    getHighEntropyValues:function(){return Promise.resolve({architecture:"x86",bitness:"64",model:"",mobile:false,platform:"Windows",platformVersion:"15.0.0",uaFullVersion:"160.0.0.0",fullVersionList:b,wow64:false});}};
}});}catch(e){}
try { Object.defineProperty(Navigator.prototype,"platform",{configurable:true,get:function(){return "Win32";}});}catch(e){}
try { Object.defineProperty(Navigator.prototype,"vendor",{configurable:true,get:function(){return "Google Inc.";}});}catch(e){}
try { window.chrome=window.chrome||{runtime:{}};}catch(e){}
setInterval(function(){try{
  var m=window.require&&window.require("WAWebABProps");
  if(m&&m.getABPropConfigValue&&!m.getABPropConfigValue.__waHooked){
    var orig=m.getABPropConfigValue;
    var w=function(){var k=arguments[0];
      switch(k){
        case "enable_web_calling":
        case "enable_web_group_calling":
        case "web_voip_call_tab_new_call": return true;
        case "calling_lid_version": return 1;
        case "heartbeat_interval_s": return 5;
        default: return orig.apply(this,arguments);
      }};
    w.__waHooked=true;
    m.getABPropConfigValue=w;
  }
  var g=window.require&&window.require("WAWebVoipGatingUtils");
  if(g&&!g.__igGated){
    try{
      ["isUnsupportedBrowserForWebCalling","getUnsupportedBrowserReason","isVoipDownloadEnabled"].forEach(function(name){
        if(typeof g[name]==='function'&&!g[name].__igGated){
          var val=(name==="isUnsupportedBrowserForWebCalling")?false:(name==="isVoipDownloadEnabled")?true:null;
          var f=function(){return val;};
          f.__igGated=true;
          g[name]=f;
        }
      });
      g.__igGated=true;
    }catch(e){}
  }
}catch(e){}},500);
})();"""

    /**
     * WhatsApp's service worker serves the app shell from cache on relaunch,
     * which bypasses shouldInterceptRequest, so the document-start spoof never
     * runs. Run this once per session: if the enabler is not present, unregister
     * the service workers and reload so the main document is fetched over the
     * network and the script gets injected.
     */
    const val RELOAD_IF_NOT_INJECTED_JS = """(function(){
if (window.__waCallEnabler) return;
var done=false;
function reload(){ if(!done){ done=true; try{ location.reload(); }catch(e){} } }
try {
  if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
    navigator.serviceWorker.getRegistrations().then(function(rs){
      return Promise.all(rs.map(function(r){ return r.unregister(); }));
    }).then(reload, reload);
  } else { reload(); }
} catch(e) { reload(); }
})();"""

    /** Reports what WhatsApp sees (call gating inputs) as a LONG toast at ~2.5s,
     * repeating every 8s (a few times) and whenever the tab regains focus, so it
     * can't be missed. */
    const val DIAG_JS = """(function(){
function rep(m){ try { if (window.__igAndroid && window.__igAndroid.reportLong) window.__igAndroid.reportLong(m); else if (window.__igAndroid) window.__igAndroid.report(m); } catch(e){} }
function req(name){ try { return window.require ? window.require(name) : null; } catch(e){ return null; } }
var n = 0;
function run(){
  try {
    var parts = [];
    parts.push('en=' + (window.__waCallEnabler ? 1 : 0));
    parts.push('ua=' + navigator.userAgent.slice(0,42));
    parts.push('plat=' + (navigator.platform || '?'));
    var uad = null; try { uad = navigator.userAgentData; } catch(e){}
    if (uad) { parts.push('uad=' + uad.platform + '/' + (uad.mobile ? 'm' : 'd')); }
    else { parts.push('uad=none'); }
    parts.push('rtc=' + (typeof RTCPeerConnection));
    var g = req('WAWebVoipGatingUtils');
    if (g) {
      var unsup = (typeof g.isUnsupportedBrowserForWebCalling === 'function') ? g.isUnsupportedBrowserForWebCalling() : '?';
      var reason = (typeof g.getUnsupportedBrowserReason === 'function') ? g.getUnsupportedBrowserReason() : '?';
      var call = (typeof g.isCallingEnabled === 'function') ? g.isCallingEnabled() : '?';
      parts.push('unsup=' + unsup + ' reason=' + reason + ' calling=' + call);
    } else { parts.push('gating=missing'); }
    var ab = req('WAWebABProps');
    if (ab && ab.getABPropConfigValue) { parts.push('ab_call=' + ab.getABPropConfigValue('enable_web_calling')); }
    rep('callDiag: ' + parts.join(' | '));
  } catch(e) {
    rep('callDiag: ERR ' + e.message);
  }
}
setTimeout(run, 2500);
var iv = setInterval(function(){ n++; if (n >= 5) { clearInterval(iv); return; } run(); }, 8000);
try { document.addEventListener('visibilitychange', function(){ if (document.visibilityState === 'visible') { n = 0; run(); } }); } catch(e){}
})();"""

    /**
     * Wraps RTCPeerConnection to toast its lifecycle during a call attempt so we
     * can see whether the engine creates a peer connection and whether ICE
     * candidates actually flow (a known Android 11+/Samsung WebView bug leaves
     * ICE gathering stuck, which fails calls exactly like this).
     */
    const val RTC_DIAG_JS = """(function(){
if (window.__igRtcHooked) return; window.__igRtcHooked = true;
function rep(m){ try { if (window.__igAndroid && window.__igAndroid.reportLong) window.__igAndroid.reportLong(m); else if (window.__igAndroid) window.__igAndroid.report(m); } catch(e){} }
function wrap(name){
  try {
    var C = window[name];
    if (typeof C !== 'function' || C.__igHooked) return;
    var W = function(cfg){
      rep('RTC created');
      var pc;
      try { pc = new C(cfg); } catch(e){ rep('RTC ctor ERR ' + e.message); throw e; }
      if (pc) {
        try {
          var cand = 0;
          pc.addEventListener('icecandidate', function(e){ if (e.candidate) cand++; });
          pc.addEventListener('icecandidateerror', function(e){ rep('RTC iceerr code=' + (e.errorCode) + ' addr=' + (e.address||'?')); });
          pc.addEventListener('icegatheringstatechange', function(){ rep('RTC icegather=' + pc.iceGatheringState + ' cand=' + cand); });
          pc.addEventListener('iceconnectionstatechange', function(){ rep('RTC iceconn=' + pc.iceConnectionState); });
          pc.addEventListener('connectionstatechange', function(){ rep('RTC conn=' + pc.connectionState); });
          pc.addEventListener('signalingstatechange', function(){ rep('RTC sig=' + pc.signalingState); });
          var sld = pc.setLocalDescription.bind(pc);
          pc.setLocalDescription = function(d){ rep('RTC setLocal ' + (d && d.type) + ' cand=' + cand); return sld.apply(pc, arguments); };
          var srd = pc.setRemoteDescription.bind(pc);
          pc.setRemoteDescription = function(d){ rep('RTC setRemote ' + (d && d.type) + ' cand=' + cand); return srd.apply(pc, arguments); };
        } catch(e){}
      }
      return pc;
    };
    W.__igHooked = true;
    window[name] = W;
  } catch(e){}
}
wrap('RTCPeerConnection');
wrap('webkitRTCPeerConnection');
})();"""

    /**
     * Creates a throwaway RTCPeerConnection against a public STUN server right
     * after load and lets it gather for ~3s. This warms up the WebView's ICE
     * stack (a common fix for "first call sticks on calling") and doubles as a
     * diagnostic: if the warm-up gathers zero candidates, this WebView/device
     * cannot do WebRTC ICE at all.
     */
    const val ICE_WARM_JS = """(function(){
if (window.__igIceWarmed) return; window.__igIceWarmed = true;
function rep(m){ try { if (window.__igAndroid && window.__igAndroid.reportLong) window.__igAndroid.reportLong(m); else if (window.__igAndroid) window.__igAndroid.report(m); } catch(e){} }
var attempts = 0;
function warm(){
  attempts++;
  try {
    var C = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (typeof C !== 'function') { if (attempts < 4) setTimeout(warm, 2500); return; }
    var pc = new C({ iceServers: [{ urls: ['stun:stun.l.google.com:19302'] }] });
    var cand = 0;
    var finished = false;
    var finish = function(){
      if (finished) return; finished = true;
      rep('ICEwarm state=' + pc.iceGatheringState + ' cand=' + cand + ' attempt=' + attempts);
      try { pc.close(); } catch(e){}
      if (cand === 0 && attempts < 4) setTimeout(warm, 2500);
    };
    pc.addEventListener('icecandidate', function(e){ if (e.candidate) cand++; });
    pc.addEventListener('icecandidateerror', function(e){ rep('ICEwarm err code=' + (e.errorCode) + ' addr=' + (e.address||'?')); });
    pc.addEventListener('icegatheringstatechange', function(){ if (pc.iceGatheringState === 'complete') finish(); });
    // A bare RTCPeerConnection does NOT gather; gathering only starts once an
    // offer is set locally. Create an offer so candidates actually flow.
    try {
      pc.createDataChannel('warm');
      pc.createOffer().then(function(o){ return pc.setLocalDescription(o); }).catch(function(e){ rep('ICEwarm offer ERR ' + e.message); });
    } catch(e) { rep('ICEwarm create ERR ' + e.message); }
    setTimeout(finish, 6000);
  } catch(e) {
    if (attempts < 4) setTimeout(warm, 2500);
  }
}
setTimeout(warm, 1200);
})();"""

    /**
     * The script to run after a page load. Idempotent per document: the
     * window.__waCallEnabler marker makes re-injection a no-op. Covers loads
     * served by WhatsApp's service worker, which bypass shouldInterceptRequest.
     */
    val INJECT_JS: String get() = SCRIPT

    private val HEAD_OPEN = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    private val CSP_META = Regex(
        "<meta[^>]*http-equiv=[\"']Content-Security-Policy[\"'][^>]*>",
        RegexOption.IGNORE_CASE
    )

    /** Injects the spoof script at the start of <head>; drops any CSP meta. */
    fun rewriteHtml(html: String): String {
        val out = CSP_META.replace(html, "")
        val headMatch = HEAD_OPEN.find(out) ?: return html
        val injectAt = headMatch.range.last + 1
        return out.substring(0, injectAt) +
            "<script>" + SCRIPT + "</script>" +
            out.substring(injectAt)
    }

    /**
     * Fetches the main-frame whatsapp.com document and returns a rewritten
     * WebResourceResponse with the spoof script injected and the CSP header
     * removed. Returns null (so the WebView falls through to the network) for
     * anything that isn't a whatsapp HTML document, or on any failure.
     */
    fun maybeInject(url: String?, isMainFrame: Boolean, userAgent: String?): WebResourceResponse? {
        if (!isMainFrame || url == null) return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        if (!url.contains("whatsapp.com", ignoreCase = true)) return null
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (cookies != null) conn.setRequestProperty("Cookie", cookies)
            conn.connect()
            for ((key, values) in conn.headerFields) {
                if (key != null && key.equals("set-cookie", ignoreCase = true)) {
                    for (v in values) runCatching { CookieManager.getInstance().setCookie(url, v) }
                }
            }
            val ctype = conn.contentType ?: ""
            if (!ctype.contains("text/html", ignoreCase = true) &&
                !ctype.contains("application/xhtml", ignoreCase = true)
            ) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            val headers = conn.headerFields
                .filterKeys { k ->
                    k != null &&
                        !k.equals("Content-Length", true) &&
                        !k.equals("Content-Encoding", true) &&
                        !k.equals("Content-Security-Policy", true) &&
                        !k.equals("Set-Cookie", true)
                }
                .mapNotNull { (k, v) -> k?.let { it to v.joinToString("\n") } }
                .toMap()
            conn.disconnect()
            val html = String(bytes, StandardCharsets.UTF_8)
            WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream(rewriteHtml(html).toByteArray(StandardCharsets.UTF_8))
            )
        }.getOrNull()
    }
}
