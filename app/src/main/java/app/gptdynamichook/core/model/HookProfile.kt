package app.gptdynamichook.core.model

/**
 * A declarative hook target. Profiles are data only; execution requires
 * explicit enablement and validation by the runtime policy layer.
 */
data class HookProfile(
    val id: String,
    val targetPackage: String,
    val className: String,
    val methodName: String,
    val parameterTypeNames: List<String>,
    val enabled: Boolean = false
)
