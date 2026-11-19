package dev.personal.berksapps.install

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.personal.berksapps.adb.ShellCommand
import java.io.File

class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }
        )
        return cursor
    }

    override fun getType(uri: Uri): String = ShellCommand.APK_MIME

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    private fun fileFor(uri: Uri): File {
        if (uri.lastPathSegment != FILE_NAME || uri.pathSegments.size != 1) {
            throw SecurityException("Not served")
        }
        return File(File(requireNotNull(context).cacheDir, "staged"), FILE_NAME)
    }

    companion object {
        const val AUTHORITY = "dev.personal.berksapps.apk"
        const val FILE_NAME = "install.apk"
        val URI: Uri = Uri.Builder().scheme("content").authority(AUTHORITY).path(FILE_NAME).build()
    }
}
