package app.gptdynamichook.hook

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.util.Log
import app.gptdynamichook.ObservationProvider

/**
 * One-way transport for sanitized observation reports from a hooked target
 * process back to the module app. Transport failure must never affect the hook.
 */
object ReportRelay {
    private const val TAG = "GPTDynamicHook"
    private const val AUTHORITY = "app.gptdynamichook.observations"
    private const val METHOD_PUBLISH = "publish"
    private const val KEY_REPORT = "report"

    @Volatile private var application: Application? = null

    /** True only when the provider explicitly acknowledges durable acceptance. */
    fun publish(json: String): Boolean {
        if (json.isEmpty()) return false
        val context = resolveApplication()
        if (context == null) {
            Log.w(TAG, "report relay unavailable: no application context")
            return false
        }
        return runCatching {
            val uri = Uri.parse("content://$AUTHORITY/report")
            val extras = Bundle().apply {
                putString(KEY_REPORT, json)
                putString("targetPackage", context.packageName)
            }
            val reply = context.contentResolver.call(uri, METHOD_PUBLISH, null, extras)
            val accepted = reply?.getBoolean(ObservationProvider.KEY_ACCEPTED, false) == true
            if (!accepted) {
                val reason = reply?.getString(ObservationProvider.KEY_REASON) ?: "no provider acknowledgement"
                Log.w(TAG, "report relay not accepted: $reason")
            }
            accepted
        }.getOrElse {
            Log.w(TAG, "report relay failed: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun resolveApplication(): Application? {
        application?.let { return it }
        val resolved = runCatching {
            val type = Class.forName("android.app.ActivityThread")
            val method = type.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            method.invoke(null) as? Application
        }.getOrNull()
        if (resolved != null) application = resolved
        return resolved
    }

    /** Authority is exposed for the module side to build the same Uri. */
    fun authority(): String = AUTHORITY

    fun keyReport(): String = KEY_REPORT
}
