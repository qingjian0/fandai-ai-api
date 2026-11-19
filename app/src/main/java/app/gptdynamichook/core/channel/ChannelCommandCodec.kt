package app.gptdynamichook.core.channel

import org.json.JSONObject

/** Parses an operator command file. Unknown fields are ignored; a broken file yields null. */
object ChannelCommandCodec {
    fun decode(raw: String?): ReflectiveChannel.Command? {
        if (raw.isNullOrBlank() || raw.length > 64 * 1024) return null
        return runCatching {
            val o = JSONObject(raw)
            val type = o.optString("type", "")
            if (type.isBlank()) return@runCatching null
            val args = o.optJSONArray("args")?.let { a ->
                buildList { for (i in 0 until minOf(a.length(), 8)) add(a.optString(i)) }
            }.orEmpty()
            ReflectiveChannel.Command(
                type = type,
                target = o.optString("target", "").ifBlank { null },
                path = o.optString("path", "").ifBlank { null },
                name = o.optString("name", "").ifBlank { null },
                value = if (o.has("value")) o.optString("value") else null,
                args = args,
                depth = o.optInt("depth", 2).coerceIn(0, 6),
                maxOps = o.optInt("maxOps", 400).coerceIn(1, 2000),
                emitValues = o.optBoolean("emitValues", false)
            )
        }.getOrNull()
    }
}
