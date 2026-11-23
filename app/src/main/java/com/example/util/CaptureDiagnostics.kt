package com.example.util

/**
 * Injects a wrapper around navigator.mediaDevices.getUserMedia so the app can
 * see (via __igAndroid.report toasts) whether the page actually gets a media
 * stream after the WebView permission request is granted, or fails and why.
 */
object CaptureDiagnostics {
    const val JS = """;
(function() {
  if (window.__igCaptureHooked) return;
  window.__igCaptureHooked = true;
  function report(m) { try { if (window.__igAndroid) window.__igAndroid.report(m); } catch (e) {} }
  try {
    var md = navigator.mediaDevices;
    if (!md || !md.getUserMedia) { report('capture: no mediaDevices'); return; }
    var orig = md.getUserMedia.bind(md);
    md.getUserMedia = function(constraints) {
      var desc = [];
      if (constraints && constraints.audio) desc.push('audio');
      if (constraints && constraints.video) desc.push('video');
      report('gUM asked [' + desc.join(',') + ']');
      return orig(constraints).then(function(stream) {
        var kinds = stream.getTracks().map(function(t){ return t.kind; }).join(',');
        var audioReady = stream.getAudioTracks().length > 0

          ? stream.getAudioTracks()[0].readyState : 'none';
        report('gUM OK tracks={' + kinds + '} audioReady=' + audioReady);
        return stream;
      }, function(err) {
        report('gUM ERR ' + err.name + ': ' + (err.message || ''));
        throw err;
      });
    };
  } catch (e) {
    report('capture: hook failed: ' + e.message);
  }
})();
"""
}