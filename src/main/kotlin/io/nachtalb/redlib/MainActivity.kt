package io.nachtalb.redlib

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject

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

    /** Where the last touch began, as fractions of the page view. */
    private var touch = 0f to 0f

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
                if (url.authority != Redlib.AUTHORITY) openOutside(url)
                // Media stays in its page: on its own it would replace the Redlib interface.
                return url.authority != Redlib.AUTHORITY || Links.isMedia(url.path.orEmpty())
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
        web.setDownloadListener { url, _, _, _, _ -> save(url) }
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touch = event.x / web.width to event.y / web.height
            false
        }
        web.setOnLongClickListener {
            web.evaluateJavascript("${Links.AT_POINT}(${touch.first}, ${touch.second})") { if (it != "null") menu(JSONObject(it)) }
            // Elsewhere the long press goes on to select text.
            web.hitTestResult.type !in setOf(WebView.HitTestResult.UNKNOWN_TYPE, WebView.HitTestResult.EDIT_TEXT_TYPE)
        }
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

    /** The long-press menu for the link, image and video `found` under the finger. */
    private fun menu(found: JSONObject) {
        fun address(key: String) = if (found.isNull(key)) null else Uri.parse(found.getString(key)).takeIf { it.scheme == "http" || it.scheme == "https" }
        fun local(uri: Uri) = uri.authority == Redlib.AUTHORITY
        fun public(uri: Uri) = if (local(uri)) Links.public(Links.page(uri.encodedPath, uri.encodedQuery)) else "$uri"
        val items = mutableListOf<Pair<String, () -> Unit>>()
        // An image's link to itself is the image, not a link.
        address("link")?.takeUnless { local(it) && Links.isMedia(it.path.orEmpty()) }?.let(::public)?.let { url ->
            items += "Open in browser" to { openOutside(Uri.parse(url)) }
            items += "Copy link" to { copy(url) }
            items += "Share link" to { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null)) }
        }
        (address("image")?.let { "image" to it } ?: address("video")?.let { "video" to it })?.let { (kind, uri) ->
            items += "Save $kind" to { save("$uri") }
            items += "Share $kind" to { share("$uri") }
            public(uri)?.let { url -> items += "Copy $kind link" to { copy(url) } }
        }
        if (items.isNotEmpty()) AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, item -> items[item].second() }.show()
    }

    private fun copy(text: String) = getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

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

    /** Fetches `url` into the stream `sink` opens for its file name and type. */
    private fun fetch(url: String, sink: (name: String, type: String?) -> OutputStream) {
        val connection = URL(url).openConnection()
        val type = connection.contentType?.substringBefore(';')
        val name = URLUtil.guessFileName(url, connection.getHeaderField("Content-Disposition"), type)
        connection.getInputStream().use { input -> sink(name, type).use { input.copyTo(it) } }
    }

    /** Saves what `url` serves to the Downloads folder. */
    private fun save(url: String) {
        thread {
            try {
                fetch(url) { name, type ->
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        type?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                    }
                    contentResolver.openOutputStream(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!)!!
                }
                toast("Saved to Downloads")
            } catch (_: Exception) {
                toast("Could not save")
            }
        }
    }

    /** Shares what `url` serves as a file. */
    private fun share(url: String) {
        thread {
            try {
                val dir = SharedFiles.dir(this).apply { deleteRecursively(); mkdirs() }
                val send = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                fetch(url) { name, type ->
                    val file = File(dir, name)
                    send.setType(type ?: "*/*").putExtra(Intent.EXTRA_STREAM, SharedFiles.uri(file))
                    file.outputStream()
                }
                runOnUiThread { startActivity(Intent.createChooser(send, null)) }
            } catch (_: Exception) {
                toast("Could not share")
            }
        }
    }
}
