package io.nachtalb.redlib

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Insets
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.FutureTask
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
            .apply {
                // Defaults for a single user's own phone. What the user sets in Redlib's settings wins.
                listOf("DEFAULT_USE_HLS", "DEFAULT_SHOW_NSFW", "DEFAULT_BLUR_NSFW", "DEFAULT_CLEAN_URLS", "ENABLE_GIPHY", "ENABLE_REDGIFS")
                    .forEach { environment()["REDLIB_$it"] = "on" }
            }
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
                // Bars another window hides (the share sheet) still keep their room, or the page jumps.
                val bars = if (fullscreen == null) insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()) else Insets.NONE
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom))
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

            // The spinner from startup stays until the page shows something.
            override fun onPageCommitVisible(view: WebView, url: String) {
                progress.visibility = View.GONE
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
        web.setDownloadListener { url, _, _, _, _ -> save(Uri.parse(url)) }
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
        val items = mutableListOf<Triple<String, Int, () -> Unit>>()
        // An image's link to itself is the image, not a link.
        address("link")?.takeUnless { local(it) && Links.isMedia(it.path.orEmpty()) }?.let(::public)?.let { url ->
            items += Triple("Open in browser", R.drawable.ic_open) { openOutside(Uri.parse(url)) }
            items += Triple("Copy link", R.drawable.ic_copy) { copy(url) }
            items += Triple("Share link", R.drawable.ic_share) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null)) }
        }
        (address("image")?.let { "image" to it } ?: address("video")?.let { "video" to it })?.let { (kind, uri) ->
            // An image is fetched while the menu is open, so sharing it doesn't start with a wait.
            val file = FutureTask { shareable("$uri") }.also { if (kind == "image") thread { it.run() } }
            items += Triple("Save $kind", R.drawable.ic_save) { save(uri) }
            items += Triple("Share $kind", R.drawable.ic_share) { share(file) }
            public(uri)?.let { url -> items += Triple("Copy $kind link", R.drawable.ic_copy) { copy(url) } }
        }
        if (items.isEmpty()) return
        val dialog = AlertDialog.Builder(this)
        val rows = object : ArrayAdapter<String>(dialog.context, android.R.layout.simple_list_item_1, items.map { it.first }) {
            override fun getView(position: Int, convert: View?, parent: ViewGroup) = (super.getView(position, convert, parent) as TextView).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(items[position].second, 0, 0, 0)
                compoundDrawablePadding = (24 * resources.displayMetrics.density).toInt()
            }
        }
        dialog.setAdapter(rows) { _, item -> items[item].third() }.show()
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
                if (ready) return@runOnUiThread web.loadUrl(url)
                progress.visibility = View.GONE
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

    /** Saves a file of the local server to Downloads through the system's download manager, which notifies when done. */
    private fun save(uri: Uri) {
        val name = Links.fileName(uri.path.orEmpty()) ?: URLUtil.guessFileName("$uri", null, null)
        val request = DownloadManager.Request(uri)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        getSystemService(DownloadManager::class.java).enqueue(request)
    }

    /** Fetches what `url` serves into the share folder and returns the intent sending that file. */
    private fun shareable(url: String): Intent {
        val connection = URL(url).openConnection()
        val type = connection.contentType?.substringBefore(';')
        val name = Links.fileName(Uri.parse(url).path.orEmpty()) ?: URLUtil.guessFileName(url, connection.getHeaderField("Content-Disposition"), type)
        val file = File(SharedFiles.dir(this).apply { deleteRecursively(); mkdirs() }, name)
        connection.getInputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        return Intent(Intent.ACTION_SEND).setType(type ?: "*/*").putExtra(Intent.EXTRA_STREAM, SharedFiles.uri(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Opens the share sheet for `file`, fetching it first unless that already began. */
    private fun share(file: FutureTask<Intent>) {
        progress.visibility = View.VISIBLE
        thread {
            val send = try {
                file.run()
                file.get()
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (send == null) toast("Could not share") else startActivity(Intent.createChooser(send, null))
            }
        }
    }
}
