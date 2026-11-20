package app.gptdynamichook.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookRuleCodecTest {
    @Test
    fun decodesRegexSweepRule() {
        val raw = """
            {"rules":[{
              "id":"sweep-net",
              "classRegex":"com\\.openai\\.network\\..*",
              "methodRegex":".*",
              "captureStack":8,
              "sampleEvery":10,
              "maxHooks":200
            }]}
        """.trimIndent()
        val rules = HookRuleCodec.decode(raw)
        assertEquals(1, rules.size)
        val rule = rules.single()
        assertEquals("sweep-net", rule.id)
        assertEquals("com\\.openai\\.network\\..*", rule.classRegex)
        assertEquals(8, rule.captureStack)
        assertEquals(10, rule.sampleEvery)
        assertEquals(200, rule.maxHooks)
        assertTrue(rule.enabled)
    }

    @Test
    fun decodesExplicitClassNameList() {
        val raw = """
            {"rules":[{"id":"r1","classNames":["com.openai.network.ApiServiceError"],"methodRegex":"a"}]}
        """.trimIndent()
        val rule = HookRuleCodec.decode(raw).single()
        assertEquals(listOf("com.openai.network.ApiServiceError"), rule.classNames)
        assertEquals("a", rule.methodRegex)
    }

    @Test
    fun acceptsBareArrayForm() {
        val raw = """[{"id":"r2","classNames":["com.a.B"]}]"""
        assertEquals(1, HookRuleCodec.decode(raw).size)
    }

    @Test
    fun rejectsRulesWithoutAnyMatcher() {
        assertEquals(0, HookRuleCodec.decode("""{"rules":[{"id":"r3"}]}""").size)
    }

    @Test
    fun rejectsInvalidId() {
        assertEquals(0, HookRuleCodec.decode("""{"rules":[{"id":"bad id!","classNames":["a.B"]}]}""").size)
    }

    @Test
    fun clampsUnsafeValues() {
        val raw = """
            {"rules":[{"id":"r4","classNames":["a.B"],"captureStack":9999,"sampleEvery":0,"maxHooks":999999}]}
        """.trimIndent()
        val rule = HookRuleCodec.decode(raw).single()
        assertEquals(HookRuleCodec.MAX_STACK, rule.captureStack)
        assertEquals(1, rule.sampleEvery)
        assertEquals(HookRuleCodec.MAX_HOOKS_PER_RULE, rule.maxHooks)
    }

    @Test
    fun malformedInputFailsClosed() {
        assertEquals(emptyList<HookRule>(), HookRuleCodec.decode("not json"))
        assertEquals(emptyList<HookRule>(), HookRuleCodec.decode(null))
        assertEquals(
            emptyList<HookRule>(),
            HookRuleCodec.decode("x".repeat(HookRuleCodec.MAX_ENCODED_LENGTH + 1))
        )
    }

    @Test
    fun tooManyRulesFailsClosed() {
        val rules = (1..(HookRuleCodec.MAX_RULES + 1))
            .joinToString(",") { """{"id":"r$it","classNames":["a.B"]}""" }
        assertEquals(emptyList<HookRule>(), HookRuleCodec.decode("""{"rules":[$rules]}"""))
    }
}
