package io.github.gcbond.openhc.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ecarx.eas.sdk.ECarXApiClient
import com.ecarx.eas.sdk.mediacenter.MediaCenterAPI
import com.ecarx.eas.sdk.mediacenter.MusicClient
import com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo
import io.github.gcbond.openhc.MainActivity
import io.github.gcbond.openhc.core.dmsdp.DmsdpMediaData
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.media.MediaKeyForwarder

/**
 * 车机双模媒体与仪表盘 EAS 推送桥（`CarMedia` 协同模式 + `CarLink` 同款 Link EAS 卡片直推兜底模式）：
 * - **聚合曲目元数据**：增量合并手机 515 推来的全量曲目包（`MediaData`）与每秒进度包（`ElapsedTime`）；
 *   当手机端播放未接入 HiCar 515 的应用、但车机蓝牙 AVRCP（`com.android.bluetooth`）有歌曲元数据时，自动从蓝牙媒体会话兜底补齐；
 * - **在线歌词补全**：515 未自带歌词时，通过 [HiCarLyricResolver] 从网易云接口拉取带时间戳的完整 LRC 歌词并缓存；
 * - **模式一（车机已安装 `CarMedia` `com.ecarx.carmedia` 且开启了 `EAS推送`）**：
 *   1. 通过 `MediaKeySession` 暴露歌名、歌手、专辑、时长、进度与封面 Bitmap；
 *   2. 向 `com.ecarx.carmedia/.SourceSwitchReceiver` 发送 `com.ecarx.carmedia.SWITCH_SOURCE` 切换音源到 OpenHC；
 *   3. 向 `com.ecarx.carmedia` 发送 `com.android.music.lyricchanged` 广播（携带完整 LRC 歌词），驱动 `CarMedia` 及其 EAS 仪表盘小卡片生效；
 *   4. 主动释放本应用的直连 EAS 注册，避免与 `CarMedia` 争抢 EAS 焦点；
 * - **模式二（车机未安装 `CarMedia`/蓝牙音乐，或 `CarMedia` 内关闭了 `EAS推送`）**：
 *   参考吉利 `CarLink优化版`（`com.geely.linkhmi` 的 `MediaCtrlMgr` + `HiCarInteractiveBridge`），
 *   自动通过 `ECARX MediaCenterAPI` 注册 `sourceType = 16/36/20`（Link 音源类型）并主动向仪表屏及桌面小卡片推送 **Link EAS 卡片**（含封面、进度与实时滚动歌词），
 *   同时将仪表盘/小卡片的播放、暂停、上下曲回调转发给手机端。
 */
class ClusterMediaBridge(
    private val onCommand: (MediaKeyForwarder.Command) -> Unit,
) {
    /** 聚合后的当前曲目快照（供 MediaKeySession、CoolHome、CarMedia 与直推 Link EAS 卡片共同消费）。 */
    class Snapshot(
        val title: String,
        val artist: String,
        val album: String,
        val appName: String,
        val appPackage: String,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        val artworkBitmap: Bitmap?,
        val artworkUri: Uri?,
        val lyricContent: String,
        val currentLyric: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    // 增量合并缓存（515 每秒进度包只有 ElapsedTime，其余字段沿用上一条全量包）。
    private var title: String = ""
    private var artist: String = ""
    private var album: String = ""
    private var appName: String = "HiCar"
    private var appPackage: String = ""
    private var totalTimeSec: Int = 0
    private var elapsedTimeSec: Int = 0
    private var status: Int = STATUS_PLAYING
    private var artworkBitmap: Bitmap? = null
    private var artworkHash: Int = 0
    private var artworkUri: Uri? = null
    private var lyricContent: String = ""
    private var currentLyric: String = ""

    private var last515AtElapsedMs: Long = 0L
    private var audioFocusHeld: Boolean = false
    private var lastSwitchedTitle: String = ""
    private var lastBroadcastLrcTitle: String = ""
    private var lastBroadcastLrcHash: Int = 0
    private var lastBroadcastAtElapsedMs: Long = 0L
    private var sessionCallback: ((Snapshot) -> Unit)? = null

    // 直推 Link EAS 卡片状态（仅在车机无 CarMedia 或 CarMedia 关闭 EAS 推送时启用）
    private var easApi: MediaCenterAPI? = null
    private var easToken: Any? = null
    private var easInitializing: Boolean = false
    private var easRegistered: Boolean = false
    @Volatile
    private var latestPlaybackInfo: MusicPlaybackInfo? = null
    @Volatile
    private var latestSnapshot: Snapshot? = null
    private var lastDirectEasFocusGrabMs: Long = 0L
    private var lastDirectEasTitle: String = ""
    private var lastDirectEasPlaying: Boolean = false
    private var interactiveManager: Any? = null
    private var interactiveActiveNotified: Boolean = false

    /**
     * 手机通话状态（504 `callState`，原厂 MediaCtrlMgr 同款保护）：非 0 = 响铃/通话/微信语音中。
     * 通话期间不向 EAS MediaCenter 抢音乐焦点、忽略外部卡片发来的「恢复播放」，避免接电话时音乐突然响起。
     * 连接断开复位 0（[clear] 一并复位）。
     */
    @Volatile
    private var inCallState: Int = 0

    private val directMusicClient = object : MusicClient() {
        override fun getMediaSourceTypeList(): IntArray = LINK_SOURCE_TYPES

        override fun getCurrentSourceType(): Int = SOURCE_TYPE_HICAR

        override fun getCurrentProgress(): Long = latestSnapshot?.positionMs ?: 0L

        override fun getMusicPlaybackInfo(): MusicPlaybackInfo? = latestPlaybackInfo

        override fun onPlay(): Boolean {
            if (inCallState != 0) {
                Log.i(TAG, "通话中（callState=$inCallState），忽略 EAS 卡片的恢复播放指令")
                return true
            }
            onCommand(MediaKeyForwarder.Command.PLAY)
            return true
        }

        override fun onReplay(): Boolean {
            if (inCallState != 0) {
                Log.i(TAG, "通话中（callState=$inCallState），忽略 EAS 卡片的重播指令")
                return true
            }
            onCommand(MediaKeyForwarder.Command.PLAY)
            return true
        }

        override fun ctrlPlayMediaList(i: Int): Boolean {
            onCommand(MediaKeyForwarder.Command.PLAY)
            return true
        }

        override fun onSourceSelected(i: Int): Boolean {
            if (i == SOURCE_TYPE_HICAR || i == SOURCE_TYPE_CARLINK || i == SOURCE_TYPE_FLYMELINK) {
                onCommand(MediaKeyForwarder.Command.PLAY)
            }
            return true
        }

        override fun onPause(): Boolean {
            onCommand(MediaKeyForwarder.Command.PAUSE)
            return true
        }

        override fun ctrlPauseMediaList(i: Int): Boolean {
            onCommand(MediaKeyForwarder.Command.PAUSE)
            return true
        }

        override fun onExit(): Boolean {
            onCommand(MediaKeyForwarder.Command.PAUSE)
            return true
        }

        override fun onNext(): Boolean {
            onCommand(MediaKeyForwarder.Command.NEXT)
            return true
        }

        override fun onForward(): Boolean {
            onCommand(MediaKeyForwarder.Command.NEXT)
            return true
        }

        override fun onMediaForward(z: Boolean): Boolean {
            if (z) onCommand(MediaKeyForwarder.Command.NEXT)
            return true
        }

        override fun onPrevious(): Boolean {
            onCommand(MediaKeyForwarder.Command.PREVIOUS)
            return true
        }

        override fun onRewind(): Boolean {
            onCommand(MediaKeyForwarder.Command.PREVIOUS)
            return true
        }

        override fun onMediaRewind(z: Boolean): Boolean {
            if (z) onCommand(MediaKeyForwarder.Command.PREVIOUS)
            return true
        }
    }

    private val btFallbackRunnable = object : Runnable {
        override fun run() {
            if (!audioFocusHeld) return
            val app = appContext ?: return
            val now = SystemClock.elapsedRealtime()
            // 若超过 3.5 秒没有收到手机 515 报文（或 515 尚未给出有效歌名），尝试从车机蓝牙 AVRCP MediaSession 兜底读取
            if (title.isEmpty() || now - last515AtElapsedMs > 3_500L) {
                pollBluetoothFallbackIfNeeded(app)
            }
            main.postDelayed(this, 1_500L)
        }
    }

    private val delayedLyricPushRunnable = Runnable {
        val app = appContext ?: return@Runnable
        val snap = currentSnapshot() ?: return@Runnable
        if (!isCarMediaInstalled(app)) return@Runnable
        if (snap.playing && snap.title.isNotEmpty()) {
            requestCarMediaSwitchSource(app, "延时确认音源(${snap.title})")
        }
        sendCarMediaLyricBroadcast(app, snap)
    }

    /** 初始化上下文引用；若车机未安装 CarMedia（或 CarMedia 关闭了 EAS 推送），预热直连 Link EAS 通道。 */
    fun ensureInitialized(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        if (!isCarMediaHandlingEas(app)) {
            ensureDirectEasInitialized(app)
        }
    }

    /** 手机 504 通话状态变化（原厂对齐）：[state] 0 空闲、非 0 通话中。通话期间暂停音乐推送与焦点抢占。 */
    fun onCallStateChanged(state: Int) {
        if (inCallState == state) return
        Log.i(TAG, "手机通话状态：$inCallState -> $state${if (state != 0) "（通话期间不推音乐、不抢焦点）" else "（恢复音乐推送）"}")
        inCallState = state
    }

    /** 音频焦点状态变化：拿到媒体焦点时触发 CarMedia 音源切换或直推 Link EAS 卡片，并开启蓝牙 AVRCP 元数据兜底轮询。 */
    fun onAudioFocusChanged(context: Context, held: Boolean, onSnapshotUpdated: (Snapshot) -> Unit) {
        val app = context.applicationContext ?: context
        appContext = app
        sessionCallback = onSnapshotUpdated
        audioFocusHeld = held
        main.removeCallbacks(btFallbackRunnable)
        if (held) {
            // 通话中（504）：不向 CarMedia 切音源、不推 EAS 卡片，等挂断后 515 推送自然恢复。
            if (inCallState != 0) {
                Log.i(TAG, "通话中（callState=$inCallState），拿到媒体焦点也不切音源/推卡片")
                return
            }
            if (title.isEmpty()) {
                pollBluetoothFallbackIfNeeded(app)
            }
            if (isCarMediaInstalled(app)) {
                requestCarMediaSwitchSource(app, "拿到媒体焦点")
            }
            if (!isCarMediaHandlingEas(app)) {
                ensureDirectEasInitialized(app)
            }
            currentSnapshot()?.let { snap ->
                pushToCarMedia(app, snap, forceSwitch = true)
            }
            main.postDelayed(btFallbackRunnable, 1_000L)
        }
    }

    /** 收到一条 515 数据（全量包或每秒进度包），合并状态并返回最新快照供 MediaKeySession、CarMedia 与 Link EAS 卡片同步。 */
    fun onMetadata(context: Context, data: DmsdpMediaData, onAsyncLyricReady: (Snapshot) -> Unit): Snapshot {
        val app = context.applicationContext ?: context
        appContext = app
        sessionCallback = onAsyncLyricReady
        last515AtElapsedMs = SystemClock.elapsedRealtime()

        val newTitle = data.title
        val newArtist = data.artist
        val newAlbum = data.album
        val newAppName = data.appName
        val newAppPackage = data.appPackage
        val newTotalSec = data.totalTimeSec
        val newElapsedSec = data.elapsedTimeSec
        val newStatus = data.status
        val newArtUrl = data.albumArtUrl
        val newLyricContent = data.lyricContent
        val newCurrentLyric = data.currentLyric

        val trackChanged = !newTitle.isNullOrEmpty() && newTitle.trim() != title
        if (!newTitle.isNullOrEmpty()) title = newTitle.trim()
        if (!newArtist.isNullOrEmpty()) artist = newArtist.trim()
        if (!newAlbum.isNullOrEmpty()) album = newAlbum.trim()
        if (!newAppName.isNullOrEmpty()) appName = newAppName.trim()
        if (!newAppPackage.isNullOrEmpty()) appPackage = newAppPackage.trim()
        if (newTotalSec != null && newTotalSec > 0) totalTimeSec = newTotalSec
        if (newElapsedSec != null && newElapsedSec >= 0) elapsedTimeSec = newElapsedSec
        if (newStatus != null) status = newStatus

        if (trackChanged) {
            lyricContent = ""
            currentLyric = ""
            HiCarLyricResolver.rememberTrack(app, title, artist)
        } else if (title.isNotEmpty() || artist.isNotEmpty()) {
            HiCarLyricResolver.rememberTrack(app, title, artist)
        }

        // 封面解码与缓存复用（同一张封面不重复写盘）。
        val artBytes = data.albumArtBytes
        if (artBytes != null && artBytes.isNotEmpty()) {
            val hash = artBytes.contentHashCode()
            if (hash != artworkHash || artworkBitmap == null) {
                val decoded = runCatching { BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) }.getOrNull()
                if (decoded != null) {
                    artworkHash = hash
                    artworkBitmap = decoded
                    artworkUri = ClusterArtProvider.saveCover(app, decoded)
                }
            }
        } else if (!newArtUrl.isNullOrEmpty()) {
            artworkUri = runCatching { Uri.parse(newArtUrl) }.getOrNull() ?: artworkUri
        }

        // 歌词处理：优先用 515 报文自带的 LRC / 当前句；没有时走 HiCarLyricResolver 在线拉取与缓存解析。
        val asyncCallback: () -> Unit = {
            main.post {
                val refreshed = resolveLyricsOnly(app)
                if (refreshed != null) {
                    onAsyncLyricReady(refreshed)
                    pushToCarMedia(app, refreshed, forceSwitch = false, forceLyric = true)
                }
            }
        }
        if (!newLyricContent.isNullOrEmpty()) {
            lyricContent = newLyricContent
            HiCarLyricResolver.saveLyric(app, title, artist, lyricContent)
        } else if (lyricContent.isEmpty() && title.isNotEmpty()) {
            lyricContent = HiCarLyricResolver.resolveContent(app, title, artist, asyncCallback)
        }

        if (!newCurrentLyric.isNullOrEmpty() && HiCarLyricResolver.isValidLyric(newCurrentLyric)) {
            currentLyric = newCurrentLyric.trim()
        } else if (lyricContent.isNotEmpty()) {
            val line = HiCarLyricResolver.findCurrentLine(lyricContent, elapsedTimeSec)
            if (HiCarLyricResolver.isValidLyric(line)) currentLyric = line
        } else if (title.isNotEmpty()) {
            val line = HiCarLyricResolver.resolve(app, title, artist, elapsedTimeSec, asyncCallback)
            if (HiCarLyricResolver.isValidLyric(line)) currentLyric = line
        }

        return buildSnapshot()
    }

    /**
     * 在 MediaKeySession 已经更新完 MediaMetadata 与 PlaybackState 之后调用：
     * - 若车机装有 `CarMedia`（`com.ecarx.carmedia`），向其发送 `SWITCH_SOURCE` 与 `lyricchanged` 歌词广播；
     * - 若车机未安装 `CarMedia`（或 `CarMedia` 关闭了 `EAS推送`），自动切换到 `CarLink` 同款直推模式，
     *   主动向车机 `ECARX MediaCenterAPI` 推送 **Link EAS 卡片**（`sourceType = 16`）与仪表屏实时歌词。
     */
    fun pushToCarMedia(
        context: Context,
        snapshot: Snapshot,
        forceSwitch: Boolean = false,
        forceLyric: Boolean = false,
    ) {
        val app = context.applicationContext ?: context
        appContext = app
        latestSnapshot = snapshot

        // 通话中（504）：不切音源、不推 EAS 卡片（不抢焦点），歌词广播照常（不影响焦点）。
        val inCall = inCallState != 0

        val carMediaInstalled = isCarMediaInstalled(app)
        val carMediaEasActive = carMediaInstalled && isCarMediaEasEnabled(app)

        if (carMediaInstalled && !inCall) {
            val shouldSwitch = forceSwitch || (snapshot.playing && snapshot.title.isNotEmpty() && snapshot.title != lastSwitchedTitle)
            if (shouldSwitch && snapshot.title.isNotEmpty()) {
                lastSwitchedTitle = snapshot.title
                requestCarMediaSwitchSource(app, "曲目播放(${snapshot.title})")
            }

            val lrcPayload = buildCarMediaLrcPayload(snapshot)
            if (lrcPayload.isNotEmpty()) {
                val now = SystemClock.elapsedRealtime()
                val hash = lrcPayload.hashCode()
                val changed = forceLyric || snapshot.title != lastBroadcastLrcTitle || hash != lastBroadcastLrcHash
                if (changed || now - lastBroadcastAtElapsedMs >= 2_500L) {
                    lastBroadcastLrcTitle = snapshot.title
                    lastBroadcastLrcHash = hash
                    lastBroadcastAtElapsedMs = now
                    sendCarMediaLyricBroadcast(app, snapshot, lrcPayload)
                    if (changed) {
                        main.removeCallbacks(delayedLyricPushRunnable)
                        main.postDelayed(delayedLyricPushRunnable, 350L)
                        main.postDelayed(delayedLyricPushRunnable, 1_500L)
                    }
                }
            }
        }

        if (carMediaEasActive) {
            // CarMedia 正在接管 EAS 推送：若本应用之前注册过直推 EAS，主动注销让出焦点
            if (easRegistered || easToken != null) {
                teardownDirectEas("检测到 CarMedia 已开启 EAS 推送，交由 CarMedia 接管")
            }
        } else if (inCall) {
            Log.i(TAG, "通话中（callState=$inCallState），跳过 Link EAS 卡片推送，不抢音乐焦点")
        } else {
            // 车机未安装 CarMedia（或 CarMedia 关闭了 EAS 推送）：主动直推 Link EAS 卡片到仪表盘与桌面
            pushDirectLinkEasCard(app, snapshot, forceFocus = forceSwitch)
        }
    }

    /** 当前是否有已缓存的歌曲信息。 */
    fun currentSnapshot(): Snapshot? = if (title.isEmpty() && artist.isEmpty()) null else buildSnapshot()

    /** 连接断开时清空缓存并通知仪表屏断开。 */
    fun clear() {
        main.removeCallbacks(btFallbackRunnable)
        main.removeCallbacks(delayedLyricPushRunnable)
        notifyDirectEasDisconnected()
        audioFocusHeld = false
        inCallState = 0
        last515AtElapsedMs = 0L
        lastSwitchedTitle = ""
        lastBroadcastLrcTitle = ""
        lastBroadcastLrcHash = 0
        lastBroadcastAtElapsedMs = 0L
        lastDirectEasTitle = ""
        lastDirectEasPlaying = false
        title = ""
        artist = ""
        album = ""
        totalTimeSec = 0
        elapsedTimeSec = 0
        status = STATUS_PAUSED
        artworkBitmap = null
        artworkHash = 0
        artworkUri = null
        lyricContent = ""
        currentLyric = ""
        latestSnapshot = null
        latestPlaybackInfo = null
    }

    /** 判断车机是否安装了 `com.ecarx.carmedia` 且处于启用状态。 */
    private fun isCarMediaInstalled(app: Context): Boolean = try {
        val info = app.packageManager.getApplicationInfo(PKG_CARMEDIA, 0)
        info != null && info.enabled
    } catch (_: Throwable) {
        false
    }

    /**
     * 判断 `com.ecarx.carmedia` 是否开启了 `EAS推送`（`carmedia_prefs` -> `dim_auto_push`，默认 `true`）。
     * 本应用与 `CarMedia` 共享 `android.uid.system`，可直接跨包读取其 SharedPreferences；若读取异常默认视为开启。
     */
    private fun isCarMediaEasEnabled(app: Context): Boolean = try {
        val pkgCtx = app.createPackageContext(PKG_CARMEDIA, 0)
        val sp = pkgCtx.getSharedPreferences(CARMEDIA_PREFS, Context.MODE_PRIVATE)
        sp.getBoolean(PREF_DIM_AUTO_PUSH, true)
    } catch (_: Throwable) {
        true
    }

    private fun isCarMediaHandlingEas(app: Context): Boolean =
        isCarMediaInstalled(app) && isCarMediaEasEnabled(app)

    /**
     * 初始化直推 Link EAS 卡片通道（参考 `CarLink优化版` `MediaCtrlMgr.init` + `MediaCenterBridge.init`）。
     */
    private fun ensureDirectEasInitialized(app: Context) {
        val api = easApi
        if (api != null && easRegistered && easToken != null && api.isReady && api.isServiceAlive) {
            return
        }
        if (easInitializing) return
        easInitializing = true
        try {
            val instance = MediaCenterAPI.get(app)
            easApi = instance
            instance.init(app, ECarXApiClient.Callback { ready ->
                main.post {
                    easInitializing = false
                    if (!ready) {
                        Log.w(TAG, "直连 ECARX MediaCenterAPI onAPIReady=false")
                        return@post
                    }
                    registerDirectEasClient(app)
                }
            })
        } catch (t: Throwable) {
            easInitializing = false
            Log.w(TAG, "初始化 ECARX MediaCenterAPI 失败：${t.message}")
        }
    }

    private fun registerDirectEasClient(app: Context) {
        val api = easApi ?: return
        if (!api.isReady) return
        try {
            easToken?.let { old ->
                runCatching { api.unregister(old) }
                easToken = null
                easRegistered = false
            }
            val token = api.registerMusic(app.packageName, directMusicClient)
            if (token == null) {
                Log.w(TAG, "直连 ECARX MediaCenterAPI registerMusic 返回空 token")
                return
            }
            easToken = token
            easRegistered = true
            // 与吉利 CarLink 优化版 MediaCtrlMgr 保持一致：声明 Link 音源列表 [16(HiCar), 36(CarLink), 20(FlymeLink)]
            api.updateMediaSourceTypeList(token, LINK_SOURCE_TYPES)
            api.declareMediaCenterCapability(token, intArrayOf(0, 2, 3))
            api.declareSupportCollectTypes(token, intArrayOf(0, 3, 4))
            api.updateCurrentSourceType(token, SOURCE_TYPE_HICAR)
            Log.i(TAG, "直连 ECARX MediaCenterAPI 注册成功（Link EAS 卡片模式，token=$token）")

            // 注册完成后若已有正在播放的曲目，立即补推一次 Link EAS 卡片
            latestSnapshot?.let { snap ->
                pushDirectLinkEasCard(app, snap, forceFocus = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "注册直连 ECARX MusicClient 失败：${t.message}")
        }
    }

    /**
     * 参考吉利 `CarLink优化版`（`MediaCtrlMgr.startHiCarMusic` + `HiCarMediaPatch.forceTitleLyric` + `HiCarInteractiveBridge`），
     * 直接向车机 `ECARX MediaCenterAPI` 推送 **Link EAS 卡片**（`sourceType = 16`）：
     * - 仪表盘/桌面 Link 卡片将 `title` 作为主滚动文本展示：当有实时歌词句时，将 `title` 设为当前歌词句、`album` 设为原歌名、`artist` 设为 `歌名 - 歌手`，实现仪表盘实时歌词滚动；
     * - 同时调用 `updateCurrentLyric`、`updateMusicPlaybackState`、`updateCurrentProgress` 与 `updatePlayState`。
     */
    private fun pushDirectLinkEasCard(app: Context, snapshot: Snapshot, forceFocus: Boolean) {
        ensureInteractiveManagerActive(app)
        val api = easApi
        val token = easToken
        if (api == null || token == null || !easRegistered || !api.isReady || !api.isServiceAlive) {
            ensureDirectEasInitialized(app)
            return
        }

        try {
            val realTitle = snapshot.title.ifEmpty { "HiCar" }
            val realArtist = snapshot.artist
            val realAlbum = snapshot.album
            val lyricLine = snapshot.currentLyric.trim()
            val hasValidLyric = HiCarLyricResolver.isValidLyric(lyricLine)

            val info = MusicPlaybackInfo().apply {
                if (hasValidLyric) {
                    // 与 CarLink 优化版 HiCarMediaPatch.forceTitleLyric 一致：将实时歌词写入 Title 驱动仪表盘与 Link EAS 卡片显示歌词
                    setTitle(lyricLine)
                    setAlbum(realTitle)
                    setArtist(
                        if (realArtist.isNotEmpty() && !realTitle.contains(realArtist)) {
                            "$realTitle - $realArtist"
                        } else {
                            realTitle
                        },
                    )
                    setCurrentLyricSentence(lyricLine)
                } else {
                    setTitle(realTitle)
                    setArtist(realArtist.ifEmpty { "HiCar" })
                    setAlbum(realAlbum)
                    setCurrentLyricSentence(LYRIC_PLACEHOLDER)
                }
                setLyricContent(snapshot.lyricContent.ifEmpty { LYRIC_PLACEHOLDER })
                setLyric(Uri.EMPTY)
                setDuration(snapshot.durationMs)
                setPlaybackStatus(if (snapshot.playing) 1 else 0)
                setSourceType(SOURCE_TYPE_HICAR)
                setPackageName(app.packageName)
                setAppName(snapshot.appName.ifEmpty { "HiCar" })
                setAppIcon("drawable://ic_launcher_hicar")
                setMediaType("music")
                setSupportLoopModeSwitch(true)
                setSupportVrCtrlPlayStatus(true)
                setSupportCollect(false)
                setCollected(false)
                setPlayingMediaListId("${app.packageName}-play-list-common")
                setUuid("$realTitle|$realArtist")
                val artUri = snapshot.artworkUri
                    ?: snapshot.artworkBitmap?.let { bmp -> ClusterArtProvider.saveCover(app, bmp) }
                setArtwork(artUri)
                val pi = buildLaunchPendingIntent(app)
                setLaunchIntent(pi)
                setPlayerIntent(pi)
            }

            latestPlaybackInfo = info

            val now = SystemClock.elapsedRealtime()
            val trackChanged = realTitle != lastDirectEasTitle
            val playStateChanged = snapshot.playing != lastDirectEasPlaying
            lastDirectEasTitle = realTitle
            lastDirectEasPlaying = snapshot.playing

            if (snapshot.playing || forceFocus || trackChanged || playStateChanged) {
                val currentFocus = runCatching { api.queryCurrentFocusClient(token) }.getOrDefault("")
                if (forceFocus || trackChanged || playStateChanged || currentFocus != app.packageName || now - lastDirectEasFocusGrabMs >= 4_000L) {
                    lastDirectEasFocusGrabMs = now
                    api.requestPlay(token)
                    api.updateCurrentSourceType(token, SOURCE_TYPE_HICAR)
                }
            }

            api.updateCurrentSourceType(token, SOURCE_TYPE_HICAR)
            api.updateMusicPlaybackState(token, info)
            if (hasValidLyric) {
                api.updateCurrentLyric(token, lyricLine)
            }
            api.updateCurrentProgress(token, snapshot.positionMs)
            api.updatePlayState(app.packageName, if (snapshot.playing) 1 else 0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "直推 Link EAS 卡片失败：${t.message}")
        }
    }

    /**
     * 参考 `CarLink优化版` 的 `HiCarInteractiveBridge.ensureActive`：
     * 通过反射通知车机 `ECarXInteractiveManager` 当前手机互联已连接并处于活跃显示状态，使仪表屏允许展示互联卡片。
     */
    private fun ensureInteractiveManagerActive(app: Context) {
        if (interactiveActiveNotified) return
        try {
            val mgr = interactiveManager ?: run {
                val cls = Class.forName("com.ecarx.ecarxinteractiveapi.ECarXInteractiveManager")
                cls.getConstructor(Context::class.java).newInstance(app)
            }.also { interactiveManager = it }
            val cls = mgr.javaClass
            val pkg = app.packageName
            runCatching {
                cls.getMethod("notifyPhoneConnectStatuschanged", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(mgr, pkg, 1)
            }
            runCatching { cls.getMethod("setEnabledDisplayApp").invoke(mgr) }
            runCatching {
                cls.getMethod("onHMIActivityShowing", String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(mgr, pkg, true)
            }
            interactiveActiveNotified = true
            Log.i(TAG, "已通知 ECarXInteractiveManager 激活互联卡片状态")
        } catch (_: Throwable) {
            // 部分车型无此系统库时静默忽略
            interactiveActiveNotified = true
        }
    }

    private fun notifyDirectEasDisconnected() {
        val api = easApi ?: return
        val token = easToken ?: return
        if (!easRegistered) return
        try {
            api.updateErrorMsg(token, SOURCE_TYPE_HICAR, "手机互联断开连接")
        } catch (_: Throwable) {
        }
    }

    private fun teardownDirectEas(reason: String) {
        try {
            val api = easApi
            val token = easToken
            if (api != null && token != null && easRegistered) {
                runCatching { api.unregister(token) }
            }
            Log.i(TAG, "已释放直连 EAS 通道（$reason）")
        } catch (_: Throwable) {
        } finally {
            easToken = null
            easRegistered = false
        }
    }

    private fun buildLaunchPendingIntent(app: Context): PendingIntent? = try {
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getActivity(app, 0, intent, flags)
    } catch (_: Throwable) {
        null
    }

    private fun resolveLyricsOnly(app: Context): Snapshot? {
        if (title.isEmpty()) return null
        val cached = HiCarLyricResolver.resolveContent(app, title, artist)
        if (cached.isNotEmpty()) lyricContent = cached
        val line = HiCarLyricResolver.resolve(app, title, artist, elapsedTimeSec)
        if (HiCarLyricResolver.isValidLyric(line)) {
            currentLyric = line
        }
        return buildSnapshot()
    }

    private fun buildSnapshot(): Snapshot = Snapshot(
        title = title.ifEmpty { "HiCar" },
        artist = artist,
        album = album,
        appName = appName.ifEmpty { "HiCar" },
        appPackage = appPackage,
        durationMs = totalTimeSec.coerceAtLeast(0) * 1000L,
        positionMs = elapsedTimeSec.coerceAtLeast(0) * 1000L,
        playing = isPlaying(status),
        artworkBitmap = artworkBitmap,
        artworkUri = artworkUri,
        lyricContent = lyricContent,
        currentLyric = currentLyric,
    )

    /**
     * 当手机播放未发 515 的音源、但车机蓝牙 AVRCP（`com.android.bluetooth`）有曲目信息时，
     * 从 `com.android.bluetooth` 的 MediaSession 读取歌名、歌手、时长与进度并触发在线歌词解析与 CarMedia / Link EAS 推送。
     */
    private fun pollBluetoothFallbackIfNeeded(app: Context) {
        try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val sessions: List<MediaController> = runCatching { msm.getActiveSessions(null) }.getOrNull() ?: return
            val bt = sessions.firstOrNull { it.packageName == PKG_BLUETOOTH } ?: return
            val md = bt.metadata ?: return
            val btTitle = (md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: "").trim()
            if (btTitle.isEmpty()) return
            val btArtist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "").trim()
            val btAlbum = (md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "").trim()
            val btDurationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
            val ps = bt.playbackState
            val btPosMs = if (ps != null && ps.position >= 0L) {
                val base = ps.position
                val updated = ps.lastPositionUpdateTime
                val now = SystemClock.elapsedRealtime()
                if (ps.state == PlaybackState.STATE_PLAYING && updated > 0L && now > updated) {
                    base + (now - updated)
                } else {
                    base
                }
            } else {
                elapsedTimeSec.coerceAtLeast(0) * 1000L
            }

            val trackChanged = btTitle != title || (btArtist.isNotEmpty() && btArtist != artist)
            title = btTitle
            if (btArtist.isNotEmpty()) artist = btArtist
            if (btAlbum.isNotEmpty()) album = btAlbum
            if (btDurationMs > 0L) totalTimeSec = (btDurationMs / 1000L).toInt()
            elapsedTimeSec = (btPosMs / 1000L).toInt().coerceAtLeast(0)
            status = if (audioFocusHeld) STATUS_PLAYING else STATUS_PAUSED

            val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (art != null && !art.isRecycled) {
                artworkBitmap = art
                if (trackChanged || artworkUri == null) {
                    artworkUri = ClusterArtProvider.saveCover(app, art)
                }
            } else if (trackChanged) {
                artworkBitmap = null
                artworkUri = null
            }

            val cb = sessionCallback
            val asyncCallback: () -> Unit = {
                main.post {
                    val refreshed = resolveLyricsOnly(app)
                    if (refreshed != null) {
                        cb?.invoke(refreshed)
                        pushToCarMedia(app, refreshed, forceSwitch = false, forceLyric = true)
                    }
                }
            }
            if (trackChanged) {
                lyricContent = ""
                currentLyric = ""
                HiCarLyricResolver.rememberTrack(app, title, artist)
                Log.i(TAG, "从蓝牙 AVRCP 补齐曲目元数据：$title / $artist（时长 ${totalTimeSec}s）")
            }
            if (lyricContent.isEmpty()) {
                lyricContent = HiCarLyricResolver.resolveContent(app, title, artist, asyncCallback)
            }
            if (lyricContent.isNotEmpty()) {
                val line = HiCarLyricResolver.findCurrentLine(lyricContent, elapsedTimeSec)
                if (HiCarLyricResolver.isValidLyric(line)) currentLyric = line
            } else {
                val line = HiCarLyricResolver.resolve(app, title, artist, elapsedTimeSec, asyncCallback)
                if (HiCarLyricResolver.isValidLyric(line)) currentLyric = line
            }

            val snapshot = buildSnapshot()
            cb?.invoke(snapshot)
            pushToCarMedia(app, snapshot, forceSwitch = trackChanged, forceLyric = trackChanged)
        } catch (t: Throwable) {
            Log.w(TAG, "蓝牙 AVRCP 元数据兜底读取失败：${t.message}")
        }
    }

    private fun requestCarMediaSwitchSource(app: Context, why: String) {
        try {
            val intent = Intent(ACTION_CARMEDIA_SWITCH_SOURCE).apply {
                component = ComponentName(PKG_CARMEDIA, CLS_CARMEDIA_SWITCH_RECEIVER)
                putExtra("pkg", app.packageName)
            }
            app.sendBroadcast(intent)
            Log.i(TAG, "已通知 CarMedia 切换音源到 OpenHC（$why）")
        } catch (t: Throwable) {
            Log.w(TAG, "通知 CarMedia 切换音源失败：${t.message}")
        }
    }

    private fun buildCarMediaLrcPayload(snapshot: Snapshot): String {
        val raw = snapshot.lyricContent.trim()
        if (raw.isNotEmpty() && raw.contains('[') && raw.contains(']')) {
            return raw
        }
        val single = snapshot.currentLyric.trim()
        if (single.isNotEmpty() && HiCarLyricResolver.isValidLyric(single)) {
            return "[00:00.00]$single\n[99:59.00] "
        }
        return ""
    }

    private fun sendCarMediaLyricBroadcast(
        app: Context,
        snapshot: Snapshot,
        payload: String = buildCarMediaLrcPayload(snapshot),
    ) {
        if (snapshot.title.isEmpty() || payload.isEmpty()) return
        try {
            for (action in CARMEDIA_LYRIC_ACTIONS) {
                val intent = Intent(action).apply {
                    setPackage(PKG_CARMEDIA)
                    putExtra("package", app.packageName)
                    putExtra("pkg", app.packageName)
                    putExtra("packagename", app.packageName)
                    putExtra("title", snapshot.title)
                    putExtra("artist", snapshot.artist)
                    putExtra("album", snapshot.album)
                    putExtra("lyric", payload)
                }
                app.sendBroadcast(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "向 CarMedia 发送歌词广播失败：${t.message}")
        }
    }

    companion object {
        private const val TAG = "OpenHC-ClusterMedia"

        private const val PKG_CARMEDIA = "com.ecarx.carmedia"
        private const val CARMEDIA_PREFS = "carmedia_prefs"
        private const val PREF_DIM_AUTO_PUSH = "dim_auto_push"
        private const val CLS_CARMEDIA_SWITCH_RECEIVER = "com.ecarx.carmedia.SourceSwitchReceiver"
        private const val ACTION_CARMEDIA_SWITCH_SOURCE = "com.ecarx.carmedia.SWITCH_SOURCE"
        private const val PKG_BLUETOOTH = "com.android.bluetooth"
        private val CARMEDIA_LYRIC_ACTIONS = arrayOf(
            "com.android.music.lyricchanged",
            "com.netease.cloudmusic.lyricchanged",
        )

        // 吉利 CarLink 优化版（com.geely.linkhmi）注册的 Link 音源类型列表：16=HiCar, 36=CarLink, 20=FlymeLink
        private const val SOURCE_TYPE_HICAR = 16
        private const val SOURCE_TYPE_CARLINK = 36
        private const val SOURCE_TYPE_FLYMELINK = 20
        private val LINK_SOURCE_TYPES = intArrayOf(SOURCE_TYPE_HICAR, SOURCE_TYPE_CARLINK, SOURCE_TYPE_FLYMELINK)
        private const val LYRIC_PLACEHOLDER = "   "

        private const val STATUS_PLAYING = 3
        private const val STATUS_PAUSED = 2
        private const val STATUS_BUFFERING = 6

        private fun isPlaying(status: Int): Boolean = status == STATUS_PLAYING || status == STATUS_BUFFERING
    }
}
