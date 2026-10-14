package com.nishicouqie.musicdl

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bridge: Bridge

    companion object {
        private const val REQ_TREE = 1001
        private const val REQ_CONFIG = 1002
        private const val REQ_PERM = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web.setBackgroundColor(0xFFFCFCFD.toInt())
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = false
            textZoom = 100
        }
        WebView.setWebContentsDebuggingEnabled(true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                // 站内不动；外部链接交给系统浏览器
                if (url != null && !url.startsWith("file://") &&
                    !url.startsWith("http://127.0.0.1")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    } catch (_: Exception) {
                    }
                }
                return false
            }
        }

        bridge = Bridge(this, web)
        web.addJavascriptInterface(bridge, "Android")
        web.loadUrl("file:///android_asset/ui/index.html")

        requestStoragePermission(false)
    }

    // ------------------------------------------------------ 目录选择

    fun launchFolderPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                     Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                     Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        try {
            startActivityForResult(i, REQ_TREE)
        } catch (e: Exception) {
            bridge.emit(JSONObject().put("type", "folderPicked").put("ok", false)
                .put("error", JSONObject().put("message", "打不开目录选择器：${e.message}")))
        }
    }

    fun launchConfigPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(i, REQ_CONFIG)
        } catch (e: Exception) {
            bridge.emit(JSONObject().put("type", "configPicked").put("ok", false)
                .put("error", JSONObject().put("message", "打不开文件选择器：${e.message}")))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            when (requestCode) {
                REQ_TREE -> bridge.emit(JSONObject().put("type", "folderPicked")
                    .put("ok", false).put("cancelled", true))
                REQ_CONFIG -> bridge.emit(JSONObject().put("type", "configPicked")
                    .put("ok", false).put("cancelled", true))
            }
            return
        }
        when (requestCode) {
            REQ_TREE -> {
                try {
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Exception) {
                }
                val tree = DocumentFile.fromTreeUri(this, uri)
                bridge.cfg.safUri = uri.toString()
                bridge.cfg.safName = tree?.name ?: "自选目录"
                bridge.cfg.saveMode = Config.SAVE_SAF
                bridge.emit(JSONObject().put("type", "folderPicked").put("ok", true)
                    .put("target", bridge.storage.hintPath(bridge.cfg))
                    .put("writable", tree?.canWrite() ?: false))
            }
            REQ_CONFIG -> {
                try {
                    val text = contentResolver.openInputStream(uri)!!
                        .use { String(it.readBytes(), Charsets.UTF_8) }
                    bridge.cfg.fromJson(JSONObject(text), keepSecrets = true)
                    bridge.emit(JSONObject().put("type", "configPicked").put("ok", true)
                        .put("target", bridge.storage.hintPath(bridge.cfg)))
                } catch (e: Exception) {
                    bridge.emit(JSONObject().put("type", "configPicked").put("ok", false)
                        .put("error", JSONObject().put("message",
                            "配置文件读不了：${e.message}")))
                }
            }
        }
    }

    // ------------------------------------------------------ 权限

    private fun neededPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun requestStoragePermission(notify: Boolean) {
        val need = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) {
            if (notify) bridge.emit(JSONObject().put("type", "permission")
                .put("ok", true).put("granted", true))
            return
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(need.toTypedArray(), REQ_PERM)
        } else if (notify) {
            bridge.emit(JSONObject().put("type", "permission").put("ok", true)
                .put("granted", true))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            val all = grantResults.isNotEmpty() &&
                      grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            bridge.emit(JSONObject().put("type", "permission").put("ok", true)
                .put("granted", all))
        }
    }

    // ------------------------------------------------------ 返回键

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){ if (window.__onBack && window.__onBack()) return '1'; return '0'; })()"
        ) { r ->
            if (r == null || !r.contains("1")) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
