package app.gptdynamichook.core.store

import app.gptdynamichook.core.model.HookProfile

/** Pure collection operations shared by the remote store and JVM tests. */
object ProfileCollection {
    fun upsert(profiles: List<HookProfile>, profile: HookProfile): List<HookProfile> =
        profiles.filterNot { it.id == profile.id } + profile

    fun delete(profiles: List<HookProfile>, profileId: String): List<HookProfile> =
        profiles.filterNot { it.id == profileId }

    fun setEnabled(
        profiles: List<HookProfile>,
        profileId: String,
        enabled: Boolean
    ): List<HookProfile> = profiles.map {
        if (it.id == profileId) it.copy(enabled = enabled) else it
    }
}
