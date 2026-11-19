package app.gptdynamichook.core.validation

import app.gptdynamichook.core.model.HookProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetPinTest {
    private val base = HookProfile(
        id = "pin-test",
        targetPackage = "com.openai.chatgpt",
        className = "com.example.Client",
        methodName = "send",
        parameterTypeNames = emptyList()
    )

    @Test
    fun legacyUnpinnedProfileDoesNotRequireRuntimeMetadata() {
        assertNull(TargetPin.mismatchReason(base, null, null))
    }

    @Test
    fun versionPinRequiresMetadataAndExactMatch() {
        val pinned = base.copy(targetVersionCode = 42L)
        assertEquals("target_version_unavailable", TargetPin.mismatchReason(pinned, null, null))
        assertEquals("target_version_mismatch", TargetPin.mismatchReason(pinned, 41L, null))
        assertNull(TargetPin.mismatchReason(pinned, 42L, null))
    }

    @Test
    fun signerPinRequiresMetadataAndExactDigest() {
        val digest = "a".repeat(64)
        val pinned = base.copy(targetSignerSha256 = digest)
        assertEquals("target_signer_unavailable", TargetPin.mismatchReason(pinned, null, null))
        assertEquals(
            "target_signer_mismatch",
            TargetPin.mismatchReason(pinned, null, "b".repeat(64))
        )
        assertNull(TargetPin.mismatchReason(pinned, null, digest))
        assertNull(TargetPin.mismatchReason(pinned, null, digest.uppercase()))
    }

    @Test
    fun bothPinsMustMatch() {
        val pinned = base.copy(
            targetVersionCode = 42L,
            targetSignerSha256 = "c".repeat(64)
        )
        assertEquals(
            "target_signer_unavailable",
            TargetPin.mismatchReason(pinned, 42L, null)
        )
        assertNull(TargetPin.mismatchReason(pinned, 42L, "c".repeat(64)))
    }
}
