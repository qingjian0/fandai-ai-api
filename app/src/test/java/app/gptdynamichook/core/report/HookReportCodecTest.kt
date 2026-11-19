package app.gptdynamichook.core.report

import app.gptdynamichook.core.model.HookProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookReportCodecTest {
    private val profile = HookProfile(
        id = "p1",
        targetPackage = "com.openai.chatgpt",
        className = "com.openai.Foo",
        methodName = "bar",
        parameterTypeNames = listOf("java.lang.String"),
        enabled = true
    )

    @Test
    fun collectorIsBoundedAndDrains() {
        val collector = HookEventCollector(now = { 0L }, capacity = 3)
        repeat(10) { collector.record(profile, 5L, "ok", null) }
        val snapshot = collector.drain()
        assertEquals(3, snapshot.events.size)
        assertEquals(10L, snapshot.counts["p1|ok"])
        assertTrue(collector.isEmpty())
    }

    @Test
    fun reportContainsNoRawValuesAndStaysBounded() {
        val collector = HookEventCollector()
        repeat(200) {
            collector.record(
                profile, 1L, "ok",
                HookResultParser.HookResult(shape = "okhttp3.Request", urlHost = "example.com")
            )
        }
        val json = HookReportCodec.encode(collector.drain(), "com.openai.chatgpt")
        assertTrue(json.length <= HookReportCodec.MAX_ENCODED_LENGTH)
        val root = JSONObject(json)
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("com.openai.chatgpt", root.getString("targetPackage"))
        assertTrue(root.getJSONArray("events").length() <= 32)
    }

    @Test
    fun emptySnapshotEncodesEmptyEvents() {
        val json = HookReportCodec.encode(HookEventCollector().drain(), "pkg")
        assertEquals(0, JSONObject(json).getJSONArray("events").length())
    }
}
