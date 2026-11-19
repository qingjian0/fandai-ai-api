package app.gptdynamichook.hook

import android.content.SharedPreferences
import android.util.Log
import app.gptdynamichook.core.codec.HookProfileCodec
import app.gptdynamichook.core.model.HookProfile
import app.gptdynamichook.core.model.HookProfileSignature
import app.gptdynamichook.core.report.HookEventCollector
import app.gptdynamichook.core.report.HookReportCodec
import app.gptdynamichook.core.report.HookResultParser
import app.gptdynamichook.core.resolve.ExecutableResolver
import app.gptdynamichook.core.store.ProfileCollection
import app.gptdynamichook.core.store.ProfileStore
import app.gptdynamichook.core.validation.HookProfileValidator
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Installs pass-through interceptors only for explicitly enabled, validated profiles. */
class ModuleEntry : XposedModule() {
    private data class InstalledHook(
        val signature: String,
        val handle: XposedInterface.HookHandle
    )

    private val collector = HookEventCollector()
    private val publishQueued = AtomicBoolean(false)
    private val publisher = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gpt-hook-report").apply { isDaemon = true }
    }
    private val installed = ConcurrentHashMap<String, InstalledHook>()
    @Volatile private var targetPackage: String = ""
    private val packageLocks = ConcurrentHashMap<String, Any>()
    private val preferenceListeners = ConcurrentHashMap<String, SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Loaded process=\${param.processName}, API=$apiVersion, framework=$frameworkName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        targetPackage = param.packageName
        if (apiVersion < XposedInterface.API_102) {
            log(Log.WARN, TAG, "Runtime API $apiVersion is below required API 102")
            return
        }

        val preferences = runCatching {
            getRemotePreferences(ProfileStore.PREFERENCES_GROUP)
        }.getOrElse {
            log(Log.WARN, TAG, "Remote preferences unavailable: \${it.javaClass.simpleName}")
            return
        }

        val apply = {
            val lock = packageLocks.computeIfAbsent(param.packageName) { Any() }
            synchronized(lock) {
                runCatching {
                    val profiles = HookProfileCodec.decode(
                        preferences.getString(ProfileStore.PROFILES_KEY, "[]")
                    )
                    applyProfiles(param.packageName, param.classLoader, profiles)
                }.onFailure {
                    log(Log.ERROR, TAG, "Profile refresh failed: \${it.javaClass.simpleName}")
                }
            }
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
            log(Log.WARN, TAG, "Ignoring duplicate Profile IDs: \${duplicateIds.joinToString()}")
        }

        val unambiguous = matching.filterNot { it.id in duplicateIds }
        val active = ProfileCollection.enabledOnly(unambiguous)
        val desired = active.associateBy { hookKey(packageName, it.id) }

        installed.entries
            .filter { (key, registration) ->
                key.startsWith("$packageName:") &&
                    desired[key]?.let(HookProfileSignature::of) != registration.signature
            }
            .forEach { entry ->
                val result = runCatching { entry.value.handle.unhook() }
                if (result.isSuccess) {
                    installed.remove(entry.key, entry.value)
                } else {
                    log(Log.ERROR, TAG, "Failed to unhook stale profile: \${entry.key}")
                }
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
                log(Log.WARN, TAG, "Profile \${profile.id}: exact method signature not found")
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
                .intercept { chain ->
                    val started = System.nanoTime()
                    try {
                        val result = chain.proceed()
                        collector.record(
                            profile,
                            (System.nanoTime() - started) / 1_000_000L,
                            "ok",
                            HookResultParser.parseObject(result)
                        )
                        schedulePublish()
                        result
                    } catch (t: Throwable) {
                        collector.record(
                            profile,
                            (System.nanoTime() - started) / 1_000_000L,
                            "throw:" + t.javaClass.simpleName.take(64),
                            null
                        )
                        schedulePublish()
                        throw t
                    }
                }
            val registration = InstalledHook(HookProfileSignature.of(profile), handle)
            installed.put(key, registration)?.handle?.unhook()
            log(Log.INFO, TAG, "Installed validated pass-through profile \${profile.id}")
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to install profile \${profile.id}: \${it.javaClass.simpleName}")
        }
    }

    private fun schedulePublish() {
        if (!publishQueued.compareAndSet(false, true)) return
        runCatching {
            publisher.schedule({
                publishQueued.set(false)
                if (collector.isEmpty()) return@schedule
                val snapshot = collector.drain()
                val json = HookReportCodec.encode(snapshot, targetPackage)
                log(Log.INFO, TAG, "observation-report: " + json)
            }, REPORT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }.onFailure { publishQueued.set(false) }
    }

    private fun hookKey(packageName: String, profileId: String) = "$packageName:$profileId"

    private companion object {
        const val TAG = "GPTDynamicHook"
        const val REPORT_DEBOUNCE_MS = 2000L
    }
}
