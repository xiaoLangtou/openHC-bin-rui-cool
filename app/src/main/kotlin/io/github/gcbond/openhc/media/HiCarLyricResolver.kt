package io.github.gcbond.openhc.media

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import io.github.gcbond.openhc.platform.log.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * HiCar 歌词解析与在线歌词兜底（移植自吉利定制包 `com.geely.patch.HiCarLyricResolver` 与 `HiCarMediaState`）：
 * 1. 若手机 515 报文自带 LRC 歌词全文或当前句，直接缓存并按播放进度（秒）解析当前句；
 * 2. 若手机 515 未带歌词（如部分音乐应用未开启 HiCar 桌面歌词传递），后台依次从 LRCLIB → 网易云 → 酷狗检索 LRC 并缓存到
 *    `SharedPreferences("hicar_lyrics_cache")`，拉取成功后回调 [onLyricCached] 立即刷新仪表屏与 MediaSession。
 */
object HiCarLyricResolver {
    private const val TAG = "OpenHC-Lyric"
    private const val PREF_NAME = "hicar_lyrics_cache"
    private const val FETCH_GAP_MS = 30_000L

    private val INVALID_TOKENS = listOf(
        "作词", "作曲", "编曲", "制作", "监制", "混音", "母带", "录音",
        "暂无歌词", "纯音乐", "歌词贡献",
    )

    @Volatile
    private var fetchingKey: String? = null

    @Volatile
    private var lastTitle: String = ""

    @Volatile
    private var lastArtist: String = ""

    fun isValidLyric(line: String?): Boolean {
        val text = line?.trim() ?: return false
        if (text.isEmpty()) return false
        return INVALID_TOKENS.none { text.contains(it) }
    }

    fun rememberTrack(context: Context, title: String?, artist: String?) {
        val normTitle = title?.trim().orEmpty()
        if (normTitle.isEmpty()) return
        val normArtist = artist?.trim().orEmpty()
        lastTitle = normTitle
        lastArtist = normArtist
        runCatching {
            prefs(context).edit()
                .putString("last_title", normTitle)
                .putString("last_artist", normArtist)
                .apply()
        }
    }

    fun getLastTitle(context: Context): String {
        if (lastTitle.isEmpty()) {
            lastTitle = runCatching { prefs(context).getString("last_title", "").orEmpty() }.getOrDefault("")
        }
        return lastTitle
    }

    fun getLastArtist(context: Context): String {
        if (lastArtist.isEmpty()) {
            lastArtist = runCatching { prefs(context).getString("last_artist", "").orEmpty() }.getOrDefault("")
        }
        return lastArtist
    }

    fun saveLyric(context: Context, title: String?, artist: String?, lrc: String?, onUpdated: (() -> Unit)? = null) {
        val normTitle = title?.trim().orEmpty()
        val normLrc = lrc?.trim().orEmpty()
        if (normTitle.isEmpty() || normLrc.isEmpty()) return
        runCatching {
            prefs(context).edit().putString(makeKey(normTitle, artist), normLrc).apply()
        }
        onUpdated?.invoke()
    }

    fun resolveContent(context: Context, title: String?, artist: String?, onFetched: (() -> Unit)? = null): String {
        var t = title?.trim().orEmpty()
        var a = artist?.trim().orEmpty()
        if (t.isEmpty()) {
            t = getLastTitle(context)
            a = getLastArtist(context)
        }
        if (t.isEmpty()) return ""
        val cached = getCachedLyric(context, t, a)
        if (cached.isNotEmpty()) return cached
        enqueueFetch(context, t, a, onFetched)
        return ""
    }

    fun resolve(context: Context, title: String?, artist: String?, elapsedSec: Int, onFetched: (() -> Unit)? = null): String {
        var t = title?.trim().orEmpty()
        var a = artist?.trim().orEmpty()
        if (t.isEmpty()) {
            t = getLastTitle(context)
            a = getLastArtist(context)
        } else {
            rememberTrack(context, t, a)
        }
        if (t.isEmpty()) return ""
        val cached = getCachedLyric(context, t, a)
        if (cached.isNotEmpty()) {
            return findCurrentLine(cached, elapsedSec.coerceAtLeast(0))
        }
        enqueueFetch(context, t, a, onFetched)
        return ""
    }

    private fun getCachedLyric(context: Context, title: String, artist: String?): String {
        if (title.isEmpty()) return ""
        return runCatching { prefs(context).getString(makeKey(title, artist), "").orEmpty() }.getOrDefault("")
    }

    private fun enqueueFetch(context: Context, title: String, artist: String, onFetched: (() -> Unit)?) {
        if (title.isEmpty()) return
        val key = makeKey(title, artist)
        if (key == fetchingKey) return
        val now = System.currentTimeMillis()
        val sp = runCatching { prefs(context) }.getOrNull() ?: return
        val lastKey = sp.getString("last_fetch_key", "").orEmpty()
        val lastAt = sp.getLong("last_fetch_at", 0L)
        if (key == lastKey && now - lastAt < FETCH_GAP_MS) return
        fetchingKey = key
        sp.edit().putString("last_fetch_key", key).putLong("last_fetch_at", now).apply()
        val app = context.applicationContext ?: context
        thread(name = "OpenHC-LyricFetch", isDaemon = true) {
            fetchAndCache(app, title, artist, onFetched)
        }
    }

    private fun fetchAndCache(context: Context, title: String, artist: String, onFetched: (() -> Unit)?) {
        try {
            var lrc = runCatching { fetchLrcLib(title, artist) }.getOrDefault("")
            if (lrc.isEmpty()) {
                lrc = runCatching { fetchNetease(title, artist) }.getOrDefault("")
            }
            if (lrc.isEmpty()) {
                lrc = runCatching { fetchKugou(title, artist) }.getOrDefault("")
            }
            if (lrc.isNotEmpty()) {
                Log.i(TAG, "在线歌词拉取成功：$title / $artist（${lrc.length} 字符）")
                saveLyric(context, title, artist, lrc, onFetched)
            }
        } finally {
            fetchingKey = null
        }
    }

    private fun fetchLrcLib(title: String, artist: String): String {
        val raw = http("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
        if (raw.isEmpty()) return ""
        val json = JSONObject(raw)
        val synced = json.optString("syncedLyrics").trim()
        return if (synced.isNotEmpty()) synced else json.optString("plainLyrics").trim()
    }

    private fun fetchNetease(title: String, artist: String): String {
        val keyword = if (artist.isNotEmpty()) "$title $artist" else title
        val searchRaw = http("https://music.163.com/api/search/get/web?csrf_token=&type=1&offset=0&total=true&limit=5&s=${enc(keyword)}")
        if (searchRaw.isEmpty()) return ""
        val songId = JSONObject(searchRaw)
            .optJSONObject("result")
            ?.optJSONArray("songs")
            ?.optJSONObject(0)
            ?.optInt("id", 0) ?: 0
        if (songId == 0) return ""
        val lyricRaw = http("https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&id=$songId")
        if (lyricRaw.isEmpty()) return ""
        return JSONObject(lyricRaw).optJSONObject("lrc")?.optString("lyric")?.trim().orEmpty()
    }

    private fun fetchKugou(title: String, artist: String): String {
        val keyword = if (artist.isNotEmpty()) "$title $artist" else title
        val searchRaw = http(
            "https://songsearch.kugou.com/song_search_v2?keyword=${enc(keyword)}&page=1&pagesize=3&userid=-1&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0",
        )
        if (searchRaw.isEmpty()) return ""
        val first = JSONObject(searchRaw).optJSONObject("data")?.optJSONArray("lists")?.optJSONObject(0) ?: return ""
        val hash = first.optString("FileHash")
        val duration = first.optInt("Duration", 0)
        if (hash.isEmpty()) return ""
        val candRaw = http(
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(keyword)}&duration=$duration&hash=${enc(hash)}",
        )
        if (candRaw.isEmpty()) return ""
        val cand = JSONObject(candRaw).optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        val id = cand.optString("id")
        val accessKey = cand.optString("accesskey")
        if (id.isEmpty() || accessKey.isEmpty()) return ""
        val dlRaw = http(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(accessKey)}&fmt=lrc&charset=utf8",
        )
        if (dlRaw.isEmpty()) return ""
        val content = JSONObject(dlRaw).optString("content")
        if (content.isEmpty()) return ""
        return runCatching { String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8).trim() }
            .getOrDefault(content.trim())
    }

    fun findCurrentLine(lrc: String, elapsedSec: Int): String {
        if (lrc.isEmpty()) return ""
        var current = ""
        for (rawLine in lrc.split('\n')) {
            val line = rawLine.trim()
            val sec = parseTimeSec(line)
            if (sec >= 0) {
                if (sec > elapsedSec) return current
                val closeIdx = line.lastIndexOf(']')
                if (closeIdx >= 0) {
                    val text = line.substring(closeIdx + 1).trim()
                    if (isValidLyric(text)) {
                        current = text
                    }
                }
            }
        }
        return current
    }

    private fun parseTimeSec(line: String): Int = try {
        if (!line.startsWith("[")) {
            -1
        } else {
            val closeIdx = line.indexOf(']')
            if (closeIdx <= 1) {
                -1
            } else {
                val parts = line.substring(1, closeIdx).split(':')
                if (parts.size < 2) {
                    -1
                } else {
                    parts[0].toInt() * 60 + parts[1].toFloat().toInt()
                }
            }
        }
    } catch (_: Throwable) {
        -1
    }

    private fun http(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 7_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 HiCarLyricResolver/2.0")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        return try {
            if (conn.responseCode >= 400) return ""
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append('\n')
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String?): String = URLEncoder.encode(value?.trim().orEmpty(), "UTF-8")

    private fun makeKey(title: String?, artist: String?): String =
        "lyric_${title?.trim().orEmpty()}_${artist?.trim().orEmpty()}"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
