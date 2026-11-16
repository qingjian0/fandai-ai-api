package app.fandai.proxy

import android.content.Context
import org.json.JSONObject

data class ProviderConfig(val baseUrl: String, val apiKey: String)
data class ModelRoute(val provider: String, val upstreamModel: String)
data class GatewayConfig(
    val gatewayKey: String,
    val lanEnabled: Boolean,
    val providers: Map<String, ProviderConfig>,
    val models: Map<String, ModelRoute>
) {
    companion object {
        private const val PREFS = "gateway"
        private const val CONFIG = "config"

        const val DEFAULT_JSON = """{
  "gatewayKey": "replace-with-a-random-32-character-secret",
  "lanEnabled": false,
  "providers": {
    "provider_a": {
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "YOUR_UPSTREAM_API_KEY"
    }
  },
  "models": {
    "model-a": "provider_a/model-a"
  }
}"""

        fun load(context: Context): GatewayConfig {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CONFIG, DEFAULT_JSON) ?: DEFAULT_JSON
            return parse(raw)
        }

        fun save(context: Context, raw: String) {
            parse(raw)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(CONFIG, raw).apply()
        }

        fun parse(raw: String): GatewayConfig {
            val root = JSONObject(raw)
            val gatewayKey = root.optString("gatewayKey").trim()
            require(gatewayKey.length >= 24 && !gatewayKey.startsWith("replace-")) { "gatewayKey 必须替换为至少 24 个字符的随机密钥" }

            val providersJson = root.optJSONObject("providers")
                ?: throw IllegalArgumentException("缺少 providers 配置")
            val providers = linkedMapOf<String, ProviderConfig>()
            val providerNames = providersJson.keys()
            while (providerNames.hasNext()) {
                val name = providerNames.next()
                val item = providersJson.optJSONObject(name) ?: continue
                val baseUrl = item.optString("baseUrl").trim().trimEnd('/')
                val apiKey = item.optString("apiKey").trim()
                require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
                    "上游 $name 的 baseUrl 必须是 HTTP(S) 地址"
                }
                require(apiKey.isNotBlank() && !apiKey.startsWith("YOUR_")) {
                    "请填写上游 $name 的 API Key"
                }
                providers[name] = ProviderConfig(baseUrl, apiKey)
            }
            require(providers.isNotEmpty()) { "至少配置一个上游" }

            val modelsJson = root.optJSONObject("models")
                ?: throw IllegalArgumentException("缺少 models 配置")
            val models = linkedMapOf<String, ModelRoute>()
            val modelNames = modelsJson.keys()
            while (modelNames.hasNext()) {
                val publicName = modelNames.next()
                val route = modelsJson.optString(publicName)
                val split = route.indexOf('/')
                require(split > 0 && split < route.lastIndex) { "模型映射格式错误：$publicName" }
                val provider = route.substring(0, split)
                val upstreamModel = route.substring(split + 1)
                require(providers.containsKey(provider)) { "模型 $publicName 引用了不存在的上游 $provider" }
                models[publicName] = ModelRoute(provider, upstreamModel)
            }
            require(models.isNotEmpty()) { "至少配置一个模型映射" }
            return GatewayConfig(gatewayKey, root.optBoolean("lanEnabled", false), providers, models)
        }
    }
}
