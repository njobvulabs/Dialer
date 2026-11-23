# NJ-Dialer

An all-in-one Android phone assistant built with **Jetpack Compose** and a single-activity
architecture. It launches into a **launcher-style Home grid** of every NJ-Dialer feature —
phone, contacts, messages, media, browsers, AI assistants, utilities — with a **swipe-left
System Tools page** for the phone-config shortcuts, alongside a full phone dialer (contacts,
call history, in-app call UI), the productivity and media tools (file management, web
browsing, media players, Samba network access, Wi‑Fi file transfer), and the reading apps
and utilities (Bible, Nyimbo, Flashlight, Sound Recorder, Internet Radio, Calculator,
BT Mouse & Keyboard, Wi‑Fi Transfer, Samba, Text Extract, APK Backup,
Phone Info, Calendar, Scan to PDF).

**Calendar & Scan to PDF:** the Calendar manages events and appointments (event CRUD includes add/edit/delete). Scan to PDF accepts added photos only and converts them locally into a shareable PDF.

Every app view shares one unified top bar (no duplicate headers), and the app remembers your
last screen — the launcher page, which Phone tab you were on, the open app, the open
media/document viewer, and even the last scroll position in the launcher, File Manager,
Audio Player, Gallery, Notes, Device Info, APK Backup, and Samba — so it relaunches exactly
where you left off.

Pressing **Back at the app root** (no tool or screen open) asks before leaving:
**"Exit & stop everything"** stops the web-app/terminal keep-alive services and audio
playback and removes the task, while **"Keep running in background"** just backgrounds
the app so nothing gets killed by a stray Back tap.

## Launcher

The app opens on a **launcher-style Home grid** — there is no bottom dock. Every destination
(Phone, Contacts, Messages, Settings, Launcher Settings, and every tool) is a tile, grouped
under **Comms / Apps / Tools / Web apps / System** section headers.

- **Landscape-friendly layout** — on wide/landscape screens (e.g. Tecno Spark 40 in landscape), lists and tool content are centered and capped to avoid stretched layouts and empty space, while wallpapers, media, camera previews, and immersive viewers stay full-bleed. The launcher adapts its grid in very wide landscape.
- **Home** — a scroll-away clock/date header with live seconds, an icon-toggled search
  field (matches title, description and keywords), and the feature tiles. All search
  fields app-wide use the same compact 40 dp pill with 14 sp text. Choose a **Grid** or
  **List** layout: list mode shows one full-width row per app with its description. Long-press a
  tile to **pin** it to the top; **Pinned**, **Recent** (your last few opened apps) and
  favourite-contact rows appear above the sections. The **Phone** and **Messages** tiles
  carry small count badges for missed calls and unread texts. App icon swatches, the
  clock/search header, the Now Playing card, summary chips and smart-search results use
  a lightweight frosted-glass look (translucent fill, soft edge and highlight) without
  expensive per-panel blur; the Tools & Dashboard clock, glance cards and list rows
  share it.
- **Swipe left for System Tools** — the second page holds the phone-config shortcuts
  (settings, SIM, Wi-Fi, Bluetooth, data, hotspot, display, rotation, app management
  and the app stores) in the same layout, so both pages feel like one
  launcher. A small dot indicator appears while you swipe and fades out once the page
  settles, and Back returns to Home. The page can be turned off in **Launcher Settings**.
- **Missed-call & unread-SMS chips** appear only when there is something to report and open
  the Phone / Messages screens.
- **Downloads, Vault, Call statistics and Contact tools** are Home-grid tiles. They have
  **no on-screen Back button**; use the system back gesture to close them. **Video
  Downloader** remains the separate yt-dlp downloader.
- **Launcher Settings** (its own tile) controls **Grid / List layout**, **grid columns**
  (Auto / 4 / 5), **tile size** (Compact / Default / Large), **app labels**, **show clock &
  date** (with live seconds) and its **clock format** (System / 12-hour / 24-hour),
  **section headers**, the **Recent row**, the **System Tools page**, a custom
  **Static** or **Slideshow** background (up to 20 images, 15 s–1 h interval,
  optional shuffle) with shared **opacity** and **dim** sliders, and
  **Reset to defaults**.
- **Quick shade** — pull down from the top edge on any native NJ-Dialer screen (launcher,
  Phone, tools and the in-app browser) to open a full-screen shade over a blurred snapshot
  of the current screen. Pull down on the **left half** to open **Notifications**, or the
  **right half** for **Quick settings**; each opens as a separate full-screen panel.
  Notification rows open the source app. Dismiss by swiping up from the bottom handle or
  pressing Back. Quick settings has flashlight/auto-rotate toggles, a brightness slider,
  live Wi‑Fi/Bluetooth status, and an editable, reorderable tile list with Battery,
  Location, Accessibility, Apps, Date & time and Developer options shortcuts. The QS tile
  grid scrolls when it overflows the page. The closed-state top pill is intentionally
  invisible, and the pull gesture only activates after a downward drag, so normal top-bar
  buttons keep working.
  Notification access is needed to list notifications; it can be turned off in
  **Launcher Settings**, and separate Gecko/WhatsApp web-app windows are not covered.
- **Floating Assistant** — a small translucent bubble can float over other apps. It is
  started manually from Settings → Floating Assistant (or the overlay actions) and configured
  from the bubble gear, Settings → Manage, or the **Assistant Settings** tile in the System
  group. The bubble is draggable, snaps to screen edges, fades when idle, and has separate
  sliders for normal and faded opacity. Its panel has Quick, Media, Notes and SMS tabs, with saved
  clipboard snippets, contacts, flashlight, mobile data status with a settings shortcut, a mini
  calculator with copy result, an inline camera, and inline screen recorder on Quick. The old
  standalone recorder Quick tile was removed because the same audio recorder lives on the Media
  tab, alongside now-playing controls with sleep timer and equalizer. Screen recording stays
  inline in the panel; only the system screen-capture consent appears briefly, a 3-second
  countdown runs, then recording continues in the background. The card mirrors the full
  screen recorder's quality, mic/system-audio, timer and pause/resume controls.
  Quick actions run inline only and never launch the full-screen recorder route;
  Close stops the overlay and finalizes recording.

## Features

### Phone
- **Opened from the Home grid** — there is no bottom dock. The Phone screen holds the
  **Recents / Contacts / SMS** sub-tabs, switched by swiping the Phone pager or by launching
  a specific tab from the launcher tiles
- Dialer keypad, T9 contact search, call history, favorites, blocked numbers, speed dials
- **Jump straight to a tab from the launcher** — the Phone, Contacts and Messages tiles, and
  the missed-call / unread-SMS chips, open the matching sub-tab
- In-app call screen with notifications/permissions handling
- **Settings opens from its own launcher tile** — app preferences (theme, color,
  call behaviors, backup & restore)
- Tapping a caller's name in **call history opens the full contact details** panel
  (call/message/share/edit/delete), matching what you see in the Contacts tab
- **Call history is one row per contact** — a person's calls are grouped under a
  single row however each one was dialled ("+265 99…" and "099…" are the same person),
  showing the name, the **number** and the date. Tap the row to expand the individual
  calls, each still individually callable, blockable, note-able and deletable; only one
  group is open at a time and back collapses it. Tapping the name still opens the
  contact panel, and deleting a group removes every call in it (asking "Delete 3 calls
  with X?") from both the app and the phone's own call log
- SIM slot selection and system call fallback
- **Call recording** — manual button in the call screen or auto-record; tries VOICE_CALL,
  then VOICE_COMMUNICATION, then MIC sources; saves M4A to `Recordings/NJ-Dialer Calls`
- **Caller announce** — speaks the caller's name over the ringtone (on-device TTS)
- **Advanced screening** — block private/hidden numbers, international numbers, custom
  prefixes, plus a first-time-caller silent mode that auto-mutes the ringer
- **Missed-call assistant** — notification with Call back / Message / Dismiss actions and
  optional repeating reminders (5/10/30/60 min) until dismissed
- **Swipe gestures on history** — swipe right to redial, left to send an SMS
- **SMS** 📩 — a full SMS tab (Recents / Contacts / **SMS**) with **read** (inbox),
  **send**, and **receive** support across **both SIM slots**. Because NJ-Dialer is
  **not** the default SMS app, sent messages are persisted locally in the app's Room DB
  and merged with the system inbox per conversation. Each message bubble shows live
  **Sent ✓ / Delivered ✓✓ / Failed** status via carrier send + delivery reports; per-recipient
  conversations, **contact names**, a **toggleable search** (tapping the search icon opens the
  search field), unread badges (red count on each conversation row **and** on the SMS tab),
  mark-as-read on open, **long-press a bubble** to Copy / Forward / Delete, a
  characters/segments counter, and automatic refresh on resume **plus a live inbox watcher**
  so an arriving text shows up without waiting for you to leave and return to the tab.
  Messages you send to a brand-new number are re-attached to the real phone conversation
  as soon as the system creates it, so a conversation never splits in two (this also
  applies to **Forward**). The compose fields use explicit high-contrast colours so the
  text stays readable in both light and AMOLED dark mode.
  **Long-press a conversation** for **Mark as read** or **Delete** — Delete clears the
  chat (with an **Undo**) and it stays out of your list until somebody actually texts
  you again, at which point it comes back. Note the app is not the phone's default SMS
  app, so the inbox messages themselves stay in the phone's storage and reappear if you
  return to the thread
- **Call statistics** 📊 (Home tile) — totals, talk time, 6-month & 8-week bar charts, top
  contacts, per-SIM breakdown; no top-bar Back button
- **Contact tools** (Home tile) — duplicate scan & merge, offline VCF backup/import of
  contacts and CSV backup/import of the call log; no top-bar Back button. **Contacts
  search** lives as an icon in the header row that reveals the search field on tap
  (matches name or number)
- **Contacts are grouped by person, not by number** — a device contact who saved several
  numbers (mobile, work, home) is **one** entry with all of them listed, and their calls,
  SMS and messages all land on that one contact rather than a separate row per number.
  A local contact is matched to a device contact by **any** of the numbers in either record.
- **Every number gets its own action** — tap the **⌄ chevron** on a contact row to expand
  it and get one row per number, each with its own **Call** and **Copy** button, so you pick
  which line to dial instead of getting one ambiguous combined entry. Only one row is open
  at a time. The **Contact Details** screen does the same: one block per number with
  per-number message and call buttons, plus SIM 1 / SIM 2 buttons for the primary number.
  Matching everywhere (call history names, speed-dial chips, known-caller detection,
  caller announcement, missed-call names, duplicate scan and VCF import) compares **each**
  number of a person rather than collapsing the whole list into one string.

### Apps Hub
- **File Manager** — browse/view/copy/move/rename/delete, multi-select, ZIP & unzip,
  Vault encryption, all-files access, install APKs; search is an icon in the toolbar
  that reveals the search box on tap. The top-bar **⋮ menu** holds **Storage
  locations, Storage Usage, New Folder, New File, Open Encrypted Vault** and
  **Sort** — the tool launchers (Samba,
  Wi-Fi Transfer, Phone Info, APK Backup, Text Extract) moved out of the
  ⋮ menu onto the dedicated **Tools** tab — and any **image file's three-dot menu**
  has an **"Extract Text"** action (on-device OCR)
- **Vault** — its own Home-grid tile opens the PIN-protected encrypted vault without going
  through the File Manager; it has no on-screen Back button.
- **External SD card support** — the **⋮ → "Storage locations"** dialog lists every
  browsable volume (internal storage + any SD card) with its free/total space;
  tapping one switches the File Manager to that volume. The breadcrumb and back-arrow
  use the volume's real label, the up-arrow stops at the volume root, and SD-card
  media also shows up in the Video/Audio/Documents libraries. No extra permission is
  needed — the app already holds **All-files access** — and deletes still go to the
  single internal **Recycle bin** (an SD-card delete is copied across volumes)
- **Sort lives one level down** — the ⋮ menu has a single **"Sort by <current>"** row
  (with a ›); tapping it opens the submenu with **Name / Date / Size / Type** plus
  **Ascending / Descending**, so the option list no longer clutters the main menu.
  Tapping the option you already have selected just flips the direction
- **Folder sizes are real** — folder rows show `12 items • 3.4 MB` instead of a bare
  "Folder", the Details pane reports a folder's true total, and **Sort by size** now
  works on folders too. Totals are walked in the background with a bounded, cached
  scan, and a truncated walk is shown honestly with a `+`
- **Copy & Move ask where** — pasting opens a **folder picker** (searchable, bounded
  scan) instead of always dropping into the folder you're already in. If names already
  exist at the destination you choose **Keep both** (default — nothing is ever
  silently overwritten), **Skip existing** or **Overwrite**, and long copies show
  **progress with a working Cancel**
- **Nothing is destroyed by a mis-tap** — every delete (files, audio, video, camera
  captures, image viewer, Downloads) moves to the **Recycle bin** (a tile on the
  **Apps Hub**), which you can **Restore** from, delete forever, or **Empty** wholesale. The bin
  is quota-limited (512 MB by default) and evicts the oldest items when full (never the
  item you just deleted), and an **Undo** button appears right after each delete and
  restores the right file even when the bin holds an older one of the same name
- **Web Browser** — full **GeckoView** (Firefox engine) browser: tabs with lazy sessions,
  **at most 3 live content processes** and every session released when you leave the
  browser, so a backgrounded app cannot be killed for holding too many Gecko processes
  (tabs keep their URL/title and reload on return); switching tabs never reloads a live one,
  while `intent://` links are filtered/confirmed instead of being launched as-is, and
  **private tabs are never written to disk**. Session-state snapshots are captured in memory
  but not restored on eviction/reopen, so tabs load fresh (restoring snapshots was removed to
  eliminate blank white pages after clearing data/cache). Includes strict tracking protection,
  a new-tab page with **your own tiles** (add any site by name + address; long-press to remove),
  **Top sites** ranked by a real visit counter, and recent history, search engines
  (Google/DuckDuckGo/Bing/Brave/Startpage or a custom `{search}` template), bookmarks, history,
  downloads to `Download/NJ-Dialer`, reader mode, save-page-as-PDF, screenshots to
  `Pictures/NJ-Dialer`, find-in-page, desktop mode (global + per-site overrides), dark websites,
  themed floating chrome (bottom by default, top optional) with a hide/restore toggle handle,
  clear-data (cookies/site data/cache/browsing history), and native `<video>` element fullscreen.
  Homepage can be a custom URL **or blank** — a "blank" homepage opens the New Tab (speed dial)
  page, not a dead white page (the Home Page dialog has a "Use blank page (New Tab page)" option).
  A dedicated full-screen **Downloads manager** (⋮ menu → Downloads, or the Home-grid **Downloads**
  tile) lists files from `Download/`, `Download/NJ-Dialer`, `Pictures/NJ-Dialer`,
  `Movies/NJ-Dialer` and `Music/NJ-Dialer Audio`; tapping opens media in the built-in viewer/player,
  and **delete appears only after long-pressing to select** (matching File Manager/Gallery select
  patterns). The browser route returns to the browser on system Back; the tile route returns to the
  launcher. There is no top-bar Back button.
- **Browser stability & tab switcher** — up to **3 tabs stay live** (each tab is its own Gecko content
  process, so an uncapped set exhausts memory on low-RAM phones); switching tabs only **detaches**
  the session instead of closing it, so a tab you return to is the same live page — never a reload.
  Beyond the cap the least-recently-used **unpinned background** tab is evicted (active and pinned tabs
  are never evicted) and reloads when reopened. The tab switcher supports **pin/unpin** (pinned tabs
  persist, sort first and stay out of LRU eviction), **Duplicate Tab**, crash indicators, and grouped
  normal/private tabs.
- **Crash recovery (no auto-reload)** — if a page crashes or is killed, the tab shows an in-place crash
  interstitial with **Reload** and **Close tab** actions instead of reloading automatically.
- **Back/forward have a full trail** — a plain tap on ← / → steps one entry as always, and a
  **long-press** opens the whole trail for that direction so you can jump straight back to a page from
  five steps ago. Only the active tab's list is captured, and private tabs stay out of it.
- **Reopen a closed tab** — closing a tab shows a "Tab closed — Undo" snackbar, and ⋮ → **Reopen Closed
  Tab** brings back the last one (up to 10 remembered). Private tabs and the New Tab page are
  deliberately never remembered. ⋮ → **Close Private Tabs** (and a "Close private" button in the tab
  drawer) tears down every incognito tab at once without touching your normal tabs.
- **Site Info sheet** — tap the padlock/globe in the address bar for the connection verdict, the
  certificate (issued to/by, valid until), a live count of **trackers blocked on this page**, and the
  stored per-site permission decisions, with a shortcut to Manage site permissions. Disabled on the New
  Tab page and in private tabs.
- **Browser chrome & layout** — the floating chrome can be hidden/restored with the toggle handle
  (`ChromeToggleHandle`) and overlays the page; the GeckoView, new-tab page and crash interstitial fill
  to the physical bottom edge (no theme-colored gap/cut-off), while the scaffold keeps top/horizontal
  system insets and the snackbar clears the navigation bar.
- **Per-site permissions** (⋮ → Site Permissions) — geolocation, notifications and
  persistent storage are decided **per origin** (`https://site` and `http://site` are
  separate) and remembered: grant, deny, or leave on Ask to get Gecko's own prompt.
  You can see every site with a stored decision, forget one, or clear them all
- **Long-press anything for the app's own context menu** — a **link** gets New Tab,
  Incognito, Copy Link, Share, plus **Download image** / **Download video** when it really
  is a media file (video goes through the yt-dlp downloader, so it keeps your login).
  A long-press on plain **text** now opens this same menu with **Copy Text**, **Select All**
  and **Share Text** instead of only Gecko's bare two-row popup, and a long-press on a link
  inside a selection offers both sets of rows at once. Copy/Select all act on the real page
  selection, so they keep working as a normal selection.
- **Camera** — Transsion-style modes: AI CAM, Portrait (on-device
  background blur via ML Kit selfie segmentation, fully offline), Pro,
  Video and Short Video (15s/30s auto-stop). Beauty is embedded in AI CAM
  (and available in Portrait) rather than a separate mode; its slider is
  shown/hidden with the top-bar wand button, and the chosen strength is
  remembered across camera sessions. On devices that
  ship a vendor extension library, AI CAM routes through the OEM **Auto**
  extension and Portrait through the OEM **Bokeh** extension (CameraX
  Extensions; silently falls back to the plain path when unavailable).
  Saved photos aim to match the stock camera's output: the default **FULL**
  aspect captures the full 4:3 sensor (no 16:9 crop), the AI-CAM scene
  colour boost was dropped, HDR is off by default, and an unedited shot is
  kept as the camera's original JPEG (no re-encode); edits bake EXIF
  orientation before the crop. Video recording can be
  **paused/resumed** mid-clip. Built-in **QR/barcode
  scanner** — tap the scan icon to read QR, EAN, Data Matrix and more with
  Copy / Open-link actions. Live filters previewed in real time via
  Media3, video quality selector (4K/2K/1080P/720P/480P), offline AI scene
  detection (Night/Document/Portrait/Scenery/Food), aspect ratio
  (default **FULL**), flash, HDR, ISO/exposure, **tap-to-focus** and a
  vertical **exposure-compensation** slider. A settings gear exposes
  **Keep screen on** and **Photo resolution**
  (Full / 4096 px / 2048 px, default 4096; the cap bounds the single-decode
  post-capture pipeline so it can't OOM). Post-capture processing is one
  bounded decode → in-memory crop/beauty/blur/filter → one encode, and is
  skipped entirely when nothing was edited.
  Gallery thumbnails open in the in-app Image Gallery
  viewer and Video Player on tap; tapping a photo opens the viewer in
  **swipe mode**, so you can flick left/right through the other captures
  without leaving it
 - **Audio Player** — folder grouping, **multiple playlists (rename, play-all and
    up/down reordering)**, **favourites + recently-played shelves**, **up-next queue**
    (play next / add to queue from any row), **per-track title/artist/album/duration
    from the file's own tags**, **bass boost and L/R balance** beside the 5-band EQ,
    **sleep timer** (5/15/30/60 min or end of queue) and **persisted playback speed**,
    background-aware playback, repeat
    (one/all/off) & shuffle, with one shared advance rule used by both the screen and
    the lock-screen/notification controls, so they can't disagree:
    **shuffle now works with repeat ALL** (a shuffled loop, not an in-order one),
    **repeat OFF plays the queue through to the last track** before stopping, a shuffled
     pass plays every track once before repeating anything, and **Previous walks back
     through the shuffled order** instead of jumping somewhere random. Track-end, Next,
     Previous and the lock-screen/notification buttons all resolve the playing track's
     position *at the moment they fire*, so advancing can no longer continue from a
     stale index (which replayed the current song or jumped to an arbitrary one), and
     leaving the player hands the session to the background engine cleanly — the mini
     bar and lockscreen keep the same queue, order and metadata as the open screen.
     debounced search; global search toggle; queue resync on folder switch;
    music keeps playing when you leave the app via a media-notification foreground
    service with a **lockscreen/notification mini player** (Play/Pause, Prev, Next, Stop
    via MediaSession) — now carrying the track's own **title, artist, album, duration and
    cover art**, a **live position** that keeps advancing, a **draggable scrubber**
    (`SEEK_TO`) and **±10 s fast-forward / rewind** on the lockscreen and in the
    notification, plus a floating in-app mini bar above the bottom edge whenever
    music plays outside the Audio Player; the bar stays visible when paused (shows "Paused"),
    has a **thick tap-to-seek progress line**, and its Prev/Next buttons keep working even
    after you close the player screen, thanks to engine-level background queue control.
    Playback also **pauses when headphones are unplugged** and **ducks or pauses for audio
    focus**. The 5-band **equalizer + preset, bass boost, balance and speed persist**
    across player opens (reopening from the mini bar keeps your settings instead of resetting)
- **Video Player** — VLC-style folder grouping (grid/list) with an icon-toggled search
   field and floating top bars (directories + folder/back), full-screen player with
   brightness/volume, screen lock, rotation, playback speed, subtitles, A-B loop, PiP
   mode, repeat OFF/ONE/ALL, sleep timer, buffering/error overlays with Retry,
   resume position persistence (auto-restores last position with "Resumed at" toast),
   and double-tap skip + swipe gestures (playback controls stay visible while any ⋮ menu dialog is open).
   Seeks are clamped and committed once when a drag/slider is released, and a generic
   seek/resume failure retries once from the start instead of dropping straight to the error card.
   Playback **pauses when you leave the app** and does not auto-resume; the bottom
   timeline only redraws itself as playback advances, and details are shown in-app
   rather than shared to another app.
- **Videos and folders are found again** — the Video app builds its library from two
   sources: a bounded filesystem walk **and** the platform's own media index
   (`MediaStore.Files`, images/audio/video/documents). The walk alone stopped two folders
   down, so anything nested deeper — WhatsApp/Telegram downloads, `Movies/Series/Season 1/…`
   — was invisible even though the File Manager opened it fine, and a folder holding one
   visible video had no playlist to build. MediaStore knows media at any depth in one cheap
   query, so the walk now goes four levels deep with an entry budget and MediaStore covers
   whatever is deeper or inside trees the app cannot enumerate. A failed refresh now keeps
   the last good index instead of blanking the tab.
- **Folder playlists work from anywhere** — opening a video builds the containing folder's
   video list and hands it to the player as a playlist, so the **Playlist** button (in the
   top control row, between the lock icon and the ⋮ menu), auto-advance and the playlist
   sheet appear even when the video was opened from the File Manager rather than the Video
   app. Playlist skipping is through that sheet — the bottom left/right prev/next buttons
   stay removed. Files opened by Samba streaming stay single-track, as they have no folder
   to list.
- **Internal subtitles now show** — a video that carries its own subtitle track
  (MKV/MP4 in SubRip, WebVTT, TTML, SSA/ASS, SAMI or QuickTime tx3g) now displays it.
  The in-app player has no text-subtitle rendering path in its custom Compose overlay,
  so it reads the track out of the file and feeds it to the same caption renderer already used
  for sidecar files. A sidecar `<name>.srt`/`.vtt` still wins when it parses, the subtitle
  menu shows where the current cues came from, and bitmap subtitles (PGS/VobSub) have no
  text to show, so they stay silent.
- **Internet Radio** — live Malawi radio stations (Zodiak, Capital Radio, Times Radio,
  MBC Radio 1 & 2 FM, Mzati, Chanco, Timveni, BT Synod, TWR) streaming via
  ExoPlayer/Media3 with a resilient HTTP datasource. It lives on the **Tools tab** (its
  own full screen with a floating top bar, sitting just after Sound Recorder) — it used
  to open only from inside the Audio Player
- **Image Gallery** — photos grouped by folder (cover thumbnails + counts), pinned
  "All Photos" & "Favorites", full-screen viewer with zoom/rotate/flip and prev/next,
  search + sort (both held as **icons in the bar** — tap Search to reveal the search
  field inside a folder, exactly like the folder grid), long-press **multi-select**
  (share / copy / move / delete / favorite), slideshow, and one-tap "Open in Image Editor"
- **Gallery photo delete, three ways** — long-press a photo for an action sheet
  (**Select for multi-edit / Share / Copy to folder… / Move to folder… / Favorites /
  Details / Delete**); long-press the "Delete" row while photos are selected and it
  says "Delete N selected image(s)" so it never quietly removes just the one photo you
  pressed; or tap the **trash icon in the full-screen viewer**, which now works for
  gallery photos (it previously only worked for files opened from the File Manager) and
  drops you back on the grid afterwards. The Android 11+ system delete prompt is raised
  without freezing the app behind it
- **Image Editor** — freeform crop (drag to move, corner/edge handles to resize, preset
  ratios 1:1/4:3/16:9/9:16 or Free), filters, brightness/contrast/saturation, draw, text,
  save to gallery, then return to the source photo (viewer or Gallery folder)
- **File Manager → Send via Bluetooth** — the three-dot menu on any **file** has a
  dedicated **"Send via Bluetooth"** row right under **Share File**. It hands the file
  straight to the Bluetooth app's own share/OPP handler (so you get pairing and the
  transfer progress) instead of making you dig through a generic chooser, and falls back
  to the chooser on ROMs that expose no such activity. No Bluetooth permission is needed to
  send — only to scan for devices.
- **File Manager → Convert to Audio** — the three-dot menu on any video file (MP4 etc.)
  offers **"Convert to Audio (M4A)"**: it extracts the audio track with `MediaTranscoder`
  (live in-dialog progress), saves it to `Download/NJConverter/<name>_audio.m4a`, scans it
  into the media store, and shows a toast when done. The old separate Converter app was
  removed — extraction lives right in the file manager now
- **Document Reader** — PDF/office/text/ebook reading. PPTX slides render text via a native
  `android.graphics` 2× supersampled rasterizer (images capped at native width), so both slide
  text and embedded images stay crisp on all devices — an on-device alternative to fuzzy
  Compose text layers and to uploading files to an online converter. Documents open by
  tapping them in the **File Manager** (the standalone Doc Reader app was removed)
- **Notes & Tasks** — rich text editor, colors, pinning, private lock (the **PIN is also
  required to export a private note**, since export writes an unlocked `.txt` into the
  Vault folder), and a **Notes | Tasks**
  floating segmented bar (tasks are notes flagged as to-dos, so there's no separate To-Do app);
  search is an icon in the bar that reveals the field, and a grid/list view toggle swaps between
  the staggered 2-column grid and a list. **Delete is long-press-only**: long-press a note/task
card to enter selection mode (checkmark + highlighted border, "N selected" in the bar), tap
   more to toggle them, then delete the selected ones from the bar's red Delete button; the
   **grid/list view you last chose is remembered** between launches
- **Settings** — app preferences (theme, call behaviors, backup & restore) from its own tile
  on the Home grid
- **System Tools** — quick shortcuts for Phone Settings, SIM Card Manager (with fallback
  pages), Wi-Fi, Bluetooth, Data Connection, App Management, Hotspot (opens tethering
  settings), Display, Screen Rotation, Google Play Store, Palm Store, Phone Master, and Clock.
  On the launcher these shortcuts live on the **swipe-left System Tools page** (see Launcher below),
  and **Tools & Dashboard → Apps & Stores** includes App Management, Google Play Store, Palm Store,
  and Phone Master. The three built-in tools —
  **Flashlight**, **Sound Recorder**, and **Calculator** — moved out of System Tools onto
  the **Tools** tab as separate full-screen apps (System Tools is now shortcuts only)
- **Device Info & APK Backup are two separate screens** — a plain title bar shows
  **Phone Info** (live battery, RAM/storage gauges, device/network/sensor specs with a
  one-tap "Copy All Info" button) or **APK Backup** (back up installed apps to APK files;
  Sort, Hide system apps and Select All are icon buttons that open menus, and the search
  icon sits on the All/Backed-Up/Missing filter line, revealing the search field on tap).
  **System apps are hidden by default** (toggle "Hide system apps" to reveal them), and the
  full installed-app list is visible because the app holds `QUERY_ALL_PACKAGES`. The
  backup card is a compact icon row with app/backed-up/size counts — no "APK Storage"
  heading or folder path.
  They used to share one screen with a pill switcher, which is gone — each is now launched
  from its own tile on the **Tools tab**
- **Network Tools** — ping (with latency stats), download speed test, common-port
  scanner, DNS lookup and Wi-Fi details (SSID, signal, link speed, IP/DNS)
- **Text Extract (OCR)** — on-device ML Kit text recognition: pick an image and copy, share,
  or save the extracted text straight into Notes. Launched from the **Tools tab** tile, from any
  **image file's three-dot menu → "Extract Text"** (which returns you to the File Manager),
  or from the in-app camera
 - **Video Downloader** — bundled yt-dlp engine (Python + FFmpeg) with a built-in
   **Update Engine** button that pulls the latest official yt-dlp so YouTube/Facebook/
   Instagram keep working as their sites change. Paste a link (or long-press any
   link/video in a web app or browser → "Download Video", or share it to NJ-Dialer from
   any app's share sheet, or tap the **⬇ button in the top bar of every web app** for a
   quick popup downloader without leaving the page — its Detect button runs a
   multi-strategy sniffer (direct video src → post/permalink URL → YouTube videoId →
   CDN stream seen by the player) and keeps the current page URL live even on
    single-page apps like the YouTube/Facebook feeds), pick a quality
    (Best / 1080p / 720p / 480p / audio-only M4A) and save to `Movies/NJ-Dialer` or
    `Music/NJ-Dialer Audio`. After tapping Download the popup switches to a **live
    Downloads panel** with per-file %, size (e.g. "12.4 MB of 35.7 MB") and
    **Pause / Resume / Retry / Cancel** buttons, refreshed in real time. Facebook/
    Instagram/TikTok downloads pass your logged-in WebView **cookies** to yt-dlp
    (temp local file, wiped after each run) so login-walled videos extract.
    Every download — from the app, web-app popups, long-press
    or share sheet — lands in **one queue** you can watch live in the Video Downloader
    app, with system progress notifications (with Cancel) and completion/failure alerts,
    plus downloaded/total **file size** on each job. The bundled FFmpeg is wired in via
    `--ffmpeg-location`, so YouTube's separate video+audio streams merge into a single
    MP4 (and audio-only extraction works). Saved files keep the **original video
    title** as their filename (resolved before download, cookies included).
    Downloads run in a background queue so you can navigate away;
    live % progress with ETA, plus per-download **Pause, Resume, Retry and Cancel**
   (resumes `.part` files; direct browser downloads are handed to the system
   DownloadManager and now really pause/resume too), real error messages, download
   history, and one-tap playback of
   finished files in the built-in Music / Video players
  - **Progress no longer "restarts"** — because yt-dlp fetches a video as a video stream
    plus a separate audio stream and then merges them, the raw per-file percentage used
    to run 0→100% twice and read as a second download. Progress is now weighted by
    actual bytes, so the bar only ever moves forward: it fills for the video, holds
    while the audio track arrives and the merge runs (the status line says
    "Downloading audio track…" / "Merging formats…"), then completes. A retry also
    keeps the bar where it was instead of snapping back to 0% — it genuinely resumes the
    partial download. Tapping **Download** twice can no longer queue the same video
    twice (the button disarms itself and the queue rejects an identical URL + quality)
- **Bible (KJV + Chichewa)** — fully offline: the King James Version plus **Mawu a Mulungu mu
  Chichewa Chalero** (Biblica® CC BY-SA, generated from eBible.org USFM via
  `tools/bible_convert_usfm.py`). A translation switcher in the header swaps languages on the
  fly; books, chapters, live full-text search, verse copy/share/save, continue-reading resume,
  and bookmarks are all scoped per translation. Long-pressing a verse reveals **Copy / Share /
  Bookmark / footnotes**. A floating top bar carries **back (chapter → book list → exit),
  text size, translation and search**; the on-screen back walks up a level instead of leaving.
  Launched from the **Tools** tab.
- **Nyimbo (Chichewa hymnal)** — *Nyimbo za Chitsitsimutso*, bundled offline as
  `assets/hymns_nyimbo.json` (331 hymns). Search by number, Chichewa title, English title or
  any stanza line; star favorites, resume from a continue-reading row, and adjust the reading
  font with a slider. (Hymn 76's text is missing from the source scan; it appears as a visible
  "Text unavailable in source" entry so the numbering stays continuous.) Launched from the
  **Tools** tab, right after the Bible.
### Tools
The **Tools** tile group on the Home grid gathers the reading apps and utilities that used to
be scattered across the File Manager's ⋮ menu, System Tools, and the Audio Player. Each opens
as a full-screen immersive route with its own floating top bar; **back returns to the
launcher, not to the File Manager**. The tile order is deliberate — **Bible is first with
Nyimbo (the Chichewa hymnal) right after it**, **Internet Radio sits just after Sound
Recorder**, and **BT Mouse & Keyboard sits just after Calculator**, followed by
**VNC Viewer**:

1. **Bible** — KJV + Chichewa, fully offline (see below).
2. **Nyimbo** — *Nyimbo za Chitsitsimutso*, the Chichewa hymnal: searchable by number,
   title or any line, with favorites, continue-reading and a font-size slider.
3. **Flashlight** — camera-flash torch with its own dark styling and back arrow.
4. **Sound Recorder** — voice memos & audio recordings (own full screen, not embedded);
   recordings save to public `Music/NJ-Dialer/Recordings` when All-Files is granted. It
   can also capture **System playback** on Android 10+ (screen-capture consent, mic mixing
   optional) into `.m4a`.
5. **Screen Recorder** — silent H.264 MP4 by default, with optional microphone or system
   playback audio (Android 10+), Low/Standard/High quality, Off/30s/1m/3m timer, pause/resume,
   a 3-second countdown, and a recordings list with open/delete (no Share button). Outputs go to
   `Movies/NJ-Dialer/Screen Recordings`; deletes move files to the Recycle Bin.
6. **Internet Radio** — live Malawi radio stations via ExoPlayer/Media3.
7. **Calculator** — basic math with history.
8. **Stopwatch & Timer** — stopwatch with laps plus a countdown timer.
9. **BT Mouse & Keyboard** — use the phone as a Bluetooth trackpad + keyboard for a PC
   (see below).
10. **VNC Viewer** — control a remote desktop with a pure-Kotlin RFB client (see below).
11. **Wi-Fi Transfer** — HTTP server to share files over Wi‑Fi (see below).
12. **Samba Server** — browse and stream network shares (see below).
13. **Text Extract** — on-device OCR (ML Kit) with Copy/Share/Save-to-Notes.
14. **APK Backup** — back up installed apps to APK files.
15. **APK Sandbox** — run simple APKs in a virtual Android environment (see below).
16. **Phone Info** — device, battery, RAM/storage, network and sensor specs with
    "Copy All Info".

Most were moved out of the **File Manager ⋮ menu** (Samba, Wi-Fi Transfer, Phone Info,
APK Backup, Text Extract), out of **System Tools** (Flashlight, Sound
Recorder, Calculator), and out of the **Audio Player** (Internet Radio) so every utility
is one tap from the Home grid. The **Recycle bin** lives in the **Apps** group (see above).
The in-player and ⋮-menu launchers were removed.

The earlier **Wi-Fi Audio** (DLNA/UPnP receiver) tool has been **removed completely** — there
is no tile, no route, no background receiver service, and the app no longer requests the
Wi-Fi multicast permission. This phone can no longer be picked as a DLNA/UPnP renderer;
use **Wi-Fi Transfer** or **Samba Server** to move or stream files over the network.

### BT Mouse & Keyboard
Confirmed working on the **Tecno Spark 40 (KM5)**. The phone advertises a standard
Bluetooth HID combo device (mouse + keyboard), so the PC needs no extra app or driver.
Open **BT Mouse & Keyboard** → **Make discoverable** → pair from the PC → return to the
tool → tap **Connect** in the paired-computers list.

- **Trackpad** — one-finger drag moves the pointer, tap = left click, two-finger tap =
  right click, two-finger drag = scroll. A ~0.5 s long-press toggles drag-lock so you can
  drag without holding; **Left / Middle / Right** buttons and the **Drag** toggle mirror
  the same actions. Sensitivity is adjustable and saved.
- **Keyboard** — tap the keyboard icon. Typing is relayed live, with sticky
  **Ctrl / Shift / Alt / Win** modifiers and a special-key row (Esc, Tab, Enter,
  Backspace, arrows, Home/End, Page Up/Down, Delete, F5, Ctrl+Alt+Del, Ctrl+V, Ctrl+C,
  Ctrl+Z, Ctrl+A).
- **Runs in the background** — a `connectedDevice` foreground service keeps the HID link
  alive, so you can switch apps or lock the phone while the PC stays connected. The
  notification's **Disconnect** action ends the session.
- **Requirements** — Android 9+ with the Bluetooth HID Device profile. On Android 12+ the
  app asks for **Nearby devices** (`BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`). Works
  fully offline, with no PC-side software.

### VNC Viewer
Control a remote desktop over raw RFB using a **pure-Kotlin VNC client** (no WebView,
JNI or third-party GPL/AGPL client). Open **VNC Viewer** from the Tools group, enter the
host (or tap a saved host), port and optional password, then connect.

- **Protocol support** — RFB 3.8 / 3.7 / 3.3, **None** and **VNC Auth** (DES), 32-bit
  true-colour, and the Raw, CopyRect, RRE, Hextile, Zlib and DesktopSize encodings.
- **Saved hosts** — optional connections are remembered locally. **Saved passwords are
  encrypted with the Android Keystore** (`CredentialCipher`); saving a password is off by
  default.
- **Controls** — one-finger drag moves the pointer; a tap clicks; a two-finger drag
  scrolls; pinch zooms (up to 6×) and pans. The bottom bar has a **Right tap** mode,
  scroll buttons, special keys (including Ctrl/Alt/Del and F1–F12), **Send text**, paste
  and reset zoom. A hardware keyboard works while the viewer is focused.
- **Security** — raw VNC is **not encrypted**; use it on a trusted network or through a
  VPN. The screen says so before you connect.
- **Local Linux desktop** — the same screen can install and run an **on-device Xfce
  desktop** inside the app's existing Debian (proot) environment. Tap **Install** on the
  Linux desktop card (or the prompt when you first open VNC Viewer); the app downloads
  TigerVNC and Xfce with `apt` (internet and several hundred MB required), then starts a
  loopback-only VNC server on `127.0.0.1:5901`. Use **Start / Open / Stop** on the card;
  **Open** connects the viewer with the saved `This device (Linux Desktop)` host. The
  desktop is owned by a foreground service, so leaving VNC Viewer or the app does not kill
  it. The generated VNC password is stored through `VncPrefs`/`CredentialCipher` and is
  never passed on the command line. **Exit & stop everything** also stops the desktop.
  The session installs into the same `filesDir/debian-rootfs` used by the Terminal, so
  re-running Terminal setup (a `SETUP_VERSION` bump) can remove it.

### APK Sandbox
Open **APK Sandbox** from the Tools group to install and run a selected APK inside a
virtual Android environment. The app uses a SAF file picker, copies the APK into its own
cache, installs it into the sandbox, and then lists sandboxed apps with **Launch**, **Stop**,
**Clear**, **Info** and **Remove** actions. **Info** shows diagnostics that can be saved to
`Download/NJCrash/` for troubleshooting.

- **All-files access is required for reliable launches** — the screen shows the current
  state and can open the system All files access settings for NJ-Dialer.
- **Shared folders are opt-in per app** — each app’s ⋮ menu can switch between virtual
  storage and passthrough for `Download`, `Documents`, `DCIM`, `Movies`, `Music`, and
  `Pictures`. The app is stopped when the mode changes and must be relaunched. In shared
  mode, those six folders appear in the virtual storage root as empty placeholders and open
  the real host folders; SAF/system file pickers also use the host Documents provider for
  those apps. Shared folders are not private: the sandboxed app shares NJ-Dialer’s UID and
  permissions.
- **Crash history survives relaunches** — **Info** includes diagnostics, and
  **Export crash logs** writes raw BBDiag/CrashMonitor logs to `Download/NJCrash/`.
- **Apps open immersive full screen** — status and navigation bars are hidden when a
  sandboxed app opens; swipe from the edge to reveal them temporarily.
- **Best for simple utility APKs** — apps that depend on Google Play services, Play
  Integrity, banking/anti-tamper protections, or 32-bit-only native libraries are likely to
  fail. Verified working on-device with a small notes APK.
- **Not a security boundary** — sandboxed apps run in a separate `:sandbox0` process, but
  they still use NJ-Dialer's UID and Android permissions. Do not treat it as isolation for
  untrusted apps.

### Web Apps
- The **Web apps** group on the Home grid holds all web-based apps, keeping the native tools
  separate. The browser is a tile alongside the native apps.
- **WhatsApp Web** — runs on the **GeckoView** engine (Firefox's engine) with a forced desktop
  user-agent and desktop viewport; isolated in its own OS process (`:whatsapp`) with a
  single `GeckoRuntime` instance per process and its **own Gecko profile directory**
  (`filesDir/whatsapp1-profile`) — so clearing the in-app browser's data/cache no longer
  logs WhatsApp 1 out. Includes desktop WebRTC calling through bare GeckoView (no injected
  extension), download-to-Downloads, and file uploads (copies selected files to app-private
  cache for reliable browser integration).
  Its top bar follows the app theme (accent + light/dark) and shows **Exit only** — the
  download icon is intentionally not shown on WhatsApp. Uses desktop viewport with larger default zoom for better fit on smaller displays.
- **WhatsApp 2** — second WhatsApp account in its own OS process (`:whatsapp2`) with a separate
  Gecko profile directory (`filesDir/whatsapp2-profile`), so both accounts stay logged in
  simultaneously and independent of browser-data clears. Same file upload
  handling as WhatsApp Web. Same desktop viewport sizing.
- **Telegram Web, Facebook, YouTube, Gemini, TikTok, Perplexity, ChatGPT, Claude, Grok,
  AI Studio, Google Classroom, Google Meet, Google Drive, Google Docs and Gmail**
- **Google Photos, Microsoft Copilot, DeepSeek and Manus AI**

Sessions (cookies + localStorage) persist until app data is cleared or the app is
uninstalled. The 19 Gecko hub web apps share the isolated `:webapps` OS process on a
dedicated profile (`filesDir/webapps-profile`), while WhatsApp 1 and WhatsApp 2 each have
their own process (`:whatsapp` / `:whatsapp2`) and profile. That separation means clearing
the in-app browser's data can never log out a web app. The last-used web app is pinned by a
**per-process keep-alive foreground service** while it is open, which is what makes logins
survive on aggressive OEM ROMs such as Tecno HiOS.

Every web app (WhatsApp 1 & 2, Telegram, Facebook, YouTube, Gemini, etc.) has a top-bar **Exit**
button that opens a two-choice dialog: **"Exit & keep running"** returns to NJ-Dialer while the
web app stays alive in its background task, so reopening resumes the exact page with no reload;
**"Close web app"** fully closes it and it reloads fresh next time. The top-bar **back** arrow
keeps its normal back-in-history behavior. Web-app toolbars follow the selected app theme
(accent + light/dark), not a fixed color.

The legacy WebView activities remain in the manifest only as fallbacks; the hub launches
**every web app through GeckoView** (`GeckoWebAppScreen`) instead. GeckoView is a real Firefox
engine, so Google sign-in works where embedded WebViews are blocked. Web-app cookies are kept
separate from the in-app browser by design, so clearing the browser cannot sign the web apps
out. Downloads and JS prompts are handled by Gecko delegates. **Long-press an image on Facebook (or any file) → Download
saves it straight to `Download/` with just a toast — no second confirmation popup**, so the
download can no longer take the web app down with it. Android runtime
permission requests flow through a Compose launcher, and the top bar is a **floating rounded
card** (matching the app's other floating bars; no app name) carrying **← Back · ⬇ · [Fit (Facebook only)] · ↻ Reload · Exit**.
The bar keeps its **text labels** (nothing was stripped to icons) and reflows so they never
wrap or get cut off on narrow screens, and it honours the app's **font-size setting**, so the
web-app bar is the same physical size as every other screen's bar. Leaving a page's own
fullscreen (a YouTube video, say) brings the bar back instead of leaving you without one.
The **⬇ Quick Download** button
runs the yt-dlp picker with live page-video detection via the `njbrowser` extension port, and
**since the apps now run on Gecko (no WebView cookie jar), a bundled `cookiejar` extension
exports the logged-in Gecko session cookies** to yt-dlp (via `browser.cookies`), so private
Facebook/Instagram/TikTok/YouTube downloads keep working like they did on WebView. The WebView
path is kept only as a legacy fallback.
Downloading no longer freezes or drops the web app: yt-dlp's Python/FFmpeg runtime is
extracted **on demand when the download picker opens**, off the main thread, instead of at
app start. The download queue is checkpointed to `nj_dl_jobs.json` so an interrupted job is
restored when you come back. If a crash ever does happen, a **crash log** is written to
`Download/NJCrash/crash_<timestamp>.txt` (stack trace of every process) for you to share.

WhatsApp 1 & 2 run in their own OS processes (`:whatsapp` / `:whatsapp2`) because each needs a
separate GeckoRuntime. To keep the last-used web app or WhatsApp alive after exiting, "Exit &
keep running" starts a per-process **keep-alive foreground service** (a small low-priority
notification, "… is still running", with a **Close** action to release it). Only the last-used
web app is kept alive. On Tecno HiOS and similar aggressive OEM ROMs, background processes
holding even a foreground service can be frozen unless the app is whitelisted in the device's
battery settings. An optional **Keep-alive timeout** (Never / 15 / 30 / 60 / 120 minutes) is
available in Settings → App Behavior. See AGENTS.md for the exact HiOS steps.

### Web App Creator (user-defined web apps)

You can point NJ-Dialer at your own sites: the **Web App Creator** tile in the **Web apps** launcher
section opens the creator manager for up to **4 custom web-app slots** (`customweb1`–`customweb4`).

- **Isolated hosting** — each custom slot runs in its own OS process (`:webapp1`–`:webapp4`) with its
  **own GeckoRuntime + Gecko profile directory** (`filesDir/custom_webapp_<n>`), mirroring the
  WhatsApp 1/2 isolation pattern. Site data / logins never mix between custom apps or with the
  built-in hub apps.
- **Persistence** — custom apps are stored in the `custom_webapps` prefs (JSON) and survive restarts;
  create/edit/delete write the slot list.
- **Lifecycle** — per-process **keep-alive foreground services** (`KeepAliveWebapp1..4Service`) apply
  to custom slots, so **"Exit & keep running"** behaves like the built-in web apps.
- **Launch** — the Home grid **Web apps** section surfaces your custom web apps as tiles; tapping one
  launches its slot activity (`GeckoWebAppActivity1..4`), resuming that slot's live Gecko session.

**Limit:** fixed **4 slots** — GeckoView allows only one `GeckoRuntime` per process, so true
per-app isolation is done via fixed slots rather than dynamic multi-profile hosting.

### Terminal (Linux Shell)
A full **Linux terminal** running Debian (aarch64) via **proot**, launched from the **Tools** tab.
- **Root prompt** — `root@localhost:~#` with full `apt`/`dpkg` privileges (PRoot `-0` flag + `USER=root`/`LOGNAME=root`/`HOME=/root` env)
- **Offline first run** — Debian rootfs bundled in assets (`debian-rootfs.tar.xz`), extracted on first launch (no download required)
- **Google DNS** — `/etc/resolv.conf` pre-configured with 8.8.8.8 / 8.8.4.4
- **Pinch-to-zoom** — two-finger scale adjusts font size (9–32 dp) in real time; zoom level is persisted
- **Multi-tab** — ⋮ menu can create new tabs, list/switch between them, and close the active tab
- **Termux-style extra keys** — two rows: `ESC TAB CTRL ALT HOME END / ← ↑ ↓ → PGUP PGDN ⌨`; CTRL/ALT are sticky toggles shared with the top-bar Ctrl; the ⌨ key or the ⋮ menu hides/shows the toolbar
- **Top bar** — Back · sticky **Ctrl** · title · **Ctrl+C** · **Ctrl+L** · **Ctrl+X** (nano exit) · ⋮ overflow menu (New/Close tab, switch tabs, Show/Hide extra keys, **Terminal Settings**, Reset zoom, Clear screen)
- **Always dark** — the terminal chrome and menus stay dark even when the app theme is Light; the old white bell flash is gone, while optional bell vibration remains
- **Terminal settings in the ⋮ menu** — theme, bell vibration, font size and zoom persist and are restored on reopen; change them from ⋮ → Settings (not from the app Settings screen)
- **Long-press text menu** — Copy / Paste / Share via a floating menu (no system toolbar)
- **Exit closes app** — typing `exit`, hitting back, or closing the last tab returns to the Tools tab
- **Auto-repair** — versioned setup marker forces re-extraction on code changes (no manual data clear needed)

### Wi-Fi Transfer
Launched from the **Tools tab**. Hosts a zero-dependency HTTP server
(`http://<wifi-ip>:8870`) so any device on the same
Wi‑Fi/Hotspot network can upload/download files from any browser — no app required on the
receiving device. Uses in-app folder/file pickers (no external SAF picker),
with an embedded QR code for easy connection. Rows have no action buttons: **tap opens** a
shared file and **long-press deletes** it after confirmation. There is no bulk "Clear all"
action. The server holds a **Wi-Fi lock** while it is running, so the
radio is not power-saved mid-transfer when the screen dims, and an **interrupted
upload is treated as a failure** (the partial file is removed and the transfer is
reported as failed) instead of silently leaving a truncated file behind.

### Samba Server
Launched from the **Tools tab**. Browse network shares (SMB/CIFS via jcifs-ng):
connect to servers, list shares/folders,
download, upload, rename, delete, and create folders. Tapping a media file opens it straight
in the built-in players **without downloading** — video plays through an in-app localhost
HTTP range proxy (`SambaMediaStream`), images/audio are streamed to a temp app cache, and
documents are cached and opened in the in-app document viewer. Non-media files still
download to `Downloads/NJSamba`. Both the server list and the file browser use the standard
floating top bar.

### Floating Assistant
- **Manual overlay** — NJ-Dialer can show a small bubble over other apps after you grant
  "Display over other apps". It does **not** start at boot and has no hidden keep-alive.
  Start it from **Settings → Floating Assistant**; open **Assistant Settings** from that
  card, the bubble's gear, or the **System → Assistant Settings** Home tile. There is no
  Floating Assistant tile in the Tools group.
- **Bubble** — size presets are **44 dp / 52 dp / 60 dp** (44 dp default). Normal opacity
  defaults to **0.72**, and the bubble fades to **0.38** after 8 seconds idle by default;
  both opacities and the delay (5/8/15/30 s) are adjustable. Tap, drag or use the bubble
  again to reset the fade. The expanded panel stays fully readable.
- **Panel** — Quick, Media, Notes and SMS tabs. Quick is inline-only: saved clipboard snippets,
  an inline Contacts list with copy-number, an independent flashlight toggle, a Data connection
  status card with a Change button that opens Android's mobile data settings, and a calculator with
  `+ − × ÷ %` and copy-result. Media hosts Now Playing with speed, sleep and 5-band equalizer
  controls plus read-only up-next, and a mini recorder with pause/resume, path copy and Recycle Bin
  delete (no Share button). SMS is an inline conversation list and composer backed by the app's
  local SMS store; the Quick SMS tile jumps to that tab. Quick tiles are Flashlight, Calculator,
  Recorder, Data, SMS, Contacts and Snippets; the Recorder tile opens the Media tab. Music and Notes
  are reached from their own tabs. Quick modes choose Hidden / Floating only.
- **Safety** — the bubble can auto-collapse on incoming calls or when the screen turns
  off (both on by default); collapse finalizes any mini recording. Use **Close** in the panel
  or notification, or **Exit & stop everything** at the app root, to stop it completely.

### Settings
- **Theme & color** — light / dark / system theme modes, **11 accent palettes** (Blue, Indigo,
  Teal, Purple, Pink, Orange, Green, Red, Cyan, Brown, Graphite), Material You dynamic colors
  (Android 12+), AMOLED pure-black dark mode, and a font-size slider (80–130%). Your chosen
  accent and mode apply across the whole app instantly and persist between launches.
- **Floating bars** — list-screen top toolbars and the in-app mini audio bar render as rounded,
  elevated "floating" cards with margins (instead of full-bleed bars), matching the modern UI
  style. The Audio Player folder sub-header and the Video Player VLC folders /
  folder-videos bars also use the floating style and follow the theme. The Gecko web apps'
  and WhatsApp 1/2 chrome bars float the same way (rounded card, no app name, margins), with
  the chrome-toggle handle floating just below the card.
- **Backup & Restore** — one JSON file (saved to `Download/NJ-Dialer`) containing notes,
  to-dos, bookmarks, browser history, contacts, blocked numbers and key prefs
- **Battery optimization** — one-tap exemption request plus Transsion/Tecno-specific tips so
  calls, radio, recordings and reminders survive background kills

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project
4. Run the app on an emulator or physical device

> **Building offline:** this project is built with
> `gradle :app:assembleDebug --offline`
> The debug APK is produced at
> `app/build/outputs/apk/debug/app-debug.apk` and can be sideloaded directly.
> If the system `gradle` command is broken (e.g. Arch's `/usr/share/java/gradle`),
> call the full distribution instead:
> `/home/njobvu/gradle-9.7.0-full/bin/gradle :app:assembleDebug`
> The APK ships **arm64-v8a only** (no x86/32-bit ABI bloat), keeping the debug
> build around **232 MB** while retaining the full GeckoView engine shared by
> WhatsApp, the in-app browser and the Gecko web apps, plus the bundled yt-dlp
> video downloader and Debian rootfs.

## Permissions

The app requests CALL/contacts/call-log, SMS read/send, camera, microphone, media read access
(images/video/audio on Android 13+), notifications, and **"All files access"**
(`MANAGE_EXTERNAL_STORAGE`) to enable the File Manager, gallery, and in-app file pickers.
Samba and **VNC Viewer** need the app to reach your network (the VNC client opens a direct
TCP connection to the host:port you enter; credentials stay in-app). The optional **local
Linux desktop** uses a `specialUse` foreground service so the Xfce session can keep running
after you leave the viewer. **BT Mouse & Keyboard** additionally needs **Nearby devices** on Android 12+
(`BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE`), requested when you open that tool; the
rest of the app needs no Bluetooth permission (even "Send via Bluetooth" only hands a file
to the system share sheet).
