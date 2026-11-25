package io.github.gcbond.openhc.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.github.gcbond.openhc.core.dmsdp.DmsdpMediaData
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.session.SessionAction
import io.github.gcbond.openhc.core.session.SessionOutput
import io.github.gcbond.openhc.media.CarIntegrationBridge
import io.github.gcbond.openhc.media.ClusterMediaBridge
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.media.MediaKeyForwarder
import io.github.gcbond.openhc.platform.media.MediaKeySession

/**
 * 车机的方向盘、媒体键转给手机（全进程唯一；有线和无线同时只有一个连接）。日志标签 `OpenHC-Keys`。
 *
 * 两个入口共用一个 [MediaKeyForwarder]（配对按下抬起、去掉重复）：
 * - **前台窗口**：主界面先把按键交给 [onWindowKey]，是媒体键、又有能发键的连接就吃掉并转给手机；
 * - **媒体按钮会话**（[MediaKeySession]）：补本应用不在前台（例如回到车机桌面）的情况。只在「按键已就绪」且
 *   「我们替手机拿着媒体焦点」时存在；长期失去媒体焦点（车机收音机等）、手机放弃焦点、连接结束时关掉，
 *   方向盘键交还车机。
 *
 * 连接这边：手机选回了按键、接受了 PLAY 后调 [ready]，收尾时调 [gone]；音频焦点的变化经 [mediaFocus] 报进来
 * （可以早于 [ready]）。公开方法任意线程可调，状态都在主线程上改；[onWindowKey] 只在主线程上调。
 */
object MediaKeys {
    private const val TAG = "OpenHC-Keys"

    /** 一次连接：[label] 用于日志，[send] 把一个按键投到协议线程上发出（不阻塞，主线程上调用）。 */
    class Target(val label: String, val send: (HiSightInput.Key) -> Unit) {
        internal var keysReady = false
        internal var mediaFocus = false
        internal var retired = false
    }

    private val main = Handler(Looper.getMainLooper())

    // 以下只在主线程上访问。
    private val forwarder = MediaKeyForwarder()
    private val clusterBridge = ClusterMediaBridge { command ->
        main.post { SessionListener.onCommand(command) }
    }
    private var appContext: Context? = null
    private var target: Target? = null
    private var session: MediaKeySession? = null

    /** 这次连接可以发键了（手机选回了按键事件、接受了 PLAY）。 */
    fun ready(context: Context, target: Target) {
        val app = context.applicationContext ?: context
        main.post {
            if (target.retired) return@post
            appContext = app
            clusterBridge.ensureInitialized(app)
            this.target?.takeIf { it !== target }?.let {
                Log.w(TAG, "上一个连接（${it.label}）没报结束，按已结束处理")
                forwarder.ready = false
            }
            this.target = target
            target.keysReady = true
            forwarder.ready = true
            Log.i(TAG, "按键已就绪（${target.label}）：方向盘、媒体键转给手机；替手机拿着媒体焦点：${yesNo(target.mediaFocus)}")
            updateSession("按键就绪")
        }
    }

    /** 连接收尾：不再转键，关掉媒体按钮会话，清空仪表屏歌曲信息。可重复调用。 */
    fun gone(target: Target) {
        main.post {
            target.retired = true
            target.keysReady = false
            target.mediaFocus = false
            if (this.target !== target) return@post
            this.target = null
            forwarder.ready = false
            clusterBridge.clear()
            Log.i(TAG, "按键停用（${target.label}）：连接结束，媒体键交还车机")
            updateSession("连接结束")
        }
    }

    /** 收到手机 505 语音拾音状态（任意线程）：抑制/恢复车机本地语音（反射，无对应系统库时静默跳过）。 */
    fun onVoiceStateChanged(context: Context, state: Int) {
        val app = context.applicationContext ?: context
        main.post { CarIntegrationBridge.enableVr(app, state == 0) }
    }

    /** 收到手机 504 通话状态（任意线程）：同步给 [ClusterMediaBridge]（通话期间不推音乐、不抢焦点）。 */
    fun onCallStateChanged(state: Int) {
        main.post { clusterBridge.onCallStateChanged(state) }
    }

    /** 收到手机 503 导航焦点 HICAR（任意线程）：让出车机原厂导航（反射，无对应系统库时静默跳过）。 */
    fun onNaviFocusRequested(context: Context) {
        val app = context.applicationContext ?: context
        main.post { CarIntegrationBridge.yieldNativeNavi(app) }
    }

    /** 收到手机 515 推来的音频元数据或播放进度：同步到 MediaKeySession（供 CoolHome 与 CarMedia 读取）并推送歌词到 CarMedia。 */
    fun onMetadata(context: Context, metadata: DmsdpMediaData) {
        val app = context.applicationContext ?: context
        main.post {
            appContext = app
            val snapshot = clusterBridge.onMetadata(app, metadata) { refreshed ->
                main.post {
                    updateSession("异步歌词就绪")
                    applySnapshotToSession(refreshed)
                }
            }
            updateSession("收到媒体元数据")
            applySnapshotToSession(snapshot)
            clusterBridge.pushToCarMedia(app, snapshot)
        }
    }

    /** 我们是不是在替手机拿着媒体焦点（音频焦点控制报来的变化）。 */
    fun mediaFocus(target: Target, held: Boolean) {
        main.post {
            if (target.retired || target.mediaFocus == held) return@post
            target.mediaFocus = held
            if (this.target !== target) return@post
            Log.i(TAG, "替手机拿着媒体焦点：${yesNo(held)}（${target.label}）")
            updateSession(if (held) "拿到媒体焦点" else "媒体焦点放掉了")
            if (held) {
                session?.pulseActive()
            }
            val app = appContext
            if (app != null) {
                clusterBridge.onAudioFocusChanged(app, held) { refreshed ->
                    main.post {
                        updateSession("蓝牙兜底元数据")
                        applySnapshotToSession(refreshed)
                    }
                }
            }
        }
    }

    /** 投屏参数协商完了（任意线程）：记一行这次能不能转键。 */
    fun negotiated(target: Target, declared: Boolean, keyInput: Boolean) {
        when {
            !declared -> Log.i(TAG, "（${target.label}）设置里关了方向盘切歌，这次没声明按键")
            keyInput -> Log.i(TAG, "（${target.label}）手机选回了按键事件，手机接受 PLAY 后开始转键")
            else -> Log.w(TAG, "（${target.label}）手机没选回按键事件或没给回控端口：这次方向盘切歌不可用，投屏照常")
        }
    }

    /**
     * 协议线程上把一个按键交给会话（[send] 是会话的 sendInput），写一行发出了多少字节。不合法或顺序不对只记日志、
     * 不影响连接。不走触摸那条路：那边按触摸是否就绪放行，出错还会清掉触摸状态。
     */
    fun sendNow(label: String, key: HiSightInput.Key, send: (HiSightInput) -> List<SessionOutput>): List<SessionOutput> {
        val what = "${if (key.action == HiSightInput.Key.Action.DOWN) "按下" else "抬起"} ${MediaKeyForwarder.label(key.keyCode)}"
        return try {
            val outputs = send(key)
            val bytes = outputs.sumOf { (it as? SessionAction.Write)?.bytes?.size ?: 0 }
            if (bytes > 0) {
                Log.i(TAG, "（$label）回控连接写出按键包：$what，$bytes 字节")
            } else {
                Log.w(TAG, "（$label）$what 没发出：回控连接还没就绪或会话已结束")
            }
            outputs
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "（$label）$what 没发出：${e.message}")
            emptyList()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "（$label）$what 没发出：${e.message}")
            emptyList()
        }
    }

    /**
     * 主界面收到的按键（主线程，在交给界面之前）。返回 true 表示吃掉了（转给了手机，或是已转过的按键的后续），
     * 调用方不要再往下派；返回 false 照常处理。
     */
    fun onWindowKey(event: KeyEvent): Boolean {
        if (event.keyCode !in MediaKeyForwarder.KEY_CODES) return false
        return handle(event, MediaKeyForwarder.Source.WINDOW)
    }

    // ---------------- 主线程 ----------------

    private fun handle(event: KeyEvent, source: MediaKeyForwarder.Source): Boolean {
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        val result = forwarder.onKey(event.keyCode, down, event.repeatCount, event.eventTime)
        if (event.repeatCount == 0) {
            Log.i(
                TAG,
                "收到 ${MediaKeyForwarder.label(event.keyCode)} ${if (down) "按下" else "抬起"}，来自${source.label}：" +
                    "${result.note}${if (result.handled) "" else "（不吃掉）"}",
            )
        }
        deliver(result)
        return result.handled
    }

    private fun deliver(result: MediaKeyForwarder.Result) {
        if (result.keys.isEmpty()) return
        val current = target ?: return
        for (key in result.keys) {
            Log.i(
                TAG,
                "发给手机（${current.label}）：${if (key.action == HiSightInput.Key.Action.DOWN) "按下" else "抬起"} " +
                    MediaKeyForwarder.label(key.keyCode),
            )
            current.send(key)
        }
    }

    /** 媒体按钮会话在「按键就绪且拿着媒体焦点」或「已有当前曲目元数据」时保持存在，暂停时不销毁以免 CarMedia 丢失音源。 */
    private fun updateSession(why: String) {
        val current = target
        val want = current != null && (
            (current.keysReady && current.mediaFocus) ||
                clusterBridge.currentSnapshot() != null
            )
        val open = session
        if (want && open == null) {
            val context = appContext ?: return
            session = try {
                MediaKeySession(context, SESSION_TAG, SessionListener)
            } catch (e: RuntimeException) {
                Log.w(TAG, "建媒体按钮会话失败（${e.javaClass.simpleName}：${e.message}），只能在投屏页前台时转键")
                null
            }
            if (session != null) {
                Log.i(TAG, "建媒体按钮会话（$why）：不在前台时按方向盘也转给手机")
                clusterBridge.currentSnapshot()?.let { applySnapshotToSession(it) }
            }
        } else if (!want && open != null) {
            open.close()
            session = null
            Log.i(TAG, "关媒体按钮会话（$why）：不在前台时方向盘键交还车机")
        }
    }

    private fun applySnapshotToSession(snapshot: ClusterMediaBridge.Snapshot) {
        session?.updateMediaState(
            title = snapshot.title,
            artist = snapshot.artist,
            album = snapshot.album,
            durationMs = snapshot.durationMs,
            positionMs = snapshot.positionMs,
            playing = snapshot.playing,
            artwork = snapshot.artworkBitmap,
            lyric = snapshot.lyricContent.ifEmpty { snapshot.currentLyric },
        )
    }

    private object SessionListener : MediaKeySession.Listener {
        override fun onMediaKey(event: KeyEvent): Boolean = handle(event, MediaKeyForwarder.Source.SESSION)

        override fun onCommand(command: MediaKeyForwarder.Command) {
            val result = forwarder.onCommand(command)
            Log.i(TAG, "收到播放控制命令 ${MediaKeyForwarder.label(command.keyCode)}，来自媒体会话：${result.note}")
            deliver(result)
        }
    }

    private fun yesNo(value: Boolean) = if (value) "是" else "否"

    private const val SESSION_TAG = "OpenHC"
}
