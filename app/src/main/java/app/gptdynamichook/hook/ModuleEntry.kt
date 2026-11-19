package app.gptdynamichook.hook

import android.util.Log
import app.gptdynamichook.core.codec.HookProfileCodec
import app.gptdynamichook.core.model.HookProfile
import app.gptdynamichook.core.resolve.ExecutableResolver
import app.gptdynamichook.core.store.ProfileStore
import app.gptdynamichook.core.validation.HookProfileValidator
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic hook entry. It installs pass-through interceptors only for explicitly
 * enabled, validated profiles in the exact target package.
 */
class ModuleEntry : XposedModule() {
    private val installed = ConcurrentHashMap<String, XposedInterface.HookHandle>()
    private var preferencesListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Loaded process=${param.processName}, API=$apiVersion, framework=$frameworkName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == packageName) return
        if (apiVersion < XposedInterface.API_102) {
            log(Log.WARN, TAG, "Runtime API $apiVersion is below required API 102")
            return
        }

        val preferences = runCatching {
            getRemotePreferences(ProfileStore.PREFERENCES_GROUP)
        }.getOrElse {
            log(Log.WARN, TAG, "Remote preferences unavailable: ${it.javaClass.simpleName}")
            return
        }

        val apply = {
            val profiles = HookProfileCodec.decode(
                preferences.getString(ProfileStore.PROFILES_KEY, "[]")
            )
            applyProfiles(param.packageName, param.classLoader, profiles)
        }

        apply()
        if (preferencesListener == null) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == ProfileStore.PROFILES_KEY) apply()
            }
            preferencesListener = listener
            preferences.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun applyProfiles(
        packageName: String,
        classLoader: ClassLoader,
        profiles: List<HookProfile>
    ) {
        val active = profiles.filter {
            HookProfileValidator.matchesTarget(it, packageName)
        }
        val activeIds = active.map { it.id }.toSet()

        installed.keys.filterNot(activeIds::contains).forEach { id ->
            installed.remove(id)?.unhook()
        }

        active.forEach { profile ->
            val method = ExecutableResolver.resolveMethod(
                classLoader,
                profile.className,
                profile.methodName,
                profile.parameterTypeNames
            )
            if (method == null) {
                log(Log.WARN, TAG, "Profile ${profile.id}: exact method signature not found")
                return@forEach
            }
            installPassThrough(profile, method)
        }
    }

    private fun installPassThrough(profile: HookProfile, method: Method) {
        if (installed.containsKey(profile.id)) return
        runCatching {
            val handle = hook(method)
                .setId(profile.id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> chain.proceed() }
            installed.put(profile.id, handle)?.unhook()
            log(Log.INFO, TAG, "Installed validated pass-through profile ${profile.id}")
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to install profile ${profile.id}: ${it.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "GPTDynamicHook"
    }
}
