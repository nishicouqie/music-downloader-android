package com.nishicouqie.musicdl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 一次下载任务：取元数据 → 歌词 → 封面 → 三级音源 → 下载 → 打标签 → 落盘 */
class Job(
    val id: String,
    private val song: JSONObject,
    private val ctx: Context,
    private val cfg: Config,
    private val storage: Storage,
    private val emit: (JSONObject) -> Unit
) {
    val cancelled = AtomicBoolean(false)
    private var files = JSONArray()
    private var lrcWarned = false

    private fun ev(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        o.put("jobId", id)
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        emit(o)
        return o
    }

    private fun stage(text: String, step: Int, total: Int) =
        ev("type" to "stage", "text" to text, "step" to step, "totalSteps" to total)

    private fun warn(text: String, hint: String = "") =
        ev("type" to "warn", "text" to text, "hint" to hint)

    fun run() {
        ev("type" to "status", "status" to "running")
        try {
            val sid = song.optString("id")
            val totalSteps = 7

            // 1) 元数据
            stage("获取歌曲信息…", 1, totalSteps)
            val d = Sources.detail(sid)
            ev("type" to "meta", "meta" to JSONObject().apply {
                put("title", d.optString("title"))
                put("artist", d.optString("artist"))
                put("album", d.optString("album"))
                put("cover", d.optString("cover"))
                put("duration", d.optLong("duration"))
                put("id", sid)
                put("vip", d.optInt("fee") == 1 || d.optInt("fee") == 4)
            })

            // 2) iTunes 补全
            stage("匹配高清封面与流派…", 2, totalSteps)
            val it = Sources.itunesLookup(d.optString("title"), d.optString("artist"))
            if (it != null) {
                ev("type" to "itunes", "info" to JSONObject().apply {
                    put("genre", it.optString("primaryGenreName"))
                    put("track", "${it.optInt("trackNumber")}/${it.optInt("trackCount")}")
                })
            } else warn("iTunes 没匹配到这张专辑，封面和流派只用网易云数据")

            // 3) 歌词
            var bilingual = ""; var original = ""
            var synced: List<Pair<Int, String>> = emptyList()
            stage("抓取歌词时间轴…", 3, totalSteps)
            try {
                val (b, o, s) = Sources.lyric(sid)
                bilingual = b; original = o; synced = s
                if (s.isNotEmpty()) {
                    val extra = bilingual.lines().size - original.lines().size
                    ev("type" to "lyric", "info" to JSONObject().apply {
                        put("lines", s.size); put("translated", maxOf(extra, 0))
                    })
                } else warn("这首歌没有时间轴歌词（纯音乐或冷门曲目）")
            } catch (e: Exception) {
                warn("歌词获取失败：${e.message}")
            }

            // 4) 封面
            var cover: ByteArray? = null
            var coverMime = "image/jpeg"
            var coverDesc = "已跳过"
            if (cfg.embedCover) {
                stage("挑选最清晰的封面…", 4, totalSteps)
                val (b, m, desc) = fetchCover(d, it)
                cover = b; coverMime = m; coverDesc = desc
                ev("type" to "cover", "info" to JSONObject().apply {
                    put("desc", desc); put("kb", (b?.size ?: 0) / 1024)
                })
            }

            // 5) 组装标签
            val meta = buildMeta(d, it, "music.163.com")
            val tag = Tagger.Tag(
                title = meta.optString("title"), artist = meta.optString("artist"),
                album = meta.optString("album"), date = meta.optString("date"),
                genre = meta.optString("genre"), track = meta.optString("track"),
                disc = meta.optString("disc"),
                comment = "网易云音乐 ID $sid | https://music.163.com/#/song?id=$sid",
                source = "music.163.com / gdstudio",
                cover = if (cfg.embedCover) cover else null, coverMime = coverMime,
                lyricBilingual = if (cfg.embedLyrics) bilingual else "",
                lyricOriginal = if (cfg.embedLyrics) original else "",
                synced = if (cfg.embedLyrics) synced else emptyList()
            )

            // 6) 音频
            val fmt = cfg.format
            val wants = if (fmt == "both") listOf("flac", "mp3") else listOf(fmt)
            val stem = Sources.sanitize("${meta.optString("artist")} - ${meta.optString("title")}")

            var step = 5
            for (want in wants) {
                if (cancelled.get()) throw Http.CancelledException()
                step++
                stage("解析 ${want.uppercase()} 音源…", 5, totalSteps)
                val audio = Sources.resolveAudio(sid, want, cfg) { m -> warn(m) }
                ev("type" to "channel", "info" to JSONObject().apply {
                    put("ext", audio.ext); put("channel", audio.channel)
                    put("level", audio.levelCn)
                })
                stage("下载 ${audio.levelCn}…", 6, totalSteps)

                val tmp = File.createTempFile("download_", "." + audio.ext, ctx.cacheDir)
                val last = longArrayOf(0)
                Http.download(audio.url, "https://music.163.com/",
                    cfg.activeCookie().takeIf { audio.channel == "official" },
                    tmp.outputStream(), { done, total, speed ->
                        val now = System.currentTimeMillis()
                        if (now - last[0] >= 260 || (total > 0 && done >= total)) {
                            last[0] = now
                            val eta = if (speed > 0 && total > 0) (total - done) / speed else 0.0
                            ev("type" to "progress", "ext" to audio.ext, "done" to done,
                                "total" to total, "speed" to speed, "eta" to eta)
                        }
                    }, { cancelled.get() })

                // 7) 打标签 + 落盘
                stage("写入标签与封面（${audio.ext.uppercase()}）…", 7, totalSteps)
                val tagged = try {
                    Tagger.write(tmp, tag); true
                } catch (e: Exception) {
                    warn("标签写入失败：${e.message}（音频本身没问题）")
                    false
                }

                val finalSize = tmp.length()      // 必须在 save 之前取，save 会删掉临时文件
                val saved = storage.save(cfg, tmp, "$stem.${audio.ext}")

                // 旁挂 .lrc：Android 11+ 不让把非媒体文件放进 Music/，所以这里
                // 绝对不能让它把整个任务搞挂——失败了只提示，歌词本身已经嵌进音频了
                if (cfg.writeLrc && bilingual.isNotBlank() && !lrcWarned) {
                    try {
                        val lrcTmp = File.createTempFile("lyric_", ".lrc", ctx.cacheDir)
                        lrcTmp.writeText(bilingual, Charsets.UTF_8)
                        val where = storage.save(cfg, lrcTmp, "$stem.lrc")
                        if (!where.contains("/Music/")) {
                            lrcWarned = true
                            warn("受 Android 限制，.lrc 放不进音乐目录，已存到 $where（歌词同样已嵌进音频文件）")
                        }
                    } catch (e: Exception) {
                        lrcWarned = true
                        warn("旁挂 .lrc 写入失败：${e.message}（歌词已嵌入音频，不影响使用）")
                    }
                }

                val f = JSONObject().apply {
                    put("ext", audio.ext); put("path", saved)
                    put("size", finalSize)
                    put("level", audio.levelCn); put("channel", audio.channel)
                    put("cover", coverDesc); put("lyricLines", synced.size)
                    put("tagged", tagged)
                }
                files.put(f)
                ev("type" to "file", "ext" to audio.ext, "path" to saved,
                    "size" to finalSize, "level" to audio.levelCn,
                    "channel" to audio.channel, "cover" to coverDesc,
                    "lyricLines" to synced.size, "tagged" to tagged)
            }

            ev("type" to "status", "status" to "done", "files" to files)
        } catch (e: Http.CancelledException) {
            ev("type" to "status", "status" to "cancelled")
        } catch (e: Sources.MusicException) {
            ev("type" to "status", "status" to "error", "error" to e.toJson())
        } catch (e: Exception) {
            ev("type" to "status", "status" to "error", "error" to JSONObject().apply {
                put("message", "意外错误：${e.javaClass.simpleName}: ${e.message}")
                put("hint", "把这条信息反馈给开发者")
                put("code", "UNEXPECTED")
            })
        }
    }

    // ---------------------------------------------------------- 封面

    private fun fetchCover(d: JSONObject, it: JSONObject?): Triple<ByteArray?, String, String> {
        val cands = mutableListOf<Triple<Int, ByteArray, String>>()
        val mode = cfg.coverSource

        if (mode != "itunes") {
            val url = d.optString("cover")
            if (url.isNotBlank()) {
                try {
                    val b = Http.bytes(url, referer = "https://music.163.com/",
                        ua = Http.UA_DESKTOP, retries = 2)
                    val (w, h) = Tagger.imageSize(b)
                    if (w > 0) cands.add(Triple(w * h, b, "网易云 ${w}×${h}"))
                } catch (_: Exception) {}
            }
        }
        if (mode != "netease" && it != null) {
            val art = it.optString("artworkUrl100")
            if (art.isNotBlank()) {
                val base = art.replace(Regex("/\\d+x\\d+(bb|sr)?\\.(jpg|png)$"), "/{size}.jpg")
                val want = if (cfg.coverMax > 0) cfg.coverMax else 3000
                for (size in listOf("${want}x${want}bb", "1200x1200bb", "600x600bb")) {
                    try {
                        val b = Http.bytes(base.replace("{size}", size),
                            referer = "https://music.apple.com/", ua = Http.UA_DESKTOP,
                            retries = 1)
                        val (w, h) = Tagger.imageSize(b)
                        if (w > 0) { cands.add(Triple(w * h, b, "iTunes ${w}×${h}")); break }
                    } catch (_: Exception) {}
                }
            }
        }
        if (cands.isEmpty()) return Triple(null, "image/jpeg", "未取到")
        cands.sortByDescending { it.first }
        val best = cands.first()
        val mime = if (best.second.size > 8 && best.second[0] == 0x89.toByte())
            "image/png" else "image/jpeg"
        return Triple(best.second, mime, best.third)
    }

    // ---------------------------------------------------------- 标签

    private fun buildMeta(d: JSONObject, it: JSONObject?, src: String): JSONObject {
        val m = JSONObject().apply {
            put("title", d.optString("title")); put("artist", d.optString("artist"))
            put("album", d.optString("album")); put("date", ""); put("genre", "")
            put("track", ""); put("disc", ""); put("source", src)
        }
        val pt = d.optLong("publishTime")
        if (pt > 0) m.put("date", java.text.SimpleDateFormat("yyyy-MM-dd",
            java.util.Locale.US).format(java.util.Date(pt)))
        val tr = d.optInt("track"); val sz = d.optInt("albumSize")
        if (tr > 0) m.put("track", if (sz > 0) "$tr/$sz" else "$tr")
        val dc = d.optString("disc")
        if (dc.isNotBlank()) m.put("disc", "$dc/1")

        if (it != null) {
            if (it.optInt("trackNumber") > 0) {
                val tn = it.optInt("trackNumber"); val tc = it.optInt("trackCount")
                m.put("track", if (tc > 0) "$tn/$tc" else "$tn")
            }
            if (it.optInt("discNumber") > 0) {
                val dn = it.optInt("discNumber"); val dcn = it.optInt("discCount")
                m.put("disc", if (dcn > 0) "$dn/$dcn" else "$dn")
            }
            if (it.optString("primaryGenreName").isNotBlank())
                m.put("genre", it.optString("primaryGenreName"))
            if (it.optString("releaseDate").isNotBlank())
                m.put("date", it.optString("releaseDate").take(10))
            if (it.optString("collectionName").isNotBlank())
                m.put("album", it.optString("collectionName"))
        }
        return m
    }
}
