package app.gptdynamichook.core.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec for the on-device rule file. Fails closed: any structural problem
 * yields an empty rule set rather than a partially applied one, because a
 * half-parsed rule list would install hooks the operator never asked for.
 */
object HookRuleCodec {
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun decode(raw: String?): List<HookRule> {
        if (raw.isNullOrBlank() || raw.length > MAX_ENCODED_LENGTH) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val array = when {
                root.has("rules") -> root.getJSONArray("rules")
                raw.trimStart().startsWith("[") -> JSONArray(raw)
                else -> JSONArray()
            }
            require(array.length() <= MAX_RULES) { "too_many_rules" }
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (!idPattern.matches(id)) continue
                    val names = item.optJSONArray("classNames")?.let { names ->
                        buildList {
                            for (n in 0 until minOf(names.length(), MAX_CLASS_NAMES)) {
                                val value = names.optString(n, "")
                                if (value.isNotBlank() && value.length <= 240) add(value)
                            }
                        }
                    }.orEmpty()
                    val regex = item.optString("classRegex", "").takeIf { it.isNotBlank() }
                    if (names.isEmpty() && regex == null) continue
                    add(
                        HookRule(
                            id = id,
                            classNames = names,
                            classRegex = regex,
                            methodRegex = item.optString("methodRegex", ".*").ifBlank { ".*" },
                            enabled = item.optBoolean("enabled", true),
                            captureStack = item.optInt("captureStack", 0).coerceIn(0, MAX_STACK),
                            captureElapsed = item.optBoolean("captureElapsed", true),
                            captureArgTypes = item.optInt("captureArgTypes", 0).coerceIn(0, MAX_ARGS),
                            sampleEvery = item.optInt("sampleEvery", 1).coerceIn(1, 10_000),
                            maxHooks = item.optInt("maxHooks", 64).coerceIn(1, MAX_HOOKS_PER_RULE)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    const val MAX_RULES = 32
    const val MAX_CLASS_NAMES = 512
    const val MAX_STACK = 16
    const val MAX_ARGS = 8
    const val MAX_HOOKS_PER_RULE = 400
    const val MAX_ENCODED_LENGTH = 512 * 1024
}
