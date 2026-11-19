package app.gptdynamichook.core.rules

/**
 * A live-editable hook rule.
 *
 * Unlike [app.gptdynamichook.core.model.HookProfile], a rule may match many
 * classes and methods by regex and may capture a stack trace. That is what makes
 * an iterative sweep possible: widen the match, read the stack, narrow it.
 *
 * Rules are data. They live in the target app's own files directory so the
 * runtime can read them without any cross-process IPC, and an operator with root
 * can rewrite them at any time.
 */
data class HookRule(
    val id: String,
    val classNames: List<String> = emptyList(),
    val classRegex: String? = null,
    val methodRegex: String = ".*",
    val enabled: Boolean = true,
    /** Frames captured per hit; 0 disables stack capture. */
    val captureStack: Int = 0,
    val captureElapsed: Boolean = true,
    /** Number of parameters recorded by type only; values are never read. */
    val captureArgTypes: Int = 0,
    val sampleEvery: Int = 1,
    val maxHooks: Int = 64
)
