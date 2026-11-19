package app.gptdynamichook.hook

import android.content.SharedPreferences
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
    private data class InstalledHook(
        val signature: String,
        val handle: XposedInterface.HookHandle
    )

    private val installed = ConcurrentHashMap<String, InstalledHook>()
    private val preferenceListeners = ConcurrentHashMap<String, SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Loaded process=${param.processName}, API=$apiVersion, framework=$frameworkName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
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
        preferenceListeners.computeIfAbsent(param.packageName) {
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == ProfileStore.PROFILES_KEY) apply()
            }.also(preferences::registerOnSharedPreferenceChangeListener)
        }
    }

    private fun applyProfiles(
        packageName: String,
        classLoader: ClassLoader,
        profiles: List<HookProfile>
    ) {
        val matching = profiles.filter {
            HookProfileValidator.matchesTarget(it, packageName)
        }
        val duplicateIds = matching.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            log(Log.WARN, TAG, "Ignoring duplicate Profile IDs: ${duplicateIds.joinToString()}")
        }
        val active = matching.filterNot { it.id in duplicateIds }
        val desired = active.associateBy { hookKey(packageName, it.id) }

        // A reused ID must not preserve a hook when its target signature changes.
        installed.entries
            .filter { (key, registration) ->
                key.startsWith("$packageName:") &&
                    desired[key]?.let(::signatureOf) != registration.signature
            }
            .forEach { entry ->
                installed.remove(entry.key, entry.value)?.handle?.unhook()
            }

        active.forEach { profile ->
            val key = hookKey(packageName, profile.id)
            if (installed.containsKey(key)) return@forEach

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
            installPassThrough(key, profile, method)
        }
    }

    private fun installPassThrough(key: String, profile: HookProfile, method: Method) {
        runCatching {
            val handle = hook(method)
                .setId(key)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> chain.proceed() }
            val registration = InstalledHook(signatureOf(profile), handle)
            installed.put(key, registration)?.handle?.unhook()
            log(Log.INFO, TAG, "Installed validated pass-through profile ${profile.id}")
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to install profile ${profile.id}: ${it.javaClass.simpleName}")
        }
    }

    private fun signatureOf(profile: HookProfile): String =
        listOf(profile.className, profile.methodName, profile.parameterTypeNames.joinToString(","))
            .joinToString("#")

    private fun hookKey(packageName: String, profileId: String) = "$packageName:$profileId"

    private companion object {
        const val TAG = "GPTDynamicHook"
    }
}
