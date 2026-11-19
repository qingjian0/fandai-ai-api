package app.gptdynamichook.core.validation

import app.gptdynamichook.core.model.HookProfile

object HookProfileValidator {
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
    private val packagePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val classPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$.]*$")
    private val memberPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val parameterTypePattern = Regex(
        "^(boolean|byte|char|short|int|long|float|double|" +
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*)" +
            "(\\[\\])*$"
    )

    fun validate(profile: HookProfile): List<String> = buildList {
        if (!idPattern.matches(profile.id)) add("invalid_profile_id")
        if (!packagePattern.matches(profile.targetPackage)) add("invalid_target_package")
        if (!classPattern.matches(profile.className)) add("invalid_class_name")
        if (!memberPattern.matches(profile.methodName)) add("invalid_method_name")
        if (profile.parameterTypeNames.size > 32) add("too_many_parameters")
        if (profile.parameterTypeNames.any {
                it.isBlank() || it.length > 240 || it == "void" || it.startsWith("void[" ) ||
                    !parameterTypePattern.matches(it)
            }) {
            add("invalid_parameter_type")
        }
    }

    fun matchesTarget(profile: HookProfile, packageName: String): Boolean =
        profile.enabled && profile.targetPackage == packageName && validate(profile).isEmpty()
}
