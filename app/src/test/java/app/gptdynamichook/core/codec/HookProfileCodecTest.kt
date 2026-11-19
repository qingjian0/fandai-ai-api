package app.gptdynamichook.core.codec

import app.gptdynamichook.core.model.HookProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class HookProfileCodecTest {
    @Test
    fun roundTripsProfiles() {
        val profiles = listOf(
            HookProfile(
                id = "chat-send",
                targetPackage = "com.openai.chatgpt",
                className = "com.example.Client",
                methodName = "send",
                parameterTypeNames = listOf("java.lang.String", "int"),
                enabled = true
            )
        )
        assertEquals(profiles, HookProfileCodec.decode(HookProfileCodec.encode(profiles)))
    }

    @Test
    fun malformedInputFailsClosed() {
        assertEquals(emptyList<HookProfile>(), HookProfileCodec.decode("not-json"))
    }

    @Test
    fun missingConfigurationIsEmpty() {
        assertEquals(emptyList<HookProfile>(), HookProfileCodec.decode(null))
    }
}
