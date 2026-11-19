package app.gptdynamichook.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteRuleTest {
    @Test
    fun decodesArgumentAndResultRewrite() {
        val raw = """
            {"rules":[{
              "id":"ctrl",
              "classNames":["a930"],
              "methodRegex":"s0",
              "rewrite":{"args":{"0":"injected","1":"42"},"result":"null","skip":true,"dryRun":true}
            }]}
        """.trimIndent()
        val rule = HookRuleCodec.decode(raw).single()
        assertEquals(mapOf(0 to "injected", 1 to "42"), rule.rewriteArgs)
        assertEquals("null", rule.rewriteResult)
        assertTrue(rule.rewriteSkip)
        assertTrue(rule.rewriteDryRun)
    }

    @Test
    fun ruleWithoutRewriteStaysObservationOnly() {
        val rule = HookRuleCodec.decode("""{"rules":[{"id":"obs","classNames":["a.B"]}]}""").single()
        assertTrue(rule.rewriteArgs.isEmpty())
        assertEquals(null, rule.rewriteResult)
        assertFalse(rule.rewriteSkip)
        assertFalse(rule.rewriteDryRun)
    }

    @Test
    fun oversizedLiteralIsDropped() {
        val big = "x".repeat(HookRuleCodec.MAX_LITERAL_LENGTH + 1)
        val raw = """{"rules":[{"id":"c","classNames":["a.B"],"rewrite":{"args":{"0":"$big"}}}]}"""
        assertTrue(HookRuleCodec.decode(raw).single().rewriteArgs.isEmpty())
    }

    @Test
    fun negativeAndHugeArgIndexesAreIgnored() {
        val raw = """{"rules":[{"id":"c","classNames":["a.B"],"rewrite":{"args":{"-1":"x","99":"y","2":"ok"}}}]}"""
        assertEquals(mapOf(2 to "ok"), HookRuleCodec.decode(raw).single().rewriteArgs)
    }
}
