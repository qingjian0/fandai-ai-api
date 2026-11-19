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
    @Test
    fun oversizedPayloadFailsClosedBeforeJsonParsing() {
        assertEquals(emptyList<HookProfile>(), HookProfileCodec.decode(" ".repeat(1_048_577)))
    }

    @Test
    fun excessiveProfileCountFailsClosed() {
        val profiles = (0..512).map { index ->
            HookProfile(
                id = "profile-$index",
                targetPackage = "com.example.client",
                className = "com.example.Client",
                methodName = "send",
                parameterTypeNames = emptyList()
            )
        }
        assertEquals(emptyList<HookProfile>(), HookProfileCodec.decode(HookProfileCodec.encode(profiles)))
    }

}
