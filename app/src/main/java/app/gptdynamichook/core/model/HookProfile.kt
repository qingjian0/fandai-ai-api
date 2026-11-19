package app.gptdynamichook.core.model

/**
 * A declarative hook target. Profiles are data only; execution requires
 * explicit enablement and validation by the runtime policy layer.
 *
 * [targetVersionCode] and [targetSignerSha256] are optional pinning fields.
 * `null` means "no constraint"; a non-null value must match the installed
 * package before a hook is installed. Pinning matters because a target app
 * update can move a method to a different signature, and because the package
 * name alone cannot distinguish a genuine install from a cloned/re-packaged
 * app that shares the name under a different signing identity.
 */
data class HookProfile(
    val id: String,
    val targetPackage: String,
    val className: String,
    val methodName: String,
    val parameterTypeNames: List<String>,
    val enabled: Boolean = false,
    val targetVersionCode: Long? = null,
    val targetSignerSha256: String? = null
)
