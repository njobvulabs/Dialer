# NJ-Dialer

NJ-Dialer is an all-in-one Android phone assistant built with Jetpack Compose. It combines a
launcher-style Home grid, a full phone dialer, messaging, media players, file tools, web apps,
reading apps, and offline emulators in one app.

The app opens on the **Home grid**, not a bottom dock. Features are grouped under
**Comms / Apps / Tools / Emulators / Web apps / System**. The app remembers the last screen,
tab, open app, and many scroll positions.

## Quick start

**Prerequisites:** Android Studio or system Gradle with Android SDK 37.

1. Open Android Studio.
2. Choose **Open** and select this project folder.
3. Let Android Studio install missing SDK components.
4. Run the app on an emulator or physical device.

There is no `gradlew` wrapper. On this machine:

```bash
gradle :app:assembleDebug --offline
gradle :app:testDebugUnitTest --offline
gradle :app:lintDebug --offline --no-configuration-cache
```

If system Gradle is broken:

```bash
/home/njobvu/gradle-9.7.0-full/bin/gradle :app:assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Build notes: APK is **arm64-v8a
only** and large because it includes GeckoView, yt-dlp, native emulator code, and a Debian rootfs.
`google-services.json` is not required; release builds need `KEYSTORE_PATH`, `STORE_PASSWORD`, and
`KEY_PASSWORD`.

## Home launcher

The Home grid is the main surface. It has search, Grid/List layouts, Auto/4/5/6 grid columns, a
live clock, pinned apps, recent apps, favourite contacts, and badges for missed calls and unread
texts. Long-press a tile to pin it. Search matches tile names, descriptions, and hidden keywords.

Launcher Settings controls layout, columns, tile size, labels, section headers, clock format,
wallpaper or slideshow, opacity/dim, extra pages, quick shade settings, and reset to defaults.
Wallpaper slideshows support up to 20 images, shuffle, and intervals from 15 seconds to 1 hour.

Wide/landscape screens use centred content to avoid stretched lists. Wallpapers, media, camera
previews, and viewers remain full-bleed. A lightweight frosted-glass style is used on launcher
cards, the clock/search header, summary chips, and now playing.

Swipe left for an optional **System Tools** page with phone settings, SIM, Wi-Fi, Bluetooth,
data, hotspot, display, rotation, app management, Play Store, Palm Store, and Phone Master. Back
returns to Home, and the page can be disabled in Launcher Settings.

### Quick shade

Pull down from the top edge on native NJ-Dialer screens. Pull from the **left half** for
Notifications, or the **right half** for Quick settings. Swipe up from the bottom handle or press
Back to close. Quick settings includes flashlight, auto-rotate, brightness, Wi-Fi/Bluetooth
status, and editable shortcuts. Separate Gecko/WhatsApp windows are not covered.

At the app root, Back asks whether to **Exit & stop everything** or **Keep running in background**.
The first stops keep-alive services and audio playback. The second just backgrounds the app. If
“Confirm before exiting” is off, Back is consumed silently and the Home gesture is used to leave.

## Phone, contacts, and messages

### Calling

The Phone screen has Recents, Contacts, and SMS tabs, reachable from Home tiles. It includes a
dialer, contact search, call history, favourites, blocked numbers, speed dials, SIM selection, and
an in-app call screen.

Call recording can be manual or automatic and saves M4A files to `Recordings/NJ-Dialer Calls`.
Caller announce speaks the caller's name. Screening can block private, international, or custom
prefix numbers, and first-time-caller silent mode can mute the ringer. Missed-call notifications
offer Call back / Message / Dismiss and optional reminders. Swipe right on a call to redial or
left to send a text.

Call history is grouped by person, so `+265 99...` and `099...` appear under one contact. Tap the
row to expand individual calls. Tapping the name opens the contact details panel. Each call can
be called, blocked, noted, or deleted. Deleting a group asks first and removes calls from both
NJ-Dialer and the phone's call log.

### Contacts and SMS

Device contacts with several numbers appear as one person. Expand a contact with the chevron to
see one action row per number, each with Call and Copy. Contact Details shows one block per
number with per-number message and call actions. Contact Tools offers duplicate scan/merge and
VCF backup/import.

SMS supports inbox, send, and receive across both SIM slots. Because NJ-Dialer is not the default
SMS app, sent messages are stored locally in Room and merged with the system inbox. Bubbles show
Sending / Sent / Delivered / Failed status. Search, contact names, unread badges, mark-as-read on
open, and a live inbox watcher are supported. Long-press a bubble for Copy / Forward / Delete, or
a conversation for Mark as read / Delete. Deleted conversations stay hidden until a newer message
arrives; Undo is available.

## Files and documents

The File Manager browses, views, copies, moves, renames, deletes, and multi-selects files. It can
browse internal storage and external SD cards. Folder rows show item counts and real sizes, and
Sort by Size works on folders. Copy/Move opens a folder picker, asks about name collisions, and
supports Cancel. Pasting defaults to **Keep both**, so files are not silently overwritten.

Text/code files open in the **Text Editor**, which supports tabs, drafts, encoding/BOM/newline
preservation, line numbers, find/replace, go-to-line, auto-indent, syntax highlighting, and word
wrap. Archives open in the **Archive Manager**, which extracts ZIP, TAR, GZ, TGZ, TBZ, and 7z,
including password-protected ZIP/7z. It can create ZIP/7z archives. RAR is recognised but cannot
be extracted by the bundled library.

Every user-facing file delete moves the file to the **Recycle Bin** instead of unlinking it. You
can restore, delete forever, or empty the bin. Undo appears immediately after a delete. If
trashing fails, the original file is left in place and a message is shown.

**Vault** is a PIN-protected encrypted file area. **Downloads** gathers files from app folders
such as Download, Pictures, Movies, Music, Notes, and Tasks. The **Document Reader** opens all
supported PDF, office, text, and ebook formats from the File Manager through the offline
OpenDocument renderer. Files can also be converted from video to M4A audio or handed to the
system Bluetooth sender.

## Media

The **Audio Player** has folder grouping, playlists, favourites, recent tracks, an up-next queue,
track metadata, five-band equalizer, presets, bass boost, L/R balance, speed, and sleep timer.
Shuffle works with repeat-all, repeat-off plays the queue to the end, and Previous walks back
through the shuffled order. Playback continues in a foreground service with a lock-screen/player
showing title, artist, album, artwork, position, scrubbing, and ±10 second seek. Audio ducks or
pauses for focus changes and pauses when headphones are unplugged.

The **Video Player** has folder grouping, search, playlists, subtitles, PiP, speed, brightness,
volume, screen lock, rotation, A-B loop, repeat, sleep timer, and resume position. Playlists are
built from the video's folder, including when opened from the File Manager. Sidecar `.srt`/`.vtt`
subtitles are preferred; text tracks inside MKV/MP4 also work. Bitmap subtitles such as
PGS/VobSub have no text overlay. Playback pauses when the app is backgrounded and does not
auto-resume. The player uses Media3 ExoPlayer with custom Compose controls.

The **Camera** has AI CAM, Portrait, Pro, Video, and Short Video modes. Beauty is built into AI
CAM and Portrait. Portrait blur uses on-device ML Kit segmentation, and vendor CameraX extensions
are used when available. Default photo aspect is FULL, using the full 4:3 sensor. HDR is off by
default, and unedited photos keep the camera's original JPEG. Controls include flash, timer,
filters, grid, EV, ISO/exposure, tap-to-focus, zoom, QR/barcode scanning, video quality, and
pausable video recording.

The **Gallery** groups photos by folder, with All Photos and Favorites, search, sort, slideshow,
multi-select, and a full-screen viewer with zoom/rotate/flip. Photos can be copied, moved,
favourited, edited, or deleted. The **Image Editor** supports crop, filters, brightness/contrast/
saturation, drawing, and text.

**Sound Recorder** saves voice memos to `Music/NJ-Dialer/Recordings` when All-files is granted;
it is temporarily mic-only. **Screen Recorder** records silent H.264 MP4 by default, with optional
microphone or system audio, quality levels, timer, pause/resume, and a recordings list. Output
goes to `Movies/NJ-Dialer/Screen Recordings`. **Internet Radio** streams Malawi stations through
ExoPlayer/Media3.

## Web browsing and web apps

The in-app browser uses GeckoView, the Firefox engine. Tabs keep URL/title when evicted and
reload on return; at most three tabs stay live. Switching tabs does not reload a live tab. Pinned
tabs, duplicate tab, crash recovery, reopen closed tab, close private tabs, and long-press
Back/Forward history trails are supported.

The New Tab page has custom tiles, top sites ranked by visits, and recent history. Search engines
include Google, DuckDuckGo, Bing, Brave, Startpage, Ecosia, Qwant, Yahoo, Yandex, Wikipedia,
Mojeek, SearXNG, and Custom. Bookmarks, history search, find-in-page, reader mode,
save-page-as-PDF, screenshots, desktop mode, dark websites, and tracking protection are included.
Site Info shows the connection verdict, certificate, tracker count, and stored permissions.
Per-site permissions remember geolocation, notifications, and storage by origin. Long-press links
or text for New Tab, Incognito, Copy Link/Text, Select All, and media-aware download actions.
Downloads go to `Download/NJ-Dialer`, with a full-screen Downloads manager.

Web apps also run through GeckoView. The hub includes WhatsApp 1, WhatsApp 2, Telegram, Facebook,
YouTube, Gemini, TikTok, Perplexity, ChatGPT, Claude, Grok, AI Studio, Classroom, Meet, Drive,
Docs, Gmail, Photos, Microsoft Copilot, DeepSeek, and Manus AI. WhatsApp 1 and 2 use separate
OS processes and profiles, so both stay logged in and browser data clears cannot sign them out.
The other hub web apps share an isolated `:webapps` process with its own profile.

Web-app top bars follow the app theme and show Back, Download, Fit (Facebook only), Reload, and
Exit. Exit offers **Exit & keep running** or **Close web app**; the first resumes the same live
page without a reload. Keep-alive foreground services protect the last-used web app on aggressive
OEM ROMs. Facebook image/file downloads save straight to `Download/` without a second
confirmation. The ⬇ Quick Download button uses page-video detection and exported Gecko cookies
for login-required videos. **Web App Creator** supports up to 4 custom web-app slots, each with
its own process and profile.

## Tools

The Tools group gathers utilities that used to be hidden in other screens. Back returns to the
launcher. The tile order starts with Bible, then Nyimbo; Internet Radio follows Sound Recorder,
and BT Mouse & Keyboard follows Calculator.

- **Reading and planning:** **Bible** (offline KJV + Chichewa *Mawu a Mulungu mu Chichewa
  Chalero*), **Nyimbo** (331 offline hymns; hymn 76 is a visible placeholder), **Calendar** (local
  events), **Scan to PDF** (added photos only), and **Settings**.
- **Everyday tools:** **Flashlight**, **Sound Recorder**, **Screen Recorder**, **Internet Radio**,
  **Calculator** (with history), and **Stopwatch & Timer** (laps and countdown).
- **Device and network tools:** **BT Mouse & Keyboard**, **Wi-Fi Transfer**, **Samba Server**,
  **Text Extract** (OCR), **APK Backup**, **Phone Info**, and **Network Tools** (ping, speed test,
  port scan, DNS lookup, Wi-Fi details).
- **System shortcuts:** phone settings, SIM manager, Wi-Fi, Bluetooth, data, hotspot, display,
  rotation, app management, Play Store, Palm Store, and Phone Master.

### BT Mouse & Keyboard

Confirmed working on the Tecno Spark 40 (KM5). The phone advertises a standard Bluetooth HID
combo device, so the PC needs no extra app or driver. Open the tool, tap **Make discoverable**,
pair from the PC, return to the tool, and tap **Connect**.

One-finger drag moves the pointer, tap clicks, two-finger tap right-clicks, and two-finger drag
scrolls. A long-press toggles drag-lock. The keyboard panel has live typing, sticky modifiers,
special keys, media keys, macros, and dictation. Android 12+ asks for Nearby devices.

### Floating Assistant

The Floating Assistant is a manually started overlay bubble; it does not start at boot. Start it
from Settings → Floating Assistant, and configure it from the bubble gear, Settings → Manage, or
the Assistant Settings tile. Bubble size, opacity, idle fade, and idle delay are adjustable.

The panel has **Quick / Media / Notes / SMS** tabs. Quick actions are inline only: flashlight,
calculator, inline camera, screen recorder, data status, SMS, snippets, and contacts. Media has
now playing, sleep timer, speed, five-band equalizer, and a mini recorder. Notes and SMS work
inline. Full-screen launches collapse the bubble first. Close stops recording and the overlay.

## Emulators and Linux tools

NJ-Dialer bundles **no ROMs, BIOS/firmware, ISOs, JARs, or JADs**. Import your own files with the
system file picker. Deletes go through the Recycle Bin.

**APK Sandbox** installs and runs APKs in a virtual Android environment. It supports `.apk`,
`.apks`, `.xapk`, and `.zip` bundles, including split APKs. All-files access is recommended for
reliable launches. Shared folders are opt-in per app. App settings open in NJ-Dialer rather than
stock Android Settings. Virtual users keep apps and data separate, and apps can have fake
locations. Crash logs can be exported to `Download/NJCrash/`.

APK Sandbox is process isolation, **not a security boundary**. Sandboxed apps share NJ-Dialer's UID
and permissions. Apps needing Google Play services, Play Integrity, banking protections, or
32-bit native code are likely to fail.

**Retro Games** uses a vendored mGBA core for Game Boy, Game Boy Color, and Game Boy Advance ROMs.
Import `.gb`, `.gbc`, and `.gba` files yourself. It has a custom Compose gamepad with adjustable
size, opacity, and offset, portrait gameplay, and a compact ⋮ menu for slots, pause, reset,
settings, and stop.

**J2ME** uses a vendored runtime for user-imported `.jar` / `.jad` games inside NJ-Dialer. Imports
are copied to app-private storage and deduplicated. No games, JARs, or JADs are bundled.

**PPSSPP** uses a vendored libretro PPSSPP core for user-imported `.iso`, `.cso`, `.pbp`, and
`.chd` images. No BIOS, firmware, or game images are bundled. Gameplay is landscape with a custom
Compose gamepad. PPSSPP uses a separate native library (`libppssppjni.so`) to avoid duplicate
libretro symbols. It defaults to software rendering for reliability. On-device verified: PSP
games play.

**Terminal** runs a Debian aarch64 rootfs through proot, fully offline after first install. It has
a root prompt with `apt`/`dpkg`, Google DNS, multi-tab support, pinch zoom, extra keys, and a
long-press Copy/Paste menu. Terminal settings live in the ⋮ menu. The terminal is always dark, and
typing `exit` or closing the last tab returns to the launcher.

**VNC Viewer** is a pure-Kotlin RFB client for external VNC servers. It supports RFB 3.8/3.7/3.3,
None and VNC Auth, Raw, CopyRect, RRE, Hextile, Zlib, and DesktopSize. Saved passwords are
encrypted with the Android Keystore. Touch controls include pointer move, tap/right-tap, scroll,
pinch zoom, special keys, and text sending. Raw VNC is not encrypted; use a trusted network or
VPN. The local Openbox Linux desktop/VM was removed permanently; VNC Viewer connects only to
external VNC servers.

## Privacy and permissions

Share buttons/actions were removed from File Manager, recorder, backup, OCR, Terminal, Contacts,
Call History, Text Editor, Gallery, Downloads, browser menus, Wi-Fi Transfer, and Screen
Recorder. Deliberate non-Share actions remain: SMS `ACTION_SENDTO`, File Manager **Send via
Bluetooth**, and Wi-Fi Transfer/Samba **share folder** / **Share name** wording.

Backup & Restore writes one JSON file to `Download/NJ-Dialer`. Crash logs are written to
`Download/NJCrash/`. VNC passwords use Android Keystore encryption and are never passed on a
command line. yt-dlp cookie files are temporary and deleted after use. Private notes require the
PIN to view **and** to export.

The app may request phone, contacts, and call log access; SMS read/send; camera and microphone;
media/files access including All-files access; notifications and notification listener access for
the in-app Quick shade; Nearby devices for BT Mouse & Keyboard; Display over other apps for the
Floating Assistant; and device admin for optional uninstall protection and double-tap-to-lock.

## Developer notes

- Main modules: `:app`, `:j2me`, `:Bcore`, `:black-reflection`, and `:compiler`.
- App package/namespace is `com.example`; application id is `com.aistudio.phone.dialer.app`.
- `MainActivity.kt` routes the launcher, Phone surface, and immersive tool screens.
- Room uses KSP; rebuild after changing entities or DAOs.
- GeckoView web apps isolate sessions by process and profile. Each process may have only one
  GeckoRuntime.
- Native emulator/core code is vendored under `app/src/main/cpp`, `j2me`, and related modules.
- R8 settings for release builds are present but have not been runtime-validated on-device.
- See `AGENTS.md` for detailed maintenance rules and `THIRD_PARTY_NOTICES.md` for source and
  licence notices.

## Credits

- Credits: Bible translation is Biblica® *Mawu a Mulungu mu Chichewa Chalero*, CC BY-SA 4.0;
  Nyimbo source is *Nyimbo Za Chitsitsimutso*. Emulator cores and vendored projects are listed in `THIRD_PARTY_NOTICES.md`.
