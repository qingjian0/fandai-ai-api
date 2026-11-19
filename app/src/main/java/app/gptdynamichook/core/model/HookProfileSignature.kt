package app.gptdynamichook.core.model

/**
 * Collision-free identity of the executable target behind a Profile ID.
 * Length-prefixing keeps arbitrary parameter type text from colliding with delimiters.
 */
object HookProfileSignature {
    fun of(profile: HookProfile): String = buildString {
        appendField(profile.className)
        appendField(profile.methodName)
        append(profile.parameterTypeNames.size).append(':')
        profile.parameterTypeNames.forEach(::appendField)
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value)
    }
}
