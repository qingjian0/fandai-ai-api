package app.gptdynamichook.core.report

import app.gptdynamichook.core.model.HookProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= HookReportCodec.MAX_ENCODED_LENGTH)
        val root = JSONObject(json)
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("com.openai.chatgpt", root.getString("targetPackage"))
        assertTrue(root.getJSONArray("events").length() <= 32)
    }


    @Test
    fun acknowledgedSnapshotPreservesEventsRecordedAfterSnapshot() {
        val collector = HookEventCollector(capacity = 8)
        collector.record(profile, 1L, "ok", null)
        val captured = collector.snapshot()

        collector.record(profile, 2L, "ok", null)
        collector.acknowledge(captured)

        val remaining = collector.snapshot()
        assertEquals(1, remaining.events.size)
        assertEquals(2L, remaining.events.single().elapsedMs)
        assertEquals(1L, remaining.counts["p1|ok"])
        assertFalse(collector.isEmpty())
    }

    @Test
    fun failedTransportCanRetrySameSnapshotWithoutLosingData() {
        val collector = HookEventCollector()
        repeat(4) { collector.record(profile, it.toLong(), "ok", null) }

        val firstAttempt = collector.snapshot()
        // No acknowledgement represents an encode/transport failure.
        val retry = collector.snapshot()

        assertEquals(firstAttempt, retry)
        assertEquals(4, retry.events.size)
        assertEquals(4L, retry.counts["p1|ok"])
    }

    @Test
    fun snapshotAndAcknowledgePreserveEventsRecordedAfterSnapshot() {
        val collector = HookEventCollector(capacity = 8)
        collector.record(profile, 1L, "ok", null)
        val first = collector.snapshot()
        collector.record(profile, 2L, "throw:IOException", null)

        collector.acknowledge(first)
        val remaining = collector.snapshot()
        assertEquals(1, remaining.events.size)
        assertEquals("throw:IOException", remaining.events.single().outcome)
        assertEquals(mapOf("p1|throw:IOException" to 1L), remaining.counts)
        assertTrue(!collector.isEmpty())
    }

    @Test
    fun failedDeliveryCanRetryTheSameSnapshotWithoutDataLoss() {
        val collector = HookEventCollector(capacity = 8)
        repeat(3) { collector.record(profile, it.toLong(), "ok", null) }

        val firstAttempt = collector.snapshot()
        // Simulate encode/transport failure: no acknowledgement is issued.
        val retry = collector.snapshot()
        assertEquals(firstAttempt, retry)
        assertEquals(3L, retry.counts["p1|ok"])
        assertEquals(3, retry.events.size)
        assertTrue(!collector.isEmpty())

        collector.acknowledge(retry)
        assertTrue(collector.isEmpty())
    }

    @Test
    fun acknowledgeSubtractsOnlyCapturedCounterDelta() {
        val collector = HookEventCollector(capacity = 8)
        collector.record(profile, 1L, "ok", null)
        val first = collector.snapshot()
        collector.record(profile, 2L, "ok", null)

        collector.acknowledge(first)
        val remaining = collector.snapshot()
        assertEquals(1, remaining.events.size)
        assertEquals(1L, remaining.counts["p1|ok"])
    }

    @Test
    fun encodedReportRespectsUtf8ByteBudgetForMultibyteProfileIds() {
        val collector = HookEventCollector(capacity = 2)
        val largeProfile = profile.copy(id = "界".repeat(12_000))
        collector.record(largeProfile, 1L, "ok", null)

        val json = HookReportCodec.encode(collector.snapshot(), "com.openai.chatgpt")
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= HookReportCodec.MAX_ENCODED_LENGTH)
        assertTrue(JSONObject(json).optBoolean("truncated"))
    }

    @Test
    fun emptySnapshotEncodesEmptyEvents() {
        val json = HookReportCodec.encode(HookEventCollector().drain(), "pkg")
        assertEquals(0, JSONObject(json).getJSONArray("events").length())
    }
}
