package com.example.ui.screens.apps

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.components.AppIcons

/**
 * One launchable entry. [id] is the same route id the rest of the app already
 * understands: a plain `immersiveAppId` for tools, a web-app id for the Gecko
 * activities, or one of the special ids handled by MainActivity's launchById
 * ("phone", "contacts", "messages", "settings", "terminal").
 *
 * [keywords] widens launcher search without showing more text on the tile
 * (e.g. "sms,text" for Messages, "photos" for Gallery).
 */
data class AppItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val description: String,
    val keywords: String = "",
    val section: String = ""
)

/** The former "Apps" hub contents (also used by the launcher Home grid). */
val APPS: List<AppItem> = listOf(
    AppItem("files", "File Manager", AppIcons.Folder, "Manage local files", "storage,sdcard,folder", "Apps"),
    AppItem("texteditor", "Text Editor", AppIcons.EditNote, "Edit notes and code files", "text,code,editor,markdown,json", "Apps"),
    AppItem("archivemanager", "Archive Manager", AppIcons.Archive, "Extract and create archives", "zip,tar,gzip,compress,extract", "Apps"),
    AppItem("web", "Web Browser", AppIcons.Language, "Surf the web", "browser,internet", "Apps"),
    AppItem("camera", "Camera", AppIcons.PhotoCamera, "Photos & videos", "photo,picture,shoot", "Apps"),
    AppItem("audio", "Audio Player", AppIcons.MusicNote, "Listen to music", "music,songs,player", "Apps"),
    AppItem("video", "Video Player", AppIcons.Movie, "Watch videos", "movies,player", "Apps"),
    AppItem("notes", "Notes & Tasks", AppIcons.EditNote, "Notes & checklists", "todo,memo", "Apps"),
    AppItem("calendar", "Calendar", AppIcons.CalendarToday, "Events & appointments", "schedule,appointments,birthdays", "Apps"),
    AppItem("scanpdf", "Scan to PDF", AppIcons.DocumentScanner, "Turn pages into PDF", "scanner,document,ocr,pdf", "Apps"),
    AppItem("gallery", "Image Gallery", AppIcons.PhotoLibrary, "Photos by folder", "photos,pictures,images", "Apps"),
    AppItem("imageeditor", "Image Editor", AppIcons.Tune, "Crop, rotate & adjust photos", "photo,picture,edit,crop,filter,adjust", "Apps"),
    AppItem("downloads", "Downloads", AppIcons.FileDownload, "Downloaded files, exports & saves", "download,exports,notes,tasks,music,movies", "Apps"),
    AppItem("downloader", "Video Downloader", AppIcons.VideoLibrary, "Save online videos", "video,youtube,ytdlp,download", "Apps"),
    AppItem("trash", "Recycle bin", AppIcons.Restore, "File Trash", "recycle,bin,deleted", "Apps")
)

/** The former "Tools" hub contents. */
val TOOLS: List<AppItem> = listOf(
    AppItem("bible", "Bible", AppIcons.MenuBook, "Read offline (KJV)", "scripture,verse", "Tools"),
    AppItem("hymns", "Nyimbo", AppIcons.QueueMusic, "Hymns Chichewa", "hymnal,songs", "Tools"),
    AppItem("flashlight", "Flashlight", AppIcons.FlashlightOn, "Use as a torch", "torch,light", "Tools"),
    AppItem("recorder", "Sound Recorder", AppIcons.Mic, "Record voice", "voice,microphone,audio", "Tools"),
    AppItem("screenrecorder", "Screen Recorder", AppIcons.FiberManualRecord, "Record the phone screen", "screen,capture,video,mp4,recorder", "Tools"),
    AppItem("radio", "Internet Radio", AppIcons.Radio, "Stream live radio", "fm,stream,music", "Tools"),
    AppItem("calculator", "Calculator", AppIcons.Calculate, "Quick math & history", "math,sum", "Tools"),
    AppItem("stopwatch", "Stopwatch & Timer", AppIcons.Timer, "Stopwatch, laps & countdown timer", "timer,stopwatch,countdown,lap,clock", "Tools"),
    AppItem("btinput", "BT Mouse & Keyboard", AppIcons.Mouse, "Use this phone as a PC mouse & keyboard", "bluetooth,hid,mouse,keyboard,pc,input,trackpad", "Tools"),
    AppItem("vnc", "VNC Viewer", AppIcons.DesktopWindows, "Connect to external VNC servers", "remote,desktop,rfb,pc,screen,vnc", "Tools"),
    
    AppItem("bttransfer", "Bluetooth Transfer", AppIcons.Bluetooth, "Send files to a paired phone", "bluetooth,share,send,receive,files,transfer,paired", "Tools"),
    AppItem("wifishare", "Wi-Fi Transfer", AppIcons.Wifi, "Share files over Wi-Fi", "share,network", "Tools"),
    AppItem("samba", "Samba Server", AppIcons.Dns, "Stream files from PC", "smb,network,pc", "Tools"),
    AppItem("ocr", "Text Extract", AppIcons.DocumentScanner, "Read text from pics", "scan,ocr,image", "Tools"),
    AppItem("qrscanner", "QR Scanner", AppIcons.QrCodeScanner, "Scan QR & barcodes", "qr,barcode,scan", "Tools"),
    AppItem("vault", "Vault", AppIcons.Lock, "Private file vault", "private,secure,encrypted,files", "Tools"),
    AppItem("apkbackup", "APK Backup", AppIcons.Backup, "Back up apps", "apk,backup,install", "Tools"),
    AppItem("deviceinfo", "Phone Info", AppIcons.PhoneAndroid, "Device details", "info,battery,system", "Tools")
)

/** Native runtime/virtual-machine apps grouped on the Home grid. Terminal stays last. */
val EMULATORS: List<AppItem> = listOf(
    AppItem("sandbox", "APK Sandbox", AppIcons.Android, "Run APKs in a virtual Android environment", "apk,sandbox,virtual,clone,test,emulator", "Emulators"),
    AppItem("retro", "Retro Games", AppIcons.SportsEsports, "Play imported GB/GBC/GBA games", "emulator,gameboy,gba,rom,muga", "Emulators"),
    AppItem("j2me", "J2ME Loader", AppIcons.Memory, "Play imported Java ME games", "emulator,j2me,java,me,jar,jad,midlet", "Emulators"),
    AppItem("ppsspp", "PSP Emulator", AppIcons.SportsEsports, "Play imported PSP games", "emulator,ppsspp,psp,iso,cso,rom", "Emulators"),
    AppItem("terminal", "Terminal", AppIcons.Terminal, "Linux shell", "shell,linux,debian,emulator,vm", "Emulators")
)

/** The former "Web Apps" hub contents (launched through the Gecko activities). */
val WEB_APPS: List<AppItem> = listOf(
    AppItem("whatsapp", "WhatsApp", AppIcons.Chat, "Messages on the web", "chat,messenger", "Web apps"),
    AppItem("whatsapp2", "WhatsApp 2", AppIcons.Forum, "Second account", "chat,messenger", "Web apps"),
    AppItem("telegram", "Telegram", AppIcons.Send, "Telegram messages", "chat,messenger", "Web apps"),
    AppItem("facebook", "Facebook", AppIcons.Groups, "Facebook web", "social,meta", "Web apps"),
    AppItem("youtube", "YouTube", AppIcons.PlayCircle, "Watch videos", "video,stream", "Web apps"),
    AppItem("gemini", "Gemini", AppIcons.SmartToy, "Google AI assistant", "ai,google", "Web apps"),
    AppItem("tiktok", "TikTok", AppIcons.MusicVideo, "Short videos", "video,social", "Web apps"),
    AppItem("perplexity", "Perplexity", AppIcons.Search, "AI answer engine", "ai,search", "Web apps"),
    AppItem("chatgpt", "ChatGPT", AppIcons.AutoAwesome, "OpenAI chatbot", "ai,openai", "Web apps"),
    AppItem("claude", "Claude", AppIcons.AutoAwesome, "Anthropic AI assistant", "ai,anthropic", "Web apps"),
    AppItem("grok", "Grok", AppIcons.AutoAwesome, "xAI assistant", "ai,xai", "Web apps"),
    AppItem("photos", "Google Photos", AppIcons.PhotoLibrary, "Photos & media", "photos,google,images", "Web apps"),
    AppItem("copilot", "Microsoft Copilot", AppIcons.AutoAwesome, "Microsoft AI assistant", "ai,copilot,microsoft", "Web apps"),
    AppItem("deepseek", "DeepSeek", AppIcons.AutoAwesome, "DeepSeek AI", "ai,deepseek,llm", "Web apps"),
    AppItem("manus", "Manus AI", AppIcons.AutoAwesome, "Manus AI agent", "ai,agent,manus", "Web apps"),
    AppItem("aistudio", "AI Studio", AppIcons.Science, "Google AI Studio", "ai,google", "Web apps"),
    AppItem("classroom", "Classroom", AppIcons.MenuBook, "Google Classroom", "school,google", "Web apps"),
    AppItem("meet", "Meet", AppIcons.Videocam, "Google Meet", "call,video,google", "Web apps"),
    AppItem("drive", "Google Drive", AppIcons.FolderSpecial, "Your cloud files", "storage,google,files", "Web apps"),
    AppItem("docs", "Google Docs", AppIcons.Description, "Documents on the web", "writer,google,documents", "Web apps"),
    AppItem("gmail", "Gmail", AppIcons.Email, "Google email", "mail,google,email", "Web apps"),
    AppItem("webappcreator", "Web App Creator", AppIcons.Add, "Create your own web apps", "web,creator,add,app", "Web apps")
)

/** Core comms and the trailing utility/settings items. */
private val LAUNCHER_CORE: List<AppItem> = listOf(
    AppItem("phone", "Phone", AppIcons.Phone, "Recents & keypad", "dial,keypad,recents", "Comms"),
    AppItem("contacts", "Contacts", AppIcons.Contacts, "Your contacts", "people,phonebook", "Comms"),
    AppItem("messages", "Messages", AppIcons.Sms, "SMS", "sms,text,inbox", "Comms"),
    AppItem("callstats", "Call Stats", AppIcons.BarChart, "Usage, talk time & top contacts", "statistics,usage,minutes", "Comms"),
    AppItem("contacttools", "Contact Tools", AppIcons.MergeType, "Duplicates, backup & restore", "vcf,import,export,dedupe,merge", "Comms")
)

private val LAUNCHER_TAIL: List<AppItem> = listOf(
    AppItem("launchersettings", "Launcher Settings", AppIcons.DisplaySettings, "Home layout & background", "launcher,home,wallpaper,tiles,columns", "System"),
    AppItem("assistantsettings", "Assistant Settings", AppIcons.SmartToy, "Floating assistant options", "assistant,bubble,floating,overlay", "System"),
    AppItem("settings", "Settings", AppIcons.Settings, "App preferences", "preferences,config", "System")
)

/**
 * The launcher grouped into labelled sections for the Home grid headers, in the
 * display order emulators first, then comms/apps/tools, then the web apps, with
 * Settings last. Only NJ-Dialer's own features live here — no installed device
 * apps. Filtering happens before grouping so a header never appears with no
 * tiles under it.
 */
val LAUNCHER_SECTIONS: List<Pair<String, List<AppItem>>> = buildList {
    add("Emulators" to EMULATORS)
    add("Comms" to LAUNCHER_CORE)
    add("Apps" to APPS)
    add("Tools" to TOOLS)
    add("Web apps" to WEB_APPS)
    add("System" to LAUNCHER_TAIL)
}
