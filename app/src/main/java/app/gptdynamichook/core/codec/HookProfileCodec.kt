package app.gptdynamichook.core.codec

import app.gptdynamichook.core.model.HookProfile
import org.json.JSONArray
import org.json.JSONObject

object HookProfileCodec {
    fun encode(profiles: List<HookProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("targetPackage", profile.targetPackage)
                    .put("className", profile.className)
                    .put("methodName", profile.methodName)
                    .put("parameterTypeNames", JSONArray(profile.parameterTypeNames))
                    .put("enabled", profile.enabled)
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<HookProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val params = item.optJSONArray("parameterTypeNames") ?: JSONArray()
                    add(
                        HookProfile(
                            id = item.getString("id"),
                            targetPackage = item.getString("targetPackage"),
                            className = item.getString("className"),
                            methodName = item.getString("methodName"),
                            parameterTypeNames = buildList {
                                for (parameterIndex in 0 until params.length()) {
                                    add(params.getString(parameterIndex))
                                }
                            },
                            enabled = item.optBoolean("enabled", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
