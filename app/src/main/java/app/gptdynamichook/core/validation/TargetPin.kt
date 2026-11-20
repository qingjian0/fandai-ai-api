package app.gptdynamichook.core.validation

import app.gptdynamichook.core.model.HookProfile

/**
 * Enforces optional target identity pins before installing a HookProfile.
 *
 * Unpinned legacy profiles remain compatible. Once a pin is configured, missing
 * runtime metadata is a rejection rather than an implicit match.
 */
object TargetPin {
    fun mismatchReason(
        profile: HookProfile,
        installedVersionCode: Long?,
        installedSignerSha256: String?
    ): String? {
        val expectedVersion = profile.targetVersionCode
        if (expectedVersion != null) {
            if (installedVersionCode == null) return "target_version_unavailable"
            if (installedVersionCode != expectedVersion) return "target_version_mismatch"
        }

        val expectedSigner = profile.targetSignerSha256
        if (expectedSigner != null) {
            if (installedSignerSha256 == null) return "target_signer_unavailable"
            if (!expectedSigner.equals(installedSignerSha256, ignoreCase = true)) {
                return "target_signer_mismatch"
            }
        }
        return null
    }
}
