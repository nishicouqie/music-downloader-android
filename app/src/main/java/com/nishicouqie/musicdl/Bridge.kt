package com.nishicouqie.musicdl

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * JS ↔ 原生 的桥。所有方法都返回 JSON 字符串。
 * 网络类操作立即返回，结果通过 window.__push({...}) 异步推回页面。
 */
class Bridge(private val act: MainActivity, private val web: WebView) {

    val cfg = Config(act)
    val storage = Storage(act)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val VERSION = "1.0"
    }

    fun emit(o: JSONObject) {
        val s = o.toString()
        main.post { web.evaluateJavascript("window.__push && window.__push($s)", null) }
    }

    private fun ok(): String = JSONObject().put("ok", true).toString()
    private fun err(msg: String, hint: String = ""): String =
        JSONObject().put("ok", false).put("error", JSONObject().apply {
            put("message", msg); put("hint", hint)
        }).toString()

    // ---------------------------------------------------- 基础

    @JavascriptInterface
    fun hello(): String = JSONObject().apply {
        put("ok", true); put("version", VERSION)
        put("platform", "android")
        put("sdkInt", android.os.Build.VERSION.SDK_INT)
        put("target", storage.hintPath(cfg))
        put("saveMode", cfg.saveMode)
        put("hasCookie", cfg.activeCookie().isNotBlank())
    }.toString()

    // ---------------------------------------------------- 设置

    @JavascriptInterface
    fun getSettings(): String {
        val d = cfg.toJson(false)
        val arr = JSONArray()
        for (c in cfg.cookies()) {
            arr.put(JSONObject().apply {
                put("id", c.optString("id"))
                put("name", c.optString("name"))
                put("enabled", c.optBoolean("enabled", true))
                put("note", c.optString("note"))
                put("masked", Config.mask(c.optString("value")))
                put("hasValue", c.optString("value").isNotBlank())
            })
        }
        d.put("cookies", arr)
        d.put("target", storage.hintPath(cfg))
        d.put("targetDesc", storage.describe(cfg))
        d.put("ok", true)
        return d.toString()
    }

    @JavascriptInterface
    fun saveSettings(json: String): String = try {
        cfg.fromJson(JSONObject(json), keepSecrets = false)
        ok()
    } catch (e: Exception) { err("保存失败：${e.message}") }

    @JavascriptInterface
    fun resetSettings(): String {
        cfg.reset(); return ok()
    }

    // ---------------------------------------------------- Cookie

    @JavascriptInterface
    fun addCookie(name: String, value: String): String = try {
        if (value.isBlank()) err("Cookie 内容不能为空", "粘贴 MUSIC_U 的值或整条 cookie 串")
        else JSONObject().put("ok", true)
            .put("id", cfg.addCookie(name, value)).toString()
    } catch (e: Exception) { err("保存失败：${e.message}") }

    @JavascriptInterface
    fun updateCookie(id: String, json: String): String = try {
        cfg.updateCookie(id, JSONObject(json)); ok()
    } catch (e: Exception) { err(e.message ?: "更新失败") }

    @JavascriptInterface
    fun removeCookie(id: String): String { cfg.removeCookie(id); return ok() }

    @JavascriptInterface
    fun setActiveCookie(id: String): String { cfg.activeCookieId = id; return ok() }

    @JavascriptInterface
    fun getCookieValue(id: String): String {
        for (c in cfg.cookies()) if (c.optString("id") == id)
            return JSONObject().put("ok", true).put("value", c.optString("value")).toString()
        return err("找不到这个账号")
    }

    @JavascriptInterface
    fun testCookie(value: String): String {
        thread {
            val r = try { Sources.probeCookie(value) }
            catch (e: Exception) { JSONObject().put("reason", e.message ?: "测试失败") }
            emit(JSONObject().put("type", "cookieTest").put("result", r))
        }
        return ok()
    }

    // ---------------------------------------------------- 搜索 / 下载

    @JavascriptInterface
    fun search(query: String): String {
        thread {
            try {
                val q = query.trim()
                if (q.isEmpty()) {
                    emit(JSONObject().put("type", "results").put("ok", false)
                        .put("error", JSONObject().put("message", "请输入歌名或粘贴链接")))
                    return@thread
                }
                val m = Regex("[?&#/]id=(\\d+)").find(q)
                    ?: Regex("^\\s*(\\d{4,})\\s*$").find(q)
                if (m != null) {
                    val d = Sources.detail(m.groupValues[1])
                    val one = JSONArray().put(JSONObject().apply {
                        put("id", d.optString("id")); put("title", d.optString("title"))
                        put("artist", d.optString("artist")); put("album", d.optString("album"))
                        put("cover", d.optString("cover"))
                        put("duration", d.optLong("duration"))
                        put("vip", d.optInt("fee") == 1 || d.optInt("fee") == 4)
                        put("_best", true)
                    })
                    emit(JSONObject().put("type", "results").put("ok", true)
                        .put("results", one))
                    return@thread
                }
                val rows = Sources.search(q)
                if (rows.isEmpty()) {
                    emit(JSONObject().put("type", "results").put("ok", false)
                        .put("error", JSONObject().put("message", "没搜到「$q」")
                            .put("hint", "试试只输入歌名，或直接粘贴网易云歌曲链接")))
                    return@thread
                }
                val best = Sources.pickBest(rows, q)
                val arr = JSONArray()
                for (r in rows) arr.put(JSONObject(r.toString()).apply {
                    put("_best", r === best)
                })
                emit(JSONObject().put("type", "results").put("ok", true).put("results", arr))
            } catch (e: Sources.MusicException) {
                emit(JSONObject().put("type", "results").put("ok", false)
                    .put("error", e.toJson()))
            } catch (e: Exception) {
                emit(JSONObject().put("type", "results").put("ok", false)
                    .put("error", JSONObject().put("message", "搜索出错：${e.message}")
                        .put("hint", "检查网络后重试")))
            }
        }
        return ok()
    }

    @JavascriptInterface
    fun startDownload(json: String): String = try {
        val song = JSONObject(json)
        if (song.optString("id").isBlank()) err("这条结果缺少歌曲 ID")
        else {
            val jid = "j" + System.currentTimeMillis()
            val job = Job(jid, song, act, cfg, storage) { ev -> emit(ev) }
            jobs[jid] = job
            thread { job.run(); jobs.remove(jid) }
            JSONObject().put("ok", true).put("jobId", jid).toString()
        }
    } catch (e: Exception) { err("启动失败：${e.message}") }

    @JavascriptInterface
    fun cancelJob(id: String): String {
        jobs[id]?.cancelled?.set(true)
        return ok()
    }

    // ---------------------------------------------------- 文件管理

    @JavascriptInterface
    fun describeTarget(): String = JSONObject().put("ok", true)
        .put("target", storage.hintPath(cfg))
        .put("desc", storage.describe(cfg)).toString()

    @JavascriptInterface
    fun listFiles(): String = try {
        JSONObject().put("ok", true)
            .put("files", storage.listToJson(storage.listMine(cfg)))
            .put("target", storage.hintPath(cfg)).toString()
    } catch (e: Exception) { err("读取目录失败：${e.message}", "可能需要在系统设置里授予文件权限") }

    @JavascriptInterface
    fun listElsewhere(): String = try {
        JSONObject().put("ok", true)
            .put("files", storage.listToJson(storage.listElsewhere())).toString()
    } catch (e: Exception) { err("读取失败：${e.message}", "需要在系统设置里授予「音乐和音频」权限") }

    @JavascriptInterface
    fun moveFiles(json: String): String {
        thread {
            try {
                val arr = JSONArray(json)
                val items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Storage.FileInfo(o.optString("name"), o.optLong("size"),
                        o.optString("uri"), o.optString("display"), true)
                }
                val (n, fails) = storage.moveTo(cfg, items)
                emit(JSONObject().put("type", "moved").put("ok", true)
                    .put("count", n).put("fails", fails))
            } catch (e: Exception) {
                emit(JSONObject().put("type", "moved").put("ok", false)
                    .put("error", JSONObject().put("message", "移动失败：${e.message}")))
            }
        }
        return ok()
    }

    @JavascriptInterface
    fun deleteFiles(json: String): String {
        thread {
            try {
                val arr = JSONArray(json)
                val items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Storage.FileInfo(o.optString("name"), o.optLong("size"),
                        o.optString("uri"), o.optString("display"), true)
                }
                val n = storage.deleteMany(items)
                emit(JSONObject().put("type", "deleted").put("ok", true).put("count", n))
            } catch (e: Exception) {
                emit(JSONObject().put("type", "deleted").put("ok", false)
                    .put("error", JSONObject().put("message", "删除失败：${e.message}")))
            }
        }
        return ok()
    }

    // ---------------------------------------------------- 目录选择 / 配置

    @JavascriptInterface
    fun pickFolder(): String {
        main.post { act.launchFolderPicker() }
        return ok()
    }

    @JavascriptInterface
    fun usePublicDir(): String {
        cfg.saveMode = Config.SAVE_PUBLIC
        cfg.safUri = ""
        return JSONObject().put("ok", true)
            .put("target", storage.hintPath(cfg)).toString()
    }

    @JavascriptInterface
    fun pickConfigFile(): String {
        main.post { act.launchConfigPicker() }
        return ok()
    }

    @JavascriptInterface
    fun exportConfig(): String = try {
        val where = storage.exportConfig(cfg)
        JSONObject().put("ok", true).put("path", where).toString()
    } catch (e: Exception) { err("导出失败：${e.message}") }

    @JavascriptInterface
    fun importConfigText(text: String): String = try {
        cfg.fromJson(JSONObject(text), keepSecrets = true)
        JSONObject().put("ok", true)
            .put("target", storage.hintPath(cfg)).toString()
    } catch (e: Exception) { err("导入失败：配置文件格式不对（${e.message}）") }

    /** 页面拿到 content:// 的配置文件后，把内容交给这里 */
    @JavascriptInterface
    fun readConfigUri(uri: String): String = try {
        JSONObject().put("ok", true).put("text", storage.readText(uri)).toString()
    } catch (e: Exception) { err("读取失败：${e.message}") }

    /** 让系统媒体库重新扫描，播放器才能立刻看到新文件 */
    @JavascriptInterface
    fun rescan(): String {
        thread {
            try {
                for (f in storage.listMine(cfg)) if (f.uri.startsWith("file://"))
                    storage.scan(android.net.Uri.parse(f.uri).path ?: "")
            } catch (_: Exception) {}
            emit(JSONObject().put("type", "rescanned").put("ok", true))
        }
        return ok()
    }
}
