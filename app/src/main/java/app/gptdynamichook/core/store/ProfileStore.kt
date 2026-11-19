package app.gptdynamichook.core.store

import android.content.SharedPreferences
import app.gptdynamichook.core.codec.HookProfileCodec
import app.gptdynamichook.core.model.HookProfile
import io.github.libxposed.service.XposedService

class ProfileStore(private val service: XposedService) {
    private val preferences = service.getRemotePreferences(PREFERENCES_GROUP)

    fun load(): List<HookProfile> =
        HookProfileCodec.decode(preferences.getString(PROFILES_KEY, "[]"))

    fun save(profile: HookProfile): Boolean {
        val current = load().filterNot {
            it.targetPackage == profile.targetPackage &&
                it.className == profile.className &&
                it.methodName == profile.methodName &&
                it.parameterTypeNames == profile.parameterTypeNames
        }
        return preferences.edit()
            .putString(PROFILES_KEY, HookProfileCodec.encode(current + profile))
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
