package com.example.util

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Shared PWA (Progressive Web App) asset generators used by the local HTTP
 * servers (Wi-Fi Transfer, Media Server, FTP Web UI). Produces the web app
 * manifest, a service worker, PNG icons, and the <head> markup + install
 * button script needed for an installable app on localhost / LAN IPs.
 */
object PwaAssets {

    fun manifest(name: String, shortName: String, theme: String, bg: String): String =
        """
        {
          "name": "$name",
          "short_name": "$shortName",
          "start_url": "/",
          "scope": "/",
          "display": "standalone",
          "orientation": "any",
          "background_color": "$bg",
          "theme_color": "$theme",

          "description": "$name - served from your phone over the local network",
          "icons": [
            { "src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
            { "src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" },
            { "src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "maskable" },
            { "src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
          ]
        }
        """.trimIndent()

    fun swJs(cacheName: String): String =
        """
        const CACHE = '$cacheName';
        const CORE = ['/', '/manifest.json', '/icons/icon-192.png', '/icons/icon-512.png'];
        self.addEventListener('install', (e) => {
          e.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE)));
          self.skipWaiting();
        });
        self.addEventListener('activate', (e) => {
          e.waitUntil(
            caches.keys().then((keys) =>
              Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
            )
          );
          self.clients.claim();
        });
        self.addEventListener('fetch', (e) => {
          const req = e.request;
          if (req.method !== 'GET') return;
          const url = new URL(req.url);
          if (url.pathname.startsWith('/api/') || url.pathname === '/upload') return;
          e.respondWith(
            caches.match(req).then((hit) =>
              hit || fetch(req).then((res) => {
                if (res.ok && res.type === 'basic') {
                  const copy = res.clone();
                  caches.open(CACHE).then((c) => c.put(req, copy));
                }
                return res;
              })
            )
          );
        });
        """.trimIndent()

    /**
     * Generates a simple flat-colour rounded-square PNG icon using pure Kotlin
     * (RGB pixel buffer + raw DEFLATE) so no native bitmap tools are needed.
     * [size] in px, [bg] and [fg] as 0xRRGGBB ints.
     */
    fun pngIcon(size: Int, bg: Int, fg: Int): ByteArray {
        val pixels = IntArray(size * size)
        val radius = size * 0.22f
        val cx = size / 2f
        val cy = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dist = kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
                val dx = (x - cx)
                val dy = (y - cy)
                val edge = if (dx >= 0) (dy.toFloat() / (dx + 1f)) else Float.MAX_VALUE
                val inPlay = dx > 0 && dx < size * 0.4f && Math.abs(dy.toFloat()) < size * 0.22f
                val inBar = dy > 0 && dy < size * 0.4f && Math.abs(dx.toFloat()) < size * 0.22f
                val inDot = (dist < size * 0.14f) && (x > cx) && (y > cy)
                val corner = if (dx >= 0 && dy >= 0) Math.abs(edge - 1f) < 0.15f else false
                val accent = inPlay || inBar || inDot || corner
                val inside = dist < size / 2f - radius + radius * 0.4f
                val color = if (accent) fg else if (inside) bg else 0xFF000000.toInt()
                pixels[y * size + x] = color
            }
        }
        return encodePng(size, pixels)
    }

    private fun encodePng(size: Int, pixels: IntArray): ByteArray {
        val stride = size * 4 + 1
        val raw = ByteArray(stride * size)
        for (y in 0 until size) {
            val rowStart = y * stride
            raw[rowStart] = 0 // filter: None
            for (x in 0 until size) {
                val argb = pixels[y * size + x]
                val i = rowStart + 1 + x * 4
                raw[i] = ((argb shr 16) and 0xFF).toByte()
                raw[i + 1] = ((argb shr 8) and 0xFF).toByte()
                raw[i + 2] = (argb and 0xFF).toByte()
                raw[i + 3] = 0xFF.toByte()
            }
        }

        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            compressed.write(buf, 0, n)
        }
        deflater.end()

        val ihdr = ByteArrayOutputStream().also { s ->
            writeInt(s, size); writeInt(s, size)
            s.write(8); s.write(6); s.write(0); s.write(0); s.write(0) // 8-bit RGBA
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", ihdr.toByteArray())
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /** <head> additions: meta theme-color, apple meta tags, manifest link, service worker registration. */
    fun pwaHead(name: String, theme: String, bg: String = theme, accent: String = theme): String =
        """
        <meta name="theme-color" content="$theme">
        <meta name="mobile-web-app-capable" content="yes">
        <meta name="apple-mobile-web-app-capable" content="yes">
        <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
        <meta name="apple-mobile-web-app-title" content="$name">
        <link rel="manifest" href="/manifest.json">
        <link rel="icon" type="image/png" sizes="192x192" href="/icons/icon-192.png">
        <link rel="apple-touch-icon" sizes="192x192" href="/icons/icon-192.png">
        <link rel="apple-touch-icon" sizes="512x512" href="/icons/icon-512.png">
        <style>
          body { -webkit-tap-highlight-color: transparent; }
          .install-btn {
            display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
            border: 1px solid ${accent}66; background: ${accent}22; color: #fff;
            border-radius: 10px; padding: 8px 14px; font-weight: 600; font-size: 0.85rem;
          }
          .install-btn:hover { background: ${accent}44; }
          @media (display-mode: standalone) { .install-btn { display: none !important; } }
        </style>
        """.trimIndent()

    /**
     * Service-worker registration + "Install App" button logic (before-installprompt).
     * Returns the JS body only (no <script> tags) so pages can embed it inside their
     * own <script> block without terminating it prematurely.
     */
    fun pwaScript(): String =
        """
        (function () {
          if ('serviceWorker' in navigator) {
            window.addEventListener('load', function () {
              navigator.serviceWorker.register('/sw.js').catch(function () {});
            });
          }
          var deferredPrompt = null;
          var installBtn = document.getElementById('installApp');
          window.addEventListener('beforeinstallprompt', function (e) {
            e.preventDefault();
            deferredPrompt = e;
            if (installBtn) installBtn.style.display = 'inline-flex';
          });
          if (installBtn) {
            installBtn.addEventListener('click', function () {
              if (!deferredPrompt) {
                showToast('Use your browser menu > "Add to Home screen" (or Chrome "Install app")');
                return;
              }
              deferredPrompt.prompt();
              deferredPrompt.userChoice.then(function (r) {
                if (r.outcome === 'accepted') {
                  showToast('App installed!');
                }
                deferredPrompt = null;
                installBtn.style.display = 'none';
              });
            });
          }
          window.addEventListener('appinstalled', function () {
            if (installBtn) installBtn.style.display = 'none';
          });
        })();
        """.trimIndent()

    /** Small toast helper shared by PWA UI pages. */
    fun toastJs(): String =
        """
        function showToast(msg) {
          var el = document.getElementById('toast');
          if (!el) {
            el = document.createElement('div');
            el.id = 'toast';
            el.style.cssText = 'position:fixed;left:50%;bottom:24px;transform:translateX(-50%);' +
              'background:#0f172a;color:#fff;padding:10px 18px;border-radius:10px;font-size:0.85rem;' +
              'z-index:9999;box-shadow:0 4px 20px rgba(0,0,0,.4);border:1px solid #334155;max-width:90%;';
            document.body.appendChild(el);
          }
          el.textContent = msg;
          el.style.display = 'block';
          clearTimeout(el._t);
          el._t = setTimeout(function () { el.style.display = 'none'; }, 3000);
        }
        """.trimIndent()
}