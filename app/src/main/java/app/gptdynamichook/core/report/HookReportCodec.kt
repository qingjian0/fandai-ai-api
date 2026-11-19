package app.gptdynamichook.core.report

import org.json.JSONArray
import org.json.JSONObject

/** Serializes a drained snapshot into the bounded key-only report schema. */
object HookReportCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_ENCODED_LENGTH = HookEventCollector.maxEncodedBytes

    fun encode(snapshot: HookEventCollector.Snapshot, targetPackage: String): String {
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("targetPackage", targetPackage)

        val counts = JSONObject()
        snapshot.counts.entries.take(64).forEach { (key, value) ->
            val (id, outcome) = key.split('|', limit = 2).let {
                it.first() to (it.getOrNull(1) ?: "unknown")
            }
            counts.put("$id|$outcome", value)
        }
        root.put("counts", counts)

        val events = JSONArray()
        snapshot.events.takeLast(MAX_EVENTS).forEach { event ->
            val item = JSONObject()
                .put("seq", event.seq)
                .put("id", event.profileId)
                .put("outcome", event.outcome)
                .put("elapsedMs", event.elapsedMs)
            event.result?.let { result ->
                val keys = JSONObject()
                result.shape?.let { keys.put("shape", it) }
                result.bodyKind?.let { keys.put("bodyKind", it) }
                result.byteLength?.let { keys.put("byteLength", it) }
                result.elementCount?.let { keys.put("elementCount", it) }
                result.status?.let { keys.put("status", it) }
                result.urlHost?.let { keys.put("urlHost", it) }
                if (result.fieldNames.isNotEmpty()) {
                    keys.put("fieldNames", JSONArray(result.fieldNames.take(MAX_FIELDS)))
                }
                item.put("result", keys)
            }
            events.put(item)
        }
        root.put("events", events)

        var encoded = root.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_LENGTH) {
            root.remove("events")
            encoded = root.toString()
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_LENGTH) {
            return JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("targetPackage", targetPackage)
                .put("truncated", true)
                .toString()
        }
        return encoded
    }

    private const val MAX_EVENTS = 32
    private const val MAX_FIELDS = HookEventCollector.MAX_FIELDS
}
