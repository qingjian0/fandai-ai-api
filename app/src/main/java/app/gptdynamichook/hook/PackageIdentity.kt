package app.gptdynamichook.hook

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Best-effort installed-package identity lookup for the current hooked process.
 *
 * PackageReady is invoked before the target Application is necessarily created,
 * so the lookup first uses currentApplication() and falls back to ActivityThread's
 * system context. If Android denies either path, pin checks receive null metadata
 * and must fail closed.
 */
internal data class PackageIdentity(
    val versionCode: Long?,
    val signerSha256: String?
) {
    companion object {
        fun of(packageName: String): PackageIdentity {
            val context = resolveContext()
                ?: return PackageIdentity(versionCode = null, signerSha256 = null)
            val packageInfo = runCatching {
                val flags = if (Build.VERSION.SDK_INT >= 28) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
                context.packageManager.getPackageInfo(packageName, flags)
            }.getOrNull() ?: return PackageIdentity(null, null)

            return PackageIdentity(
                versionCode = packageInfo.versionCodeLongCompat(),
                signerSha256 = packageInfo.signerSha256Compat()
            )
        }

        private fun resolveContext(): Context? {
            val thread = runCatching {
                val type = Class.forName("android.app.ActivityThread")
                type.getDeclaredMethod("currentActivityThread").apply {
                    isAccessible = true
                }.invoke(null)
            }.getOrNull() ?: return null

            val type = thread.javaClass
            val application = runCatching {
                type.getDeclaredMethod("currentApplication").apply {
                    isAccessible = true
                }.invoke(null) as? Application
            }.getOrNull()
            if (application != null) return application

            return runCatching {
                type.getDeclaredMethod("getSystemContext").apply {
                    isAccessible = true
                }.invoke(thread) as? Context
            }.getOrNull()
        }

        private fun PackageInfo.versionCodeLongCompat(): Long =
            if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

        private fun PackageInfo.signerSha256Compat(): String? {
            val certificates = if (Build.VERSION.SDK_INT >= 28) {
                signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                signatures?.toList().orEmpty()
            }
            // The current profile schema stores exactly one signer digest.
            // A multi-signer package cannot be represented without ambiguity;
            // return null so any configured signer pin fails closed.
            if (certificates.size != 1) return null
            return MessageDigest.getInstance("SHA-256")
                .digest(certificates.single().toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }
    }
}
