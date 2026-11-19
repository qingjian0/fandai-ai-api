package app.gptdynamichook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HookProfileSignatureTest {
    private fun profile(
        className: String = "com.example.Client",
        methodName: String = "send",
        parameters: List<String> = listOf("java.lang.String")
    ) = HookProfile(
        id = "stable-profile-id",
        targetPackage = "com.openai.chatgpt",
        className = className,
        methodName = methodName,
        parameterTypeNames = parameters,
        enabled = true
    )

    @Test
    fun sameTargetProducesSameSignature() {
        assertEquals(HookProfileSignature.of(profile()), HookProfileSignature.of(profile()))
    }

    @Test
    fun changedClassMethodOrParametersProduceDifferentSignature() {
        val original = HookProfileSignature.of(profile())
        assertNotEquals(original, HookProfileSignature.of(profile(className = "com.example.Other")))
        assertNotEquals(original, HookProfileSignature.of(profile(methodName = "submit")))
        assertNotEquals(original, HookProfileSignature.of(profile(parameters = listOf("int"))))
    }
}
