package com.example.util

/**
 * Makes an Android WebView look like a real desktop Chrome to Google's sign-in
 * JS. Android WebView leaks its true identity via client hints
 * (navigator.userAgentData reports the "Android WebView" brand + mobile
 * platform) even after the UA string itself is overridden, which triggers
 * Google's "This browser or app may not be secure" block on OAuth. This shim is
 * injected on every WebView web-app page (see WebAppScreen.onPageFinished) so
 * Google Identity Services sees the same desktop-Chrome fingerprint the UA
 * string claims. Keep the version tokens in line with WhatsAppCallEnabler.DESKTOP_UA.
 */
object ChromeFingerprint {
    private const val CHROME_VERSION = "160.0.0.0"
    private const val CHROME_MAJOR = "160"
    private const val NOT_A_BRAND = "99.0.0.0"

    private val brands = "{\"brand\":\"Not/A)Brand\",\"version\":\"$NOT_A_BRAND\"}," +
        "{\"brand\":\"Chromium\",\"version\":\"$CHROME_MAJOR\"}," +
        "{\"brand\":\"Google Chrome\",\"version\":\"$CHROME_MAJOR\"}"

    val JS = """(function(){
if (window.__chromeFingerprint) return; window.__chromeFingerprint = true;
try { Object.defineProperty(Navigator.prototype,"userAgentData",{configurable:true,get:function(){
  var b=$brands;

  return {brands:b,mobile:false,platform:"Windows",
    getHighEntropyValues:function(){return Promise.resolve({architecture:"x86",bitness:"64",model:"",mobile:false,platform:"Windows",platformVersion:"15.0.0",uaFullVersion:"$CHROME_VERSION",fullVersionList:b,wow64:false});}};
}});}catch(e){}
try { Object.defineProperty(Navigator.prototype,"platform",{configurable:true,get:function(){return "Win32";}});}catch(e){}
try { Object.defineProperty(Navigator.prototype,"vendor",{configurable:true,get:function(){return "Google Inc.";}});}catch(e){}
try { Object.defineProperty(Navigator.prototype,"webdriver",{configurable:true,get:function(){return undefined;}});}catch(e){}
try { window.chrome=window.chrome||{runtime:{},loadTimes:function(){return{}}};}catch(e){}
try { window.outerWidth=window.outerWidth||window.innerWidth; window.outerHeight=window.outerHeight||window.innerHeight;}catch(e){}
})();"""
}