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
import app.gptdynamichook.core.rules.HookRule
import app.gptdynamichook.core.store.ProfileCollection
import app.gptdynamichook.core.store.ProfileStore
import app.gptdynamichook.core.validation.HookProfileValidator
import app.gptdynamichook.core.validation.TargetPin
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Installs pass-through interceptors only for explicitly enabled, validated profiles. */
class ModuleEntry : XposedModule() {
    private data class InstalledHook(
        val signature: String,
        val handle: XposedInterface.HookHandle
    )

    private val collectors = ConcurrentHashMap<String, HookEventCollector>()
    private val publishQueued = ConcurrentHashMap<String, AtomicBoolean>()
    private val relayFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val publisher = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gpt-hook-report").apply { isDaemon = true }
    }
    private val installed = ConcurrentHashMap<String, InstalledHook>()
    private val packageLocks = ConcurrentHashMap<String, Any>()
    @Volatile private var ruleEngine: RuleEngine? = null
    @Volatile private var ruleWatcher: RuleWatcher? = null
    @Volatile private var channelWatcher: ChannelWatcher? = null
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
            val lock = packageLocks.computeIfAbsent(param.packageName) { Any() }
            synchronized(lock) {
                runCatching {
                    val profiles = HookProfileCodec.decode(
                        preferences.getString(ProfileStore.PROFILES_KEY, "[]")
                    )
                    applyProfiles(param.packageName, param.classLoader, profiles)
                }.onFailure {
                    log(Log.ERROR, TAG, "Profile refresh failed: ${it.javaClass.simpleName}")
                }
            }
        }

        apply()
        preferenceListeners.computeIfAbsent(param.packageName) {
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == ProfileStore.PROFILES_KEY) apply()
            }.also(preferences::registerOnSharedPreferenceChangeListener)
        }

        startRuleWatch(param.packageName, param.classLoader)
    }

    /**
     * Starts the sweep loop: an on-disk rule file is re-read every couple of
     * seconds and applied without restarting the target process. Rules may match
     * many classes by regex and capture stack traces, which is what allows an
     * operator to widen, read, and narrow iteratively.
     */
    private fun startRuleWatch(packageName: String, classLoader: ClassLoader) {
        if (ruleWatcher != null) return
        val rulesFile = RuleWatcher.rulesFileFor(packageName)
        val reportFile = RuleWatcher.reportFileFor(packageName)
        // Reflective command channel: the module compiles once, then an operator
        // drives exploration with JSON commands instead of new builds.
        val channel = ChannelWatcher(packageName, classLoader) { line -> log(Log.INFO, TAG, line) }
        channel.start()
        channelWatcher = channel
        val engine = RuleEngine(this, reportFile, { line -> log(Log.INFO, TAG, line) }) { name, value ->
            channel.expose(name, value)
        }
        ruleEngine = engine
        val watcher = RuleWatcher(
            packageName = packageName,
            rulesFile = rulesFile,
            reportFile = reportFile,
            classLoader = classLoader,
            log = { line -> log(Log.INFO, TAG, line) },
            apply = { rules: List<HookRule> ->
                val lock = packageLocks.computeIfAbsent(packageName) { Any() }
                synchronized(lock) {
                    runCatching { engine.apply(packageName, classLoader, rules) }
                        .onFailure { log(Log.ERROR, TAG, "rule apply failed: ${it.javaClass.simpleName}") }
                }
            }
        )
        watcher.start()
        ruleWatcher = watcher
        log(Log.INFO, TAG, "rule watcher started; file=$rulesFile")
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
                    log(Log.ERROR, TAG, "Failed to unhook stale profile: ${entry.key}")
                }
            }

        // PackageManager identity is invariant for this package during one
        // profile refresh. Avoid a Binder lookup for every individual profile.
        val identity = if (active.isNotEmpty()) PackageIdentity.of(packageName) else null
        active.forEach { profile ->
            val key = hookKey(packageName, profile.id)
            if (installed.containsKey(key)) return@forEach

            val pinFailure = TargetPin.mismatchReason(
                profile,
                identity?.versionCode,
                identity?.signerSha256
            )
            if (pinFailure != null) {
                log(Log.WARN, TAG, "Profile ${profile.id} rejected by pin: $pinFailure")
                return@forEach
            }

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
            installPassThrough(key, packageName, profile, method)
        }
    }

    private fun installPassThrough(
        key: String,
        packageName: String,
        profile: HookProfile,
        method: Method
    ) {
        val collector = collectors.computeIfAbsent(packageName) { HookEventCollector() }
        runCatching {
            val handle = hook(method)
                .setId(key)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val started = System.nanoTime()
                    val arguments = runCatching {
                        HookResultParser.parseArguments(chain.args)
                    }.getOrNull()
                    try {
                        val result = chain.proceed()
                        collector.record(
                            profile,
                            (System.nanoTime() - started) / 1_000_000L,
                            "ok",
                            HookResultParser.parseObject(result),
                            arguments
                        )
                        schedulePublish(packageName)
                        result
                    } catch (t: Throwable) {
                        collector.record(
                            profile,
                            (System.nanoTime() - started) / 1_000_000L,
                            "throw:" + t.javaClass.simpleName.take(64),
                            null,
                            arguments
                        )
                        schedulePublish(packageName)
                        throw t
                    }
                }
            val registration = InstalledHook(HookProfileSignature.of(profile), handle)
            installed.put(key, registration)?.handle?.unhook()
            log(Log.INFO, TAG, "Installed validated pass-through profile ${profile.id}")
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to install profile ${profile.id}: ${it.javaClass.simpleName}")
        }
    }

    /**
     * Each target package owns an isolated collector and publish queue. A report
     * can therefore never attribute events from one package to another.
     */
    private fun schedulePublish(packageName: String, delayMs: Long = REPORT_DEBOUNCE_MS) {
        if (delayMs == REPORT_DEBOUNCE_MS) relayFailures.remove(packageName)
        val collector = collectors[packageName] ?: return
        val queued = publishQueued.computeIfAbsent(packageName) { AtomicBoolean(false) }
        if (!queued.compareAndSet(false, true)) return

        runCatching {
            publisher.schedule({
                queued.set(false)
                if (collector.isEmpty()) return@schedule

                val snapshot = collector.snapshot()
                val json = runCatching {
                    HookReportCodec.encode(snapshot, packageName)
                }.getOrElse {
                    log(Log.WARN, TAG, "report encode failed: ${it.javaClass.simpleName}")
                    return@schedule
                }

                log(Log.INFO, TAG, "observation-report: " + json)
                if (ReportRelay.publish(json)) {
                    collector.acknowledge(snapshot)
                    relayFailures.remove(packageName)
                } else {
                    val failures = relayFailures.computeIfAbsent(packageName) { AtomicInteger(0) }
                        .incrementAndGet()
                    log(Log.WARN, TAG, "report relay not acknowledged; pending=$failures")
                    // Bounded retries avoid a hot loop when the module app/provider
                    // is unavailable. Pending data remains in the collector.
                    if (failures < MAX_RELAY_RETRIES) {
                        schedulePublish(packageName, RELAY_RETRY_MS)
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }.onFailure {
            queued.set(false)
            log(Log.WARN, TAG, "report scheduling failed: ${it.javaClass.simpleName}")
        }
    }

    private fun hookKey(packageName: String, profileId: String) = "$packageName:$profileId"

    private companion object {
        const val TAG = "GPTDynamicHook"
        const val REPORT_DEBOUNCE_MS = 2000L
        const val RELAY_RETRY_MS = 5000L
        const val MAX_RELAY_RETRIES = 3
    }
}
