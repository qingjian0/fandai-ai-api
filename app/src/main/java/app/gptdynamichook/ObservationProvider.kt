package app.gptdynamichook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * Receives sanitized observation reports from the hooked target process and
 * exposes them to the module UI.
 *
 * publish is limited to the configured target UID and a schema-v1 payload
 * whose declared target matches the authorized package. read/clear are
 * restricted to the module app UID.
 */
class ObservationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        return when (method) {
            METHOD_PUBLISH -> publish(caller, extras)
            METHOD_READ -> read(caller)
            METHOD_CLEAR -> clear(caller)
            else -> denied("unsupported method")
        }
    }

    private fun publish(caller: Int, extras: Bundle?): Bundle {
        val payload = extras?.getString(KEY_REPORT).orEmpty()
        val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
        if (payload.isEmpty() || payloadBytes > MAX_REPORT_BYTES) {
            return denied("report is empty or exceeds byte limit")
        }

        val declaredTarget = extras?.getString(KEY_TARGET_PACKAGE).orEmpty()
        if (!isTargetUid(caller, declaredTarget)) {
            Log.w(TAG, "publish rejected for unauthorized caller")
            return denied("caller is not the configured target package")
        }

        val validSchema = runCatching {
            val root = JSONObject(payload)
            root.optInt("schemaVersion", -1) == SCHEMA_VERSION &&
                root.optString("targetPackage") == declaredTarget
        }.getOrDefault(false)
        if (!validSchema) return denied("invalid report schema or target package")

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
     * A caller-supplied package string is never sufficient authorization:
     * PackageManager must independently confirm ownership of the target UID.
     */
    private fun isTargetUid(callerUid: Int, declaredTarget: String): Boolean {
        if (callerUid <= 0 || declaredTarget != TARGET_PACKAGE) return false
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
        private const val SCHEMA_VERSION = 1

        const val METHOD_PUBLISH = "publish"
        const val METHOD_READ = "read"
        const val METHOD_CLEAR = "clear"

        const val KEY_REPORT = "report"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_REASON = "reason"
        const val KEY_PUBLISHED_AT = "publishedAt"
        const val KEY_PUBLISH_COUNT = "publishCount"
        const val KEY_TARGET_PACKAGE = "targetPackage"

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
