package app.gptdynamichook.core.validation

import app.gptdynamichook.core.model.HookProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookProfileValidatorTest {
    @Test
    fun acceptsWellFormedDisabledProfile() {
        val errors = HookProfileValidator.validate(
            HookProfile(
                id = "openai-chat-send",
                targetPackage = "com.openai.chatgpt",
                className = "com.example.Client",
                methodName = "execute",
                parameterTypeNames = listOf("java.lang.String")
            )
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun acceptsPrimitiveAndReferenceArrayParameterTypes() {
        val profile = HookProfile(
            id = "arrays",
            targetPackage = "com.openai.chatgpt",
            className = "com.example.Client",
            methodName = "execute",
            parameterTypeNames = listOf("int[]", "java.lang.String[][]", "com.example.Outer$Inner")
        )
        assertTrue(HookProfileValidator.validate(profile).isEmpty())
    }

    @Test
    fun rejectsMalformedPackageAndMemberNames() {
        val errors = HookProfileValidator.validate(
            HookProfile(
                id = "",
                targetPackage = "com..bad",
                className = "not a class",
                methodName = "bad-name",
                parameterTypeNames = listOf("")
            )
        )
        assertEquals(
            setOf("invalid_profile_id", "invalid_target_package", "invalid_class_name",
                "invalid_method_name", "invalid_parameter_type"),
            errors.toSet()
        )
    }

    @Test
    fun rejectsMalformedParameterTypeSyntax() {
        val invalidTypes = listOf("void[]", "java..lang.String", "int[ ]", "java.lang.String;")
        val profile = HookProfile(
            id = "invalid-types",
            targetPackage = "com.openai.chatgpt",
            className = "com.example.Client",
            methodName = "execute",
            parameterTypeNames = invalidTypes
        )
        assertTrue(HookProfileValidator.validate(profile).contains("invalid_parameter_type"))
    }

    @Test
    fun rejectsProfileIdsThatCanBreakRuntimeHookKeys() {
        val profile = HookProfile(
            id = "profile:with:separators",
            targetPackage = "com.openai.chatgpt",
            className = "com.example.Client",
            methodName = "execute",
            parameterTypeNames = emptyList()
        )
        assertTrue(HookProfileValidator.validate(profile).contains("invalid_profile_id"))
    }

    @Test
    fun onlyEnabledExactPackageProfilesMatch() {
        val profile = HookProfile(
            id = "profile",
            targetPackage = "com.openai.chatgpt",
            className = "com.example.Client",
            methodName = "execute",
            parameterTypeNames = emptyList(),
            enabled = true
        )
        assertTrue(HookProfileValidator.matchesTarget(profile, "com.openai.chatgpt"))
        assertEquals(false, HookProfileValidator.matchesTarget(profile, "com.other.app"))
    }
}
