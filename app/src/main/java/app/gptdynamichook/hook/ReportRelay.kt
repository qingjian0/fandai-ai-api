package app.gptdynamichook.hook

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.util.Log
import app.gptdynamichook.ObservationProvider

/**
 * One-way transport for the observation report, from the hooked target process
 * back to the module app.
 *
 * Why not logcat only: the module app cannot read another process' logcat
 * buffer without adb, so a user who enabled a profile but has no adb would
 * never see a report. This relay carries the already-sanitized JSON through a
 * Binder call so the module UI can display it.
 *
 * The target process has no injected Context, so the application instance is
 * resolved through ActivityThread. Every failure is swallowed on purpose: a
 * broken relay must never change the hooked method's behavior.
 */
object ReportRelay {
    private const val TAG = "GPTDynamicHook"
    private const val AUTHORITY = "app.gptdynamichook.observations"
    private const val METHOD_PUBLISH = "publish"
    private const val KEY_REPORT = "report"

    @Volatile private var application: Application? = null
    @Volatile private var disabled = false

    fun publish(json: String) {
        if (disabled || json.isEmpty()) return
        val context = resolveApplication()
        if (context == null) {
            disabled = true
            Log.w(TAG, "report relay unavailable: no application context")
            return
        }
        runCatching {
            val uri = Uri.parse("content://$AUTHORITY/report")
            val extras = Bundle().apply {
                putString(KEY_REPORT, json)
                putString("targetPackage", context.packageName)
            }
            context.contentResolver.call(uri, METHOD_PUBLISH, null, extras)
        }.onFailure {
            // A rejected or unavailable relay is not a hook failure.
            Log.w(TAG, "report relay rejected: ${it.javaClass.simpleName}")
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

    fun isDisabled(): Boolean = disabled
}
