package app.gptdynamichook.core.model

/**
 * Identity of the executable target behind a Profile ID.
 * A changed signature requires the old runtime hook to be removed and resolved again.
 */
object HookProfileSignature {
    fun of(profile: HookProfile): String =
        listOf(
            profile.className,
            profile.methodName,
            profile.parameterTypeNames.joinToString(",")
        ).joinToString("#")
}
