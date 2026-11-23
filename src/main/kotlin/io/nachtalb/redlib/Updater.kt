package io.nachtalb.redlib

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Offers the newest published build once a day, downloads it with the download manager and installs it. */
class Updater(private val activity: Activity) {
    companion object {
        /** The action of the intent bringing an install's result back to the activity. */
        const val STATUS = "io.nachtalb.redlib.INSTALL_STATUS"
        private const val RELEASE = "https://github.com/Nachtalb/redlib-android/releases/latest/download"
        private const val APK = "redlib-update.apk"
        private const val AUTO_BLOCKER = "com.samsung.android.rampart"
        private const val DAY = 24 * 60 * 60 * 1000L
    }

    private val prefs = activity.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val downloads = activity.getSystemService(DownloadManager::class.java)
    private val apk = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK)
    private val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
    private var checking = false
    private var resumed = false

    /** Runs once the activity is in front again: an install after a background download or a settings screen. */
    private var onReturn: (() -> Unit)? = null

    private val done = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == prefs.getLong("download", -2)) downloaded()
        }
    }

    init {
        // Installed by now: the downloaded file has done its job.
        if (prefs.getLong("staged", 0) <= installed) apk.delete()
        activity.registerReceiver(done, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
    }

    fun resume() {
        resumed = true
        onReturn?.let { onReturn = null; return it() }
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) > DAY) check()
    }

    fun pause() {
        resumed = false
    }

    fun destroy() = activity.unregisterReceiver(done)

    /** The published version.json, null when it can't be fetched. */
    private fun latest(): String? = try {
        (URL("$RELEASE/version.json").openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 10_000
            inputStream.bufferedReader().use { it.readText() }
        }
    } catch (_: Exception) {
        null
    }

    private fun check() {
        if (checking) return
        checking = true
        thread {
            val json = latest()
            activity.runOnUiThread {
                checking = false
                if (json == null) return@runOnUiThread
                // "Later" waits for the next check, a day on.
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                val offer = Update.newer(json, installed)
                if (offer == null || activity.isFinishing) return@runOnUiThread
                AlertDialog.Builder(activity)
                    .setTitle("Update available")
                    .setMessage("Redlib ${offer.name} is available.")
                    .setPositiveButton("Download") { _, _ -> download(offer) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun download(offer: Update.Offer) {
        // Also deletes its file: the download manager picks another name rather than overwrite one.
        downloads.remove(prefs.getLong("download", -1))
        apk.delete()
        val request = DownloadManager.Request(Uri.parse("$RELEASE/redlib-android.apk"))
            .setTitle("Redlib ${offer.name}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, APK)
        prefs.edit().putLong("download", downloads.enqueue(request)).putLong("staged", offer.code).apply()
    }

    private fun downloaded() {
        val status = downloads.query(DownloadManager.Query().setFilterById(prefs.getLong("download", -1))).use {
            if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
        }
        // No row: the user cancelled the download.
        if (status == null) return
        if (status != DownloadManager.STATUS_SUCCESSFUL) return toast("The update could not be downloaded")
        // A build published meanwhile replaces the one just downloaded.
        thread {
            val newer = latest()?.let { Update.newer(it, prefs.getLong("staged", 0)) }
            activity.runOnUiThread {
                when {
                    newer != null -> download(newer)
                    resumed -> install()
                    else -> onReturn = ::install
                }
            }
        }
    }

    private fun install() {
        if (!apk.exists()) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            onReturn = { if (activity.packageManager.canRequestPackageInstalls()) install() }
            toast("Allow Redlib to install apps, then come back")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        thread {
            try {
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(activity.packageName)
                    // Where Android allows it, an update signed with the same key installs without a prompt.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite(APK, 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val result = Intent(activity, activity.javaClass).setAction(STATUS)
                    session.commit(PendingIntent.getActivity(activity, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
                }
            } catch (e: Exception) {
                toast("Update install failed: ${e.message}")
            }
        }
    }

    /** The installer's answer, delivered to the activity as an intent with [STATUS]. */
    @Suppress("DEPRECATION")
    fun status(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let(activity::startActivity)
            else -> when (Update.failure(status, message, autoBlockerInstalled())) {
                Update.Failure.AutoBlocker -> autoBlocker()
                Update.Failure.UserCancelled -> Unit
                Update.Failure.Other -> toast("Update install failed: $message")
            }
        }
    }

    private fun autoBlockerInstalled() = runCatching { activity.packageManager.getPackageInfo(AUTO_BLOCKER, 0) }.isSuccess

    /** Samsung's Auto Blocker stops self-updates: offer its screen, and install again on the way back. */
    private fun autoBlocker() {
        AlertDialog.Builder(activity)
            .setTitle("Auto Blocker is blocking the update")
            .setMessage("Turn off Auto Blocker, then come back to install.")
            .setPositiveButton("Open Auto Blocker") { _, _ ->
                val screens = listOf(
                    Intent().setComponent(ComponentName(AUTO_BLOCKER, "$AUTO_BLOCKER.ui.MainSettingActivity")),
                    Intent(Settings.ACTION_SECURITY_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                )
                if (screens.any { runCatching { activity.startActivity(it) }.isSuccess }) onReturn = ::install
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(text: String) = activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}
