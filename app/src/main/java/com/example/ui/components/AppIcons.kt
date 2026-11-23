package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*

/**
 * Single source of truth for every icon used in the app (Phase 1.5).
 *
 * Why this exists: the Material icons artifact *hides* deprecated names instead of
 * failing loudly. When the Compose BOM moved to 2026.09.00, `Icons.Default.Shield` and
 * `Icons.Default.Security` stopped resolving, and because breakage depended on each
 * file's star-imports it scattered across call sites rather than surfacing once, in one
 * reviewable place. This object is that place.
 *
 * Usage:
 * ```
 * import com.example.ui.components.AppIcons
 * Icon(AppIcons.Search, contentDescription = null)
 * ```
 *
 * Rules:
 * - Screens import [AppIcons], never `androidx.compose.material.icons.*`.
 * - To add an icon, add it here first, then reference `AppIcons.X` everywhere.
 * - Do not re-export an icon known to be hidden-deprecated. If one is genuinely needed,
 *   substitute a supported equivalent and note it here; do not silence the compiler.
 * - `Icons.Default` is an alias for `Icons.Filled`, so everything is normalised to
 *   `Filled` here.
 * - Where an icon exists in both plain and auto-mirrored form, the **auto-mirrored**
 *   variant is canonical under the plain name. It renders identically in LTR and is
 *   correct under RTL, so there is no reason to keep the non-mirrored spelling. This
 *   affects ArrowBack, ArrowForward, CallMade, CallMissed, CallReceived, KeyboardArrowRight, Send, VolumeUp.
 *
 * Generated from the app's actual icon usages; keep in sync by re-running the
 * inventory rather than hand-adding.
 */
object AppIcons {
    val Accessibility = Icons.Filled.Accessibility
    val AccessTime = Icons.Filled.AccessTime
    val Add = Icons.Filled.Add
    val AddCircle = Icons.Filled.AddCircle
    val Android = Icons.Filled.Android
    val Apps = Icons.Filled.Apps
    val Architecture = Icons.Filled.Architecture
    val Archive = Icons.Filled.Archive
    val ArrowBack = Icons.AutoMirrored.Filled.ArrowBack
    val ArrowDownward = Icons.Filled.ArrowDownward
    val ArrowForward = Icons.AutoMirrored.Filled.ArrowForward
    val ArrowUpward = Icons.Filled.ArrowUpward
    val AspectRatio = Icons.Filled.AspectRatio
    val AudioFile = Icons.Filled.AudioFile
    val Audiotrack = Icons.Filled.Audiotrack
    val AutoAwesome = Icons.Filled.AutoAwesome
    val AutoFixHigh = Icons.Filled.AutoFixHigh
    val Backspace = Icons.Filled.Backspace
    val Backup = Icons.Filled.Backup
    val BarChart = Icons.Filled.BarChart
    val BatteryChargingFull = Icons.Filled.BatteryChargingFull
    val BatterySaver = Icons.Filled.BatterySaver
    val BatteryStd = Icons.Filled.BatteryStd
    val Block = Icons.Filled.Block
    val Bluetooth = Icons.Filled.Bluetooth
    val BluetoothConnected = Icons.Filled.BluetoothConnected
    val Bookmark = Icons.Filled.Bookmark
    val BookmarkBorder = Icons.Filled.BookmarkBorder
    val Bookmarks = Icons.Filled.Bookmarks
    val Brightness6 = Icons.Filled.Brightness6
    val BrightnessHigh = Icons.Filled.BrightnessHigh
    val BrokenImage = Icons.Filled.BrokenImage
    val Build = Icons.Filled.Build
    val Calculate = Icons.Filled.Calculate
    val CalendarToday = Icons.Filled.CalendarToday
    val Call = Icons.Filled.Call
    val CallEnd = Icons.Filled.CallEnd
    val CallMade = Icons.AutoMirrored.Filled.CallMade
    val CallMissed = Icons.AutoMirrored.Filled.CallMissed
    val CallReceived = Icons.AutoMirrored.Filled.CallReceived
    val CameraAlt = Icons.Filled.CameraAlt
    val Cameraswitch = Icons.Filled.Cameraswitch
    val Category = Icons.Filled.Category
    val Chat = Icons.Filled.Chat
    val Check = Icons.Filled.Check
    val CheckCircle = Icons.Filled.CheckCircle
    val CheckCircleOutline = Icons.Filled.CheckCircleOutline
    val ChevronLeft = Icons.Filled.ChevronLeft
    val ChevronRight = Icons.Filled.ChevronRight
    val ChromeReaderMode = Icons.Filled.ChromeReaderMode
    val CleaningServices = Icons.Filled.CleaningServices
    val Clear = Icons.Filled.Clear
    val Close = Icons.Filled.Close
    val ClosedCaption = Icons.Filled.ClosedCaption
    val CloudOff = Icons.Filled.CloudOff
    val Code = Icons.Filled.Code
    val Contacts = Icons.Filled.Contacts
    val ContentCopy = Icons.Filled.ContentCopy
    val ContentCut = Icons.Filled.ContentCut
    val ContentPaste = Icons.Filled.ContentPaste
    val CreateNewFolder = Icons.Filled.CreateNewFolder
    val Crop = Icons.Filled.Crop
    val DarkMode = Icons.Filled.DarkMode
    val Delete = Icons.Filled.Delete
    val DeleteForever = Icons.Filled.DeleteForever
    val DeleteSweep = Icons.Filled.DeleteSweep
    val Description = Icons.Filled.Description
    val Deselect = Icons.Filled.Deselect
    val DesktopWindows = Icons.Filled.DesktopWindows
    val DeveloperMode = Icons.Filled.DeveloperMode
    val Dialpad = Icons.Filled.Dialpad
    val DisplaySettings = Icons.Filled.DisplaySettings
    val Dns = Icons.Filled.Dns
    val DocumentScanner = Icons.Filled.DocumentScanner
    val DoneAll = Icons.Filled.DoneAll
    val Download = Icons.Filled.Download
    val DriveFileMove = Icons.Filled.DriveFileMove
    val Edit = Icons.Filled.Edit
    val EditNote = Icons.Filled.EditNote
    val Email = Icons.Filled.Email
    val Equalizer = Icons.Filled.Equalizer
    val ErrorOutline = Icons.Filled.ErrorOutline
    val ExpandLess = Icons.Filled.ExpandLess
    val ExpandMore = Icons.Filled.ExpandMore
    val Explore = Icons.Filled.Explore
    val FastForward = Icons.Filled.FastForward
    val FastRewind = Icons.Filled.FastRewind
    val FiberManualRecord = Icons.Filled.FiberManualRecord
    val FileDownload = Icons.Filled.FileDownload
    val FilterVintage = Icons.Filled.FilterVintage
    val FindInPage = Icons.Filled.FindInPage
    val FitScreen = Icons.Filled.FitScreen
    val FlashAuto = Icons.Filled.FlashAuto
    val FlashOff = Icons.Filled.FlashOff
    val FlashOn = Icons.Filled.FlashOn
    val FlashlightOff = Icons.Filled.FlashlightOff
    val FlashlightOn = Icons.Filled.FlashlightOn
    val Flip = Icons.Filled.Flip
    val Folder = Icons.Filled.Folder
    val FolderOff = Icons.Filled.FolderOff
    val FolderOpen = Icons.Filled.FolderOpen
    val FolderSpecial = Icons.Filled.FolderSpecial
    val FolderZip = Icons.Filled.FolderZip
    val FormatSize = Icons.Filled.FormatSize
    val Forum = Icons.Filled.Forum
    val Forward10 = Icons.Filled.Forward10
    val Fullscreen = Icons.Filled.Fullscreen
    val FullscreenExit = Icons.Filled.FullscreenExit
    val GridOn = Icons.Filled.GridOn
    val GridView = Icons.Filled.GridView
    val Group = Icons.Filled.Group
    val Groups = Icons.Filled.Groups
    val History = Icons.Filled.History
    val Home = Icons.Filled.Home
    val HomeWork = Icons.Filled.HomeWork
    val Image = Icons.Filled.Image
    val Inbox = Icons.Filled.Inbox
    val Info = Icons.Filled.Info
    val InsertDriveFile = Icons.Filled.InsertDriveFile
    val InstallMobile = Icons.Filled.InstallMobile
    val Keyboard = Icons.Filled.Keyboard
    val KeyboardArrowDown = Icons.Filled.KeyboardArrowDown
    val KeyboardArrowLeft = Icons.Filled.KeyboardArrowLeft
    val KeyboardArrowRight = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val KeyboardArrowUp = Icons.Filled.KeyboardArrowUp
    val Language = Icons.Filled.Language
    val Layers = Icons.Filled.Layers
    val LocationOn = Icons.Filled.LocationOn
    val Lock = Icons.Filled.Lock
    val LockOpen = Icons.Filled.LockOpen
    val Memory = Icons.Filled.Memory
    val MenuBook = Icons.Filled.MenuBook
    val MergeType = Icons.Filled.MergeType
    val Message = Icons.Filled.Message
    val Mic = Icons.Filled.Mic
    val MicOff = Icons.Filled.MicOff
    val MoreVert = Icons.Filled.MoreVert
    val Mouse = Icons.Filled.Mouse
    val Movie = Icons.Filled.Movie
    val MusicNote = Icons.Filled.MusicNote
    val MusicVideo = Icons.Filled.MusicVideo
    val NetworkCheck = Icons.Filled.NetworkCheck
    val NoteAdd = Icons.Filled.NoteAdd
    val NotificationAdd = Icons.Filled.NotificationAdd
    val NotificationsActive = Icons.Filled.NotificationsActive
    val OndemandVideo = Icons.Filled.OndemandVideo
    val OpenInNew = Icons.Filled.OpenInNew
    val Output = Icons.Filled.Output
    val Pause = Icons.Filled.Pause
    val PauseCircle = Icons.Filled.PauseCircle
    val Person = Icons.Filled.Person
    val PersonOff = Icons.Filled.PersonOff
    val Phone = Icons.Filled.Phone
    val PhoneAndroid = Icons.Filled.PhoneAndroid
    val PhoneDisabled = Icons.Filled.PhoneDisabled
    val PhoneInTalk = Icons.Filled.PhoneInTalk
    val Photo = Icons.Filled.Photo
    val PhotoCamera = Icons.Filled.PhotoCamera
    val PhotoLibrary = Icons.Filled.PhotoLibrary
    val PictureAsPdf = Icons.Filled.PictureAsPdf
    val PictureInPictureAlt = Icons.Filled.PictureInPictureAlt
    val PlayArrow = Icons.Filled.PlayArrow
    val PlayCircle = Icons.Filled.PlayCircle
    val PlayCircleFilled = Icons.Filled.PlayCircleFilled
    val PlaylistAdd = Icons.Filled.PlaylistAdd
    val PlaylistPlay = Icons.Filled.PlaylistPlay
    val Public = Icons.Filled.Public
    val PushPin = Icons.Filled.PushPin
    val QrCode = Icons.Filled.QrCode
    val QrCodeScanner = Icons.Filled.QrCodeScanner
    val QueueMusic = Icons.Filled.QueueMusic
    val LibraryMusic = Icons.Filled.LibraryMusic
    val Radio = Icons.Filled.Radio
    val RadioButtonUnchecked = Icons.Filled.RadioButtonUnchecked
    val RecordVoiceOver = Icons.Filled.RecordVoiceOver
    val Refresh = Icons.Filled.Refresh
    val Remove = Icons.Filled.Remove
    val Repeat = Icons.Filled.Repeat
    val RepeatOne = Icons.Filled.RepeatOne
    val Replay = Icons.Filled.Replay
    val Replay10 = Icons.Filled.Replay10
    val RestartAlt = Icons.Filled.RestartAlt
    val Restore = Icons.Filled.Restore
    val RotateLeft = Icons.Filled.RotateLeft
    val RotateRight = Icons.Filled.RotateRight
    val Save = Icons.Filled.Save
    val SaveAlt = Icons.Filled.SaveAlt
    val Science = Icons.Filled.Science
    val ScreenLockLandscape = Icons.Filled.ScreenLockLandscape
    val ScreenLockPortrait = Icons.Filled.ScreenLockPortrait
    val ScreenRotation = Icons.Filled.ScreenRotation
    val SdStorage = Icons.Filled.SdStorage
    val Search = Icons.Filled.Search
    val SearchOff = Icons.Filled.SearchOff
    val Security = Icons.Filled.Security
    val SelectAll = Icons.Filled.SelectAll
    val Send = Icons.AutoMirrored.Filled.Send
    val Sensors = Icons.Filled.Sensors
    val Settings = Icons.Filled.Settings
    val Share = Icons.Filled.Share
    val Shield = Icons.Filled.Shield
    val ShoppingBag = Icons.Filled.ShoppingBag
    val Shuffle = Icons.Filled.Shuffle
    val SignalCellularAlt = Icons.Filled.SignalCellularAlt
    val SimCard = Icons.Filled.SimCard
    val SkipNext = Icons.Filled.SkipNext
    val SkipPrevious = Icons.Filled.SkipPrevious
    val Slideshow = Icons.Filled.Slideshow
    val SmartToy = Icons.Filled.SmartToy
    val Smartphone = Icons.Filled.Smartphone
    val Sms = Icons.Filled.Sms
    val Speaker = Icons.Filled.Speaker
    val Sort = Icons.Filled.Sort
    val SortByAlpha = Icons.Filled.SortByAlpha
    val Speed = Icons.Filled.Speed
    val Star = Icons.Filled.Star
    val StarBorder = Icons.Filled.StarBorder
    val StickyNote2 = Icons.Filled.StickyNote2
    val Stop = Icons.Filled.Stop
    val Storage = Icons.Filled.Storage
    val Store = Icons.Filled.Store
    val Subtitles = Icons.Filled.Subtitles
    val SubtitlesOff = Icons.Filled.SubtitlesOff
    val SwapHoriz = Icons.Filled.SwapHoriz
    val SwapVert = Icons.Filled.SwapVert
    val Tab = Icons.Filled.Tab
    val Terminal = Icons.Filled.Terminal
    val Timer = Icons.Filled.Timer
    val Translate = Icons.Filled.Translate
    val Tune = Icons.Filled.Tune
    val Unarchive = Icons.Filled.Unarchive
    val Vibration = Icons.Filled.Vibration
    val VideoLibrary = Icons.Filled.VideoLibrary
    val Videocam = Icons.Filled.Videocam
    val VideocamOff = Icons.Filled.VideocamOff
    val ViewAgenda = Icons.Filled.ViewAgenda
    val ViewList = Icons.Filled.ViewList
    val ViewStream = Icons.Filled.ViewStream
    val Visibility = Icons.Filled.Visibility
    val VisibilityOff = Icons.Filled.VisibilityOff
    val VolumeMute = Icons.Filled.VolumeMute
    val VolumeOff = Icons.Filled.VolumeOff
    val VolumeUp = Icons.AutoMirrored.Filled.VolumeUp
    val Warning = Icons.Filled.Warning
    val Wifi = Icons.Filled.Wifi
    val WifiTethering = Icons.Filled.WifiTethering
    val ZoomIn = Icons.Filled.ZoomIn
    val ZoomOut = Icons.Filled.ZoomOut
}
