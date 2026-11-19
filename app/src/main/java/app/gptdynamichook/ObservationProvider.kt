package app.gptdynamichook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

/**
 * Receives sanitized observation reports from the hooked target process and
 * exposes them to the module UI.
 *
 * Access rules:
 *  - `publish` is writable only by a UID that owns the configured target package.
 *    The provider is exported because the target app has a different UID; the
 *    UID check is what actually gates it.
 *  - `read` / `clear` are restricted to the module app's own UID.
 *  - No query/insert/update/delete surface: reports cannot become a data store.
 *
 * The payload is bounded before it is accepted, so a misbehaving caller cannot
 * grow module memory without limit.
 */
class ObservationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        val self = context?.applicationInfo?.uid ?: -1
        return when (method) {
            METHOD_PUBLISH -> publish(caller, extras)
            METHOD_READ -> read(caller)
            METHOD_CLEAR -> clear(caller)
            else -> denied("unsupported method")
        }.also { if (self < 0) Log.w(TAG, "provider has no context") }
    }

    private fun publish(caller: Int, extras: Bundle?): Bundle {
        val payload = extras?.getString(KEY_REPORT).orEmpty()
        if (payload.length > MAX_REPORT_BYTES) {
            return denied("report exceeds ${MAX_REPORT_BYTES} bytes")
        }
        val declaredTarget = extras?.getString("targetPackage").orEmpty()
        if (!isTargetUid(caller, declaredTarget)) {
            Log.w(TAG, "publish rejected for uid $caller")
            return denied("caller is not the configured target package")
        }
        synchronized(lock) {
            latestReport = payload
            publishedAt = System.currentTimeMillis()
            publishCount += 1
        }
        return Bundle().apply { putBoolean(KEY_ACCEPTED, true) }
    }

    private fun read(caller: Int): Bundle {
        if (caller != ownUid()) return denied("read is restricted to the module app")
        return synchronized(lock) {
            Bundle().apply {
                putString(KEY_REPORT, latestReport)
                putLong(KEY_PUBLISHED_AT, publishedAt)
                putInt(KEY_PUBLISH_COUNT, publishCount)
            }
        }
    }

    private fun clear(caller: Int): Bundle {
        if (caller != ownUid()) return denied("clear is restricted to the module app")
        synchronized(lock) {
            latestReport = null
            publishedAt = 0L
            publishCount = 0
        }
        return Bundle().apply { putBoolean(KEY_ACCEPTED, true) }
    }

    private fun denied(reason: String): Bundle =
        Bundle().apply {
            putBoolean(KEY_ACCEPTED, false)
            putString(KEY_REASON, reason)
        }

    private fun ownUid(): Int = context?.applicationInfo?.uid ?: -1

    /**
     * Accept only when the caller UID really owns the configured target package,
     * and only when the payload claims the same package. A spoofed
     * `targetPackage` string alone never authorizes a write.
     */
    private fun isTargetUid(callerUid: Int, declaredTarget: String): Boolean {
        if (callerUid <= 0) return false
        if (declaredTarget != TARGET_PACKAGE) return false
        val packages = context?.packageManager?.getPackagesForUid(callerUid) ?: return false
        return packages.any { it == TARGET_PACKAGE }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "ObservationProvider"
        private const val TARGET_PACKAGE = "com.openai.chatgpt"
        private const val MAX_REPORT_BYTES = 64 * 1024

        const val METHOD_PUBLISH = "publish"
        const val METHOD_READ = "read"
        const val METHOD_CLEAR = "clear"

        const val KEY_REPORT = "report"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_REASON = "reason"
        const val KEY_PUBLISHED_AT = "publishedAt"
        const val KEY_PUBLISH_COUNT = "publishCount"

        const val AUTHORITY = "app.gptdynamichook.observations"

        private val lock = Any()

        @Volatile private var latestReport: String? = null

        @Volatile private var publishedAt: Long = 0L

        @Volatile private var publishCount: Int = 0

        /** Same-process read used by the module UI (no Binder round trip). */
        fun snapshot(): Triple<String?, Long, Int> =
            synchronized(lock) { Triple(latestReport, publishedAt, publishCount) }
    }
}
