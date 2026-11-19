package dev.personal.berksapps.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock

class TokenProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("Only the shell may call this")
        }
        if (method != METHOD_TOKEN) return null
        val current = take() ?: return null
        return Bundle().apply { putBinder(KEY_TOKEN, current) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "dev.personal.berksapps.shellbridge"
        const val METHOD_TOKEN = "activityToken"
        const val KEY_TOKEN = "token"
        private const val VALID_MS = 30000L

        private var token: IBinder? = null
        private var offeredAt = 0L

        @Synchronized
        fun offer(activityToken: IBinder?) {
            token = activityToken
            offeredAt = SystemClock.elapsedRealtime()
        }

        @Synchronized
        private fun take(): IBinder? {
            val current = token
            token = null
            return if (SystemClock.elapsedRealtime() - offeredAt <= VALID_MS) current else null
        }
    }
}
