package io.nachtalb.redlib

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.net.URLConnection

/** Hands the files in the cache's share folder to the app the user shares them with. */
class SharedFiles : ContentProvider() {
    companion object {
        fun dir(context: Context) = File(context.cacheDir, "share")

        fun uri(file: File): Uri = Uri.parse("content://io.nachtalb.redlib.share/").buildUpon().appendPath(file.name).build()
    }

    private fun file(uri: Uri) = File(dir(context!!), uri.lastPathSegment!!)

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String? = URLConnection.guessContentTypeFromName(file(uri).name)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(file(uri).name, file(uri).length())) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
}
