package app.gptdynamichook.core.store

import app.gptdynamichook.core.model.HookProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileCollectionTest {
    private fun profile(id: String, method: String = "send", enabled: Boolean = false) =
        HookProfile(id, "com.example.client", "com.example.Api", method, emptyList(), enabled)

    @Test fun upsertReplacesSameIdWhenTargetChanges() {
        val original = profile("stable-id")
        val edited = profile("stable-id", method = "execute")
        assertEquals(listOf(edited), ProfileCollection.upsert(listOf(original), edited))
    }

    @Test fun upsertPreservesOtherProfiles() {
        val first = profile("one")
        val second = profile("two")
        assertEquals(listOf(first, second), ProfileCollection.upsert(listOf(first), second))
    }

    @Test fun deleteRemovesOnlyMatchingId() {
        val first = profile("one")
        val second = profile("two")
        assertEquals(listOf(second), ProfileCollection.delete(listOf(first, second), "one"))
    }

    @Test fun setEnabledChangesOnlyMatchingProfile() {
        val first = profile("one")
        val second = profile("two")
        assertEquals(
            listOf(first.copy(enabled = true), second),
            ProfileCollection.setEnabled(listOf(first, second), "one", true)
        )
    }

    @Test fun disabledProfilesAreNeverSelectedForHookInstallation() {
        val disabled = profile("off", enabled = false)
        val enabled = profile("on", enabled = true)
        assertEquals(listOf(enabled), ProfileCollection.enabledOnly(listOf(disabled, enabled)))
    }

    @Test fun allDisabledProfilesProduceNoHookInstallations() {
        assertEquals(
            emptyList<HookProfile>(),
            ProfileCollection.enabledOnly(listOf(profile("off-a"), profile("off-b")))
        )
    }
}
