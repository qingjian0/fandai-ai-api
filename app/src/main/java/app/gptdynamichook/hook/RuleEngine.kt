package app.gptdynamichook.hook

import android.util.Log
import app.gptdynamichook.core.report.ResultShape
import app.gptdynamichook.core.rules.HookRule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Applies [HookRule]s and records what the hooks actually saw.
 *
 * This is the "sweep" half of the loop: widen a rule, let it hit, read the stack
 * trace, narrow the rule. Every observation is written as one JSON line so an
 * operator can tail the file instead of pulling logcat.
 *
 * Nothing here reads argument or return *values*. Argument types, durations,
 * call counts and stack frames are metadata; values would be user content.
 */
class RuleEngine(
    private val module: XposedModule,
    private val reportFile: File,
    private val log: (String) -> Unit
) {
    private class Installed(val key: String, val handle: XposedInterface.HookHandle)

    private val installed = ConcurrentHashMap<String, Installed>()
    private val hits = AtomicLong(0)
    private val samples = ConcurrentHashMap<String, Long>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gpt-rule-report").apply { isDaemon = true }
    }

    /** Installs every rule that is not already installed; unhooks removed ones. */
    fun apply(
        packageName: String,
        classLoader: ClassLoader,
        rules: List<HookRule>
    ) {
        val desired = rules.filter { it.enabled }
        val wanted = HashSet<String>()

        desired.forEach { rule ->
            val classNames = resolveClassNames(rule, classLoader)
            if (classNames.isEmpty()) {
                log("[rule:${rule.id}] no class matched")
                return@forEach
            }
            val classRegex = rule.classRegex?.let { runCatching { Regex(it) }.getOrNull() }
            val methodRegex = runCatching { Regex(rule.methodRegex) }.getOrNull()
            if (methodRegex == null) {
                log("[rule:${rule.id}] invalid methodRegex")
                return@forEach
            }

            var installedForRule = 0
            for (name in classNames) {
                if (installedForRule >= rule.maxHooks) break
                val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                    ?: continue
                for (method in clazz.declaredMethods) {
                    if (installedForRule >= rule.maxHooks) break
                    if (!methodRegex.matches(method.name)) continue
                    val key = "$packageName:${rule.id}:${name}#${method.name}${descriptorOf(method)}"
                    wanted.add(key)
                    if (installed.containsKey(key)) {
                        installedForRule++
                        continue
                    }
                    if (install(key, rule, method)) installedForRule++
                }
            }
            log("[rule:${rule.id}] candidates=${classNames.size} installed=$installedForRule")
        }

        installed.entries
            .filter { (key, _) -> key.startsWith("$packageName:") && key !in wanted }
            .forEach { entry ->
                runCatching { entry.value.handle.unhook() }
                    .onSuccess { installed.remove(entry.key) }
                    .onFailure { log("[rule] unhook failed: ${entry.key}") }
            }
    }

    private fun resolveClassNames(rule: HookRule, classLoader: ClassLoader): List<String> {
        if (rule.classNames.isNotEmpty()) return rule.classNames
        val regex = rule.classRegex?.let { runCatching { Regex(it) }.getOrNull() } ?: return emptyList()
        return ClassEnumerator.names(classLoader).filter { regex.matches(it) }
    }

    private fun install(key: String, rule: HookRule, method: Method): Boolean {
        val local = key.substringAfterLast(':')
        return runCatching {
            val handle = module.hook(method)
                .setId(key)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val started = System.nanoTime()
                    try {
                        val rewritten = applyRewrite(rule, chain, method, local)
                        val result = if (rewritten.skipOriginal) {
                            rewritten.value
                        } else if (rewritten.args != null) {
                            chain.proceed(rewritten.args)
                        } else {
                            chain.proceed()
                        }
                        val finalResult = if (rewritten.replaceResult) rewritten.value else result
                        record(rule, local, started, null, finalResult)
                        finalResult
                    } catch (t: Throwable) {
                        record(rule, local, started, t.javaClass.name, null)
                        throw t
                    }
                }
            installed[key] = Installed(key, handle)
            true
        }.getOrElse {
            log("[rule:${rule.id}] install failed: $local (${it.javaClass.simpleName})")
            false
        }
    }

    private class RewriteOutcome(
        val skipOriginal: Boolean,
        val replaceResult: Boolean,
        val value: Any?,
        val args: Array<Any?>? = null
    )

    /**
     * Applies an operator-supplied rewrite.
     *
     * Literals are never derived from app data, and [HookRule.rewriteDryRun] logs
     * the decision without touching the call, so a rewrite can be verified before
     * it is allowed to take effect.
     */
    private fun applyRewrite(
        rule: HookRule,
        chain: XposedInterface.Chain,
        method: Method,
        target: String
    ): RewriteOutcome {
        if (rule.rewriteArgs.isEmpty() && rule.rewriteResult == null && !rule.rewriteSkip) {
            return RewriteOutcome(false, false, null)
        }
        val parameterTypes = method.parameterTypes
        // The chain exposes an immutable arg list; a rewrite must pass a new
        // array to proceed(args) rather than mutate the existing one.
        var newArgs: Array<Any?>? = null
        if (rule.rewriteArgs.isNotEmpty()) {
            val base = chain.getArgs()
            val copy = arrayOfNulls<Any?>(base.size)
            for (i in base.indices) copy[i] = base[i]
            rule.rewriteArgs.forEach { (index, literal) ->
                if (index >= parameterTypes.size) return@forEach
                val converted = literalOf(literal, parameterTypes[index])
                if (rule.rewriteDryRun) {
                    log("[rewrite:dry] $target arg$index <- $literal")
                } else {
                    copy[index] = converted
                    newArgs = copy
                    log("[rewrite] $target arg$index <- $literal")
                }
            }
        }
        val resultLiteral = rule.rewriteResult
        if (resultLiteral == null) {
            return RewriteOutcome(rule.rewriteSkip, false, null, newArgs)
        }
        val value = literalOf(resultLiteral, method.returnType)
        if (rule.rewriteDryRun) {
            log("[rewrite:dry] $target result <- $resultLiteral")
            return RewriteOutcome(false, false, null, newArgs)
        }
        log("[rewrite] $target result <- $resultLiteral")
        return RewriteOutcome(rule.rewriteSkip, true, value, newArgs)
    }

    /** Operator literals only: null, boolean, integer, long, or string. */
    private fun literalOf(literal: String, type: Class<*>): Any? = when {
        literal == "null" -> null
        literal == "true" -> java.lang.Boolean.TRUE
        literal == "false" -> java.lang.Boolean.FALSE
        type == java.lang.String::class.java || type == CharSequence::class.java -> literal
        type == Integer.TYPE || type == java.lang.Integer::class.java -> literal.toIntOrNull()
        type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> literal.toLongOrNull()
        type == java.lang.Boolean.TYPE -> literal.toBoolean()
        type == java.lang.Float.TYPE -> literal.toFloatOrNull()
        type == java.lang.Double.TYPE -> literal.toDoubleOrNull()
        else -> null
    }

    private fun record(
        rule: HookRule,
        target: String,
        startedNanos: Long,
        throwable: String?,
        result: Any?
    ) {
        val n = hits.incrementAndGet()
        val sample = samples.merge(rule.id, 1L) { a, _ -> a + 1 } ?: 1L
        if (sample % rule.sampleEvery.toLong() != 0L) return

        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
        val builder = StringBuilder(160)
        builder.append("{\"seq\":").append(n)
            .append(",\"rule\":\"").append(escape(rule.id)).append('"')
            .append(",\"target\":\"").append(escape(target)).append('"')
        if (rule.captureElapsed) builder.append(",\"ms\":").append(elapsedMs)
        if (throwable != null) builder.append(",\"throwable\":\"").append(escape(throwable)).append('"')
        if (rule.captureArgTypes > 0) {
            val inside = target.substringAfter('(', "").substringBefore(')')
            val argc = if (inside.isBlank()) 0 else inside.count { it == ',' } + 1
            builder.append(",\"argCount\":").append(argc)
        }
        if (rule.captureResult) {
            val shape = runCatching { ResultShape.describe(result) }
                .getOrDefault("unavailable")
            builder.append(",\"").append("resultShape\":\"").append(escape(shape)).append('"')
        }
        if (rule.captureStack > 0) {
            builder.append(",\"stack\":[")
            var emitted = 0
            for (frame in Throwable().stackTrace) {
                if (emitted >= rule.captureStack) break
                val cn = frame.className
                if (cn.startsWith("app.gptdynamichook") || cn.startsWith("io.github.libxposed") ||
                    cn.startsWith("java.lang.reflect") || cn.startsWith("android.os") ||
                    cn.startsWith("com.android.internal.os")
                ) continue
                if (emitted > 0) builder.append(',')
                builder.append('"').append(escape(cn)).append('#').append(escape(frame.methodName)).append('"')
                emitted++
            }
            builder.append(']')
        }
        builder.append('}')

        val line = builder.toString()
        log("[hit:${rule.id}] $target")
        write(line)
    }

    private fun write(line: String) {
        runCatching {
            writer.execute {
                runCatching { appendLine(reportFile, line) }
                    .onFailure { Log.w(TAG, "report write failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        // Cap the file so a runaway sweep cannot fill the data partition.
        if (file.isFile && file.length() > MAX_REPORT_BYTES) {
            file.renameTo(File(file.parentFile, file.name + ".1"))
        }
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    fun resetCounters() {
        hits.set(0)
        samples.clear()
    }

    fun installedCount(): Int = installed.size

    private fun descriptorOf(method: Method): String =
        method.parameterTypes.joinToString(",", "(", ")") { it.name }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    companion object {
        private const val TAG = "GPTDynamicHook"
        private const val MAX_REPORT_BYTES = 8L * 1024 * 1024
    }
}
