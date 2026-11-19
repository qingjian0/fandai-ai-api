package app.gptdynamichook.hook

import android.util.Log
import app.gptdynamichook.core.rules.HookRule
import app.gptdynamichook.core.rules.HookRuleCodec
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Watches the on-device rule file and re-applies it when it changes.
 *
 * Polling by mtime+size instead of FileObserver: FileObserver on a directory
 * under /data/data is unreliable across OEM kernels (MIUI in particular batches
 * or drops events), while a stat every [POLL_MS] is cheap and always converges.
 *
 * The file lives in the target app's own files directory, so this runs with the
 * target's own uid and needs no IPC. An operator with root rewrites the file.
 */
class RuleWatcher(
    private val packageName: String,
    private val rulesFile: File,
    private val reportFile: File,
    private val classLoader: ClassLoader,
    private val log: (String) -> Unit,
    private val apply: (List<HookRule>) -> Unit
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gpt-rule-watch").apply { isDaemon = true }
    }

    @Volatile private var lastStamp: String = ""
    @Volatile private var lastRuleCount: Int = -1

    fun start() {
        executor.scheduleWithFixedDelay({
            runCatching { tick() }
                .onFailure { Log.w(TAG, "rule tick failed: ${it.javaClass.simpleName}") }
        }, 0, POLL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val stamp = stampOf(rulesFile)
        if (stamp == lastStamp) return
        lastStamp = stamp

        if (!rulesFile.isFile) {
            if (lastRuleCount != 0) {
                log("[rules] file absent; clearing all rule hooks")
                apply(emptyList())
                lastRuleCount = 0
            }
            return
        }
        val raw = runCatching { rulesFile.readText() }.getOrNull()
        if (raw == null) {
            log("[rules] unreadable; keeping previous rule set")
            return
        }
        val rules = HookRuleCodec.decode(raw)
        if (rules.isEmpty() && raw.isNotBlank()) {
            log("[rules] no valid rule parsed (${raw.length} chars); keeping previous set")
            return
        }
        log("[rules] loaded=${rules.size} enabled=${rules.count { it.enabled }}")
        apply(rules)
        lastRuleCount = rules.size
    }

    private fun stampOf(file: File): String =
        if (!file.isFile) "absent" else "${file.length()}:${file.lastModified()}"

    fun stop() {
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "GPTDynamicHook"
        private const val POLL_MS = 2000L

        /** Rules and report live in the target app's own files dir. */
        fun rulesFileFor(packageName: String): File =
            File("/data/data/$packageName/files/gpt_rules.json")

        fun reportFileFor(packageName: String): File =
            File("/data/data/$packageName/files/gpt_report.jsonl")
    }
}
