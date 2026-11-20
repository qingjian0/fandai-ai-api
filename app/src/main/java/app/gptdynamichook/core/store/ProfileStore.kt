package app.gptdynamichook.core.store

import android.content.SharedPreferences
import app.gptdynamichook.core.codec.HookProfileCodec
import app.gptdynamichook.core.model.HookProfile
import io.github.libxposed.service.XposedService

class ProfileStore(private val service: XposedService) {
    private val preferences = service.getRemotePreferences(PREFERENCES_GROUP)

    fun load(): List<HookProfile> =
        HookProfileCodec.decode(preferences.getString(PROFILES_KEY, "[]"))

    /** Upsert by stable profile ID; changing a target must not create a second profile. */
    fun save(profile: HookProfile): Boolean {
        val next = ProfileCollection.upsert(load(), profile)
        return preferences.edit()
            .putString(PROFILES_KEY, HookProfileCodec.encode(next))
            .commit()
    }

    fun delete(profileId: String): Boolean {
        val next = ProfileCollection.delete(load(), profileId)
        return preferences.edit()
            .putString(PROFILES_KEY, HookProfileCodec.encode(next))
            .commit()
    }

    fun setEnabled(profileId: String, enabled: Boolean): Boolean {
        val next = ProfileCollection.setEnabled(load(), profileId, enabled)
        return preferences.edit()
            .putString(PROFILES_KEY, HookProfileCodec.encode(next))
            .commit()
    }

    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFERENCES_GROUP = "gpt_dynamic_hook_profiles"
        const val PROFILES_KEY = "profiles_json"
    }
}
