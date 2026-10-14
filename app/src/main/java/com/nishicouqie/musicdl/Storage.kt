package com.nishicouqie.musicdl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 文件存放。三种模式：
 *   public —— 公共 Music/ 目录（MediaStore），播放器立刻能扫到，不需要任何权限
 *   saf    —— 用户自己用系统选择器挑的目录
 *   app    —— 应用私有目录
 */
class Storage(private val ctx: Context) {

    private val resolver = ctx.contentResolver

    data class FileInfo(
        val name: String,
        val size: Long,
        val uri: String,          // content:// 或 file://
        val display: String,      // 给人看的路径
        val exists: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name); put("size", size); put("uri", uri)
            put("display", display); put("exists", exists)
        }
    }

    // ---------------------------------------------------- 目标路径描述

    fun describe(cfg: Config): String = when (cfg.saveMode) {
        Config.SAVE_SAF ->
            if (cfg.safUri.isBlank()) "未选择目录" else "自选目录：${cfg.safName}"
        Config.SAVE_APP -> "应用私有目录"
        else -> "内部存储 / Music / ${cfg.dirName}"
    }

    /** 用户可见的一个「人类可读」路径，用于界面展示和提示 */
    fun hintPath(cfg: Config): String = when (cfg.saveMode) {
        Config.SAVE_SAF -> cfg.safName.ifBlank { "自选目录" }
        Config.SAVE_APP -> "Android/data/${ctx.packageName}/files/Music"
        else -> "内部存储/Music/${cfg.dirName}"
    }

    // ---------------------------------------------------- 写入

    /** 把临时文件放进目标位置，返回落点描述；file 会被消费掉 */
    fun save(cfg: Config, tmp: File, displayName: String): String {
        val mime = when (tmp.extension.lowercase()) {
            "flac" -> "audio/flac"; "lrc" -> "text/plain"
            "json" -> "application/json"; else -> "audio/mpeg"
        }
        return if (mime == "text/plain" || mime == "application/json")
            saveSidecar(cfg, tmp, displayName, mime)
        else save(cfg, tmp, displayName, mime)
    }

    fun save(cfg: Config, tmp: File, displayName: String, mime: String): String =
        when (cfg.saveMode) {
            Config.SAVE_SAF -> saveToSaf(cfg, tmp, displayName, mime)
            Config.SAVE_APP -> saveToApp(tmp, displayName)
            else -> saveToPublic(cfg, tmp, displayName, mime)
        }

    /**
     * 旁挂文件（.lrc / .json）。
     *
     * Android 11+ 明确禁止把非媒体文件写进 Music/：
     *   "Primary directory Music not allowed for content://media/external/file;
     *    allowed directories are [Download, Documents]"
     * 所以这里按可行性依次降级，任何一步失败都不会影响音频本身。
     */
    private fun saveSidecar(cfg: Config, tmp: File, name: String, mime: String): String {
        when (cfg.saveMode) {
            Config.SAVE_SAF -> return saveToSaf(cfg, tmp, name, mime)
            Config.SAVE_APP -> return saveToApp(tmp, name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 先试着跟音乐放一起：走 audio 集合（Music/ 只对音频类集合开放）
            try {
                return saveQ(tmp, name, mime, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    "${Environment.DIRECTORY_MUSIC}/${cfg.dirName}")
            } catch (_: Exception) {
            }
            // 退到 Download/<子目录>（这是系统允许放任意文件的公共目录）
            return saveQ(tmp, name, mime, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_DOWNLOADS}/${cfg.dirName}")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC), cfg.dirName)
        if (!dir.exists()) dir.mkdirs()
        val dst = File(dir, name)
        if (dst.exists()) dst.delete()
        tmp.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it, 256 * 1024) } }
        tmp.delete()
        scan(dst.absolutePath)
        return "内部存储/Music/${cfg.dirName}/$name"
    }

    /** Android 10+ 往指定集合 + 相对路径写一个文件 */
    private fun saveQ(tmp: File, name: String, mime: String,
                      collection: Uri, relPath: String): String {
        // MediaStore 里存的是带尾斜杠的形式（Music/xxx/），删旧的必须用同样的写法，
        // 否则匹配不上会生成 "xxx (1).mp3" 这种重复文件
        val rel = relPath.trimEnd('/') + "/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            if (mime.startsWith("audio")) put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        try {
            resolver.delete(collection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(rel, name))
        } catch (_: Exception) {
        }
        val uri = resolver.insert(collection, values)
            ?: throw RuntimeException("系统拒绝了写入 $rel")
        resolver.openOutputStream(uri)?.use { out ->
            tmp.inputStream().use { it.copyTo(out, 256 * 1024) }
        } ?: throw RuntimeException("无法写入 $rel")
        tmp.delete()
        return "内部存储/${rel}$name"
    }

    private fun saveToPublic(cfg: Config, tmp: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveQ(tmp, name, mime, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MUSIC}/${cfg.dirName}")
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val dir = File(base, cfg.dirName)
            if (!dir.exists() && !dir.mkdirs())
                throw RuntimeException("无法创建目录 ${dir.absolutePath}")
            val dst = File(dir, name)
            if (dst.exists()) dst.delete()
            tmp.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it, 256 * 1024) } }
            tmp.delete()
            scan(dst.absolutePath)
            return "内部存储/Music/${cfg.dirName}/$name"
        }
    }

    private fun saveToSaf(cfg: Config, tmp: File, name: String, mime: String): String {
        if (cfg.safUri.isBlank()) throw RuntimeException("还没有选择保存目录")
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(cfg.safUri))
            ?: throw RuntimeException("保存目录已失效，请重新选择")
        if (!tree.canWrite()) throw RuntimeException("这个目录没有写入权限，请换一个")

        tree.findFile(name)?.delete()
        val doc = tree.createFile(mime, name)
            ?: throw RuntimeException("在「${tree.name}」里创建文件失败")
        resolver.openOutputStream(doc.uri)?.use { out ->
            tmp.inputStream().use { it.copyTo(out, 256 * 1024) }
        } ?: throw RuntimeException("写入「${tree.name}」失败")
        tmp.delete()
        return "${tree.name}/$name"
    }

    private fun saveToApp(tmp: File, name: String): String {
        val dir = File(ctx.getExternalFilesDir(null), "Music")
        dir.mkdirs()
        val dst = File(dir, name)
        if (dst.exists()) dst.delete()
        tmp.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it, 256 * 1024) } }
        tmp.delete()
        return "应用目录/Music/$name"
    }

    fun scan(path: String) {
        try {
            MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------- 列文件

    /** 当前保存位置里的文件 */
    fun listMine(cfg: Config): List<FileInfo> = when (cfg.saveMode) {
        Config.SAVE_SAF -> listSaf(cfg)
        Config.SAVE_APP -> listApp()
        else -> listPublic(cfg)
    }

    private fun listPublic(cfg: Config): List<FileInfo> {
        val out = mutableListOf<FileInfo>()
        val rel = "${Environment.DIRECTORY_MUSIC}/${cfg.dirName}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val proj = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH)
                resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$rel%"), null)?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(0) ?: continue
                        if (!isAudio(n) && !n.endsWith(".lrc")) continue
                        out.add(FileInfo(n, c.getLong(1), "",
                            "内部存储/Music/${cfg.dirName}/$n", true))
                    }
                }
            } catch (_: Exception) {
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), cfg.dirName)
            dir.listFiles()?.forEach { f ->
                if (f.isFile && (isAudio(f.name) || f.name.endsWith(".lrc")))
                    out.add(FileInfo(f.name, f.length(),
                        Uri.fromFile(f).toString(),
                        "内部存储/Music/${cfg.dirName}/${f.name}", true))
            }
        }
        return out.sortedBy { it.name }
    }

    private fun listSaf(cfg: Config): List<FileInfo> {
        if (cfg.safUri.isBlank()) return emptyList()
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(cfg.safUri))
            ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && (isAudio(it.name ?: "") || (it.name ?: "").endsWith(".lrc")) }
            .map {
                FileInfo(it.name ?: "", it.length(), it.uri.toString(),
                    "${tree.name}/${it.name}", true)
            }
            .sortedBy { it.name }
    }

    private fun listApp(): List<FileInfo> {
        val dir = File(ctx.getExternalFilesDir(null), "Music")
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && (isAudio(it.name) || it.name.endsWith(".lrc")) }
            .map {
                FileInfo(it.name, it.length(), Uri.fromFile(it).toString(),
                    "应用目录/Music/${it.name}", true)
            }
            .sortedBy { it.name }
    }

    /** 系统「下载」等公共目录里的音频，方便用户把别处下载的歌挪进 Music */
    fun listElsewhere(limit: Int = 300): List<FileInfo> {
        val out = mutableListOf<FileInfo>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA)
            val sel = "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND " +
                      "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?"
            resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel,
                arrayOf("${Environment.DIRECTORY_MUSIC}/%"), null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val n = c.getString(0) ?: continue
                    if (!isAudio(n)) continue
                    val rel = c.getString(2) ?: ""
                    out.add(FileInfo(n, c.getLong(1), "",
                        "内部存储/$rel$n", true))
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { it.name }
    }

    private fun isAudio(n: String): Boolean {
        val e = n.substringAfterLast('.', "").lowercase()
        return e in setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")
    }

    // ---------------------------------------------------- 搬运 / 删除

    /** 把一批文件搬到目标位置；返回 (成功数, 失败说明) */
    fun moveTo(cfg: Config, items: List<FileInfo>): Pair<Int, String> {
        var ok = 0
        val fails = mutableListOf<String>()
        for (item in items) {
            try {
                val ext = item.name.substringAfterLast('.', "bin")
                val tmp = File.createTempFile("move_", ".$ext", ctx.cacheDir)
                openInput(item.uri).use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out, 256 * 1024) }
                }
                save(cfg, tmp, item.name)
                deleteOne(item)
                ok++
            } catch (e: Exception) {
                fails.add("${item.name}：${e.message}")
            }
        }
        return ok to fails.joinToString("\n")
    }

    private fun openInput(uriStr: String): InputStream {
        if (uriStr.startsWith("content://"))
            return resolver.openInputStream(Uri.parse(uriStr))
                ?: throw RuntimeException("读不到文件")
        return File(Uri.parse(uriStr).path!!).inputStream()
    }

    fun deleteOne(info: FileInfo): Boolean = try {
        if (info.uri.startsWith("content://")) {
            resolver.delete(Uri.parse(info.uri), null, null) > 0
        } else {
            val f = File(Uri.parse(info.uri).path!!)
            val p = f.absolutePath
            val r = f.delete()
            if (r) scan(p)
            r
        }
    } catch (_: Exception) {
        false
    }

    fun deleteMany(items: List<FileInfo>): Int = items.count { deleteOne(it) }

    // ---------------------------------------------------- 配置导出 / 导入

    fun exportConfig(cfg: Config): String {
        val tmp = File.createTempFile("config_", ".json", ctx.cacheDir)
        tmp.writeText(cfg.toJson(true).toString(2), Charsets.UTF_8)
        val name = "musicdl-config-${System.currentTimeMillis() / 1000}.json"
        return when (cfg.saveMode) {
            Config.SAVE_SAF -> runCatching { saveToSaf(cfg, tmp, name, "application/json") }
                .getOrElse { saveToPublic(cfg, tmp, name, "application/json") }
            else -> saveToPublic(cfg, tmp, name, "application/json")
        }
    }

    fun importConfig(text: String): Config {
        val o = JSONObject(text)
        return Config(ctx).also { it.fromJson(o, keepSecrets = true) }
    }

    fun readText(uriStr: String): String =
        openInput(uriStr).use { String(it.readBytes(), Charsets.UTF_8) }

    fun listToJson(list: List<FileInfo>): JSONArray {
        val a = JSONArray()
        for (f in list) a.put(f.toJson())
        return a
    }
}
