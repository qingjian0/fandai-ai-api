package app.gptdynamichook.core.model

/**
 * Collision-free identity of the executable target and its installation pins.
 * A pin change must invalidate an already-installed hook so the runtime cannot
 * keep executing under a stale version/signer constraint.
 */
object HookProfileSignature {
    fun of(profile: HookProfile): String = buildString {
        appendField(profile.targetPackage)
        appendField(profile.className)
        appendField(profile.methodName)
        append(profile.parameterTypeNames.size).append(':')
        profile.parameterTypeNames.forEach { appendField(it) }
        appendField(profile.targetVersionCode?.toString().orEmpty())
        appendField(profile.targetSignerSha256.orEmpty())
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value)
    }
}
