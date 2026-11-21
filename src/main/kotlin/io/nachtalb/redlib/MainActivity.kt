package io.nachtalb.redlib

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ContextMenu
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** The Redlib server, one per app process. */
object Redlib {
    private const val PORT = 38080
    const val AUTHORITY = "127.0.0.1:$PORT"
    const val BASE = "http://$AUTHORITY"
    private var process: Process? = null

    fun log(context: Context) = File(context.cacheDir, "redlib.log")

    /** Starts the server unless it runs. Returns whether it had to. */
    fun start(context: Context): Boolean {
        if (process?.isAlive == true) return false
        process = ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libredlib.so", "-a", "127.0.0.1", "-p", "$PORT")
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .redirectOutput(log(context))
            .start()
        return true
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    /** Waits until the server answers. False when it exited or took over a minute. */
    fun awaitReady(): Boolean {
        repeat(300) {
            if (process?.isAlive != true) return false
            try {
                val connection = URL("$BASE/healthz").openConnection() as HttpURLConnection
                connection.connectTimeout = 500
                if (connection.responseCode == 200) return true
            } catch (_: Exception) {
            }
            Thread.sleep(200)
        }
        return false
    }
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private var fullscreen: View? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        web = WebView(this)
        progress = ProgressBar(this)
        error = TextView(this).apply { setPadding(48, 48, 48, 48); setTextIsSelectable(true) }
        root = FrameLayout(this).apply {
            addView(web)
            addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(error)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val local = url.authority == Redlib.AUTHORITY
                if (local && !Links.isMedia(url.path.orEmpty())) return false
                val path = url.encodedPath.orEmpty() + (url.encodedQuery?.let { "?$it" } ?: "")
                openOutside(if (local) Links.origin(path)?.let(Uri::parse) ?: url else url)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreen = view
                root.addView(view)
                web.visibility = View.GONE
                window.insetsController?.hide(WindowInsets.Type.systemBars())
            }

            override fun onHideCustomView() {
                root.removeView(fullscreen)
                fullscreen = null
                web.visibility = View.VISIBLE
                window.insetsController?.show(WindowInsets.Type.systemBars())
            }
        }
        web.setDownloadListener { url, _, disposition, mime, _ -> save(url, URLUtil.guessFileName(url, disposition, mime)) }
        registerForContextMenu(web)
        open(page(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(page(intent))
    }

    override fun onResume() {
        super.onResume()
        // Android may have killed the server while the app was in the background.
        web.url?.let { if (Redlib.start(this)) open(it, started = true) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) Redlib.stop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreen != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, info: ContextMenu.ContextMenuInfo?) {
        val hit = web.hitTestResult
        val url = hit.extra ?: return
        if (hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            menu.add("Save image").setOnMenuItemClickListener {
                save(url, URLUtil.guessFileName(url, null, null))
                true
            }
        }
    }

    /** The local address for the link the app was opened with, the front page without one. */
    private fun page(intent: Intent?) = Redlib.BASE + Links.page(intent?.data?.encodedPath, intent?.data?.encodedQuery)

    private fun open(url: String, started: Boolean = Redlib.start(this)) {
        error.visibility = View.GONE
        progress.visibility = if (started) View.VISIBLE else View.GONE
        if (!started) return web.loadUrl(url)
        thread {
            val ready = Redlib.awaitReady()
            runOnUiThread {
                progress.visibility = View.GONE
                if (ready) return@runOnUiThread web.loadUrl(url)
                error.text = "Redlib did not start.\n\n" + Redlib.log(this).takeIf { it.exists() }?.readText().orEmpty().takeLast(4000)
                error.visibility = View.VISIBLE
            }
        }
    }

    /** Opens a link in the browser, never in this app, which handles reddit.com links itself. */
    private fun openOutside(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (uri.scheme == "http" || uri.scheme == "https") {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
            val preferred = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            val browsers = packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
            intent.setPackage(preferred?.takeIf { it in browsers && it != packageName } ?: browsers.firstOrNull { it != packageName })
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app opens this link", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves what the local server sends for `url` to the Downloads folder. */
    private fun save(url: String, name: String) {
        thread {
            val saved = try {
                val connection = URL(url).openConnection()
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    connection.contentType?.substringBefore(';')?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                }
                val target = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                connection.getInputStream().use { input -> contentResolver.openOutputStream(target)!!.use { input.copyTo(it) } }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread { Toast.makeText(this, if (saved) "Saved to Downloads" else "Could not save", Toast.LENGTH_SHORT).show() }
        }
    }
}
