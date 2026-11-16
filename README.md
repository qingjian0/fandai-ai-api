# 反代 AI API

极简 Android 个人 AI API 反向代理。支持多个上游、OpenAI 兼容的 Chat Completions 接口、模型映射与 SSE 流式转发。

## 当前能力

- Android 本地 HTTP 网关，默认监听 `127.0.0.1:8765`
- 可选局域网监听（`0.0.0.0:8765`）
- 独立网关 Bearer Key；所有 API 路由（健康检查除外）均需鉴权
- 多上游配置及公开模型名到上游模型名的映射
- `GET /v1/models`
- `POST /v1/chat/completions`，支持普通响应与 SSE 流式响应
- 上游 API Key 保存在应用私有 SharedPreferences，不写入日志

## 配置

在 APK 首页编辑 JSON 配置并保存。示例：

```json
{
  "gatewayKey": "替换为至少24字符的随机密钥",
  "lanEnabled": false,
  "providers": {
    "provider_a": {
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "你的上游API密钥"
    },
    "provider_b": {
      "baseUrl": "https://other.example.com/v1",
      "apiKey": "另一个上游API密钥"
    }
  },
  "models": {
    "model-a": "provider_a/model-a",
    "model-b": "provider_b/model-b"
  }
}
```

模型映射格式为 `客户端模型名: 上游名称/上游模型名`。例如客户端请求 `model-a`，网关会将请求中的 model 改写为上游模型名后转发。

保存配置后启动或重启网关。客户端 Base URL 使用 `http://127.0.0.1:8765/v1`；开启局域网时，使用手机局域网 IP 和相同端口。客户端 API Key 填写 `gatewayKey`，不是上游 API Key。

## 安全说明

- 默认关闭局域网访问。
- 开启局域网前，必须设置独立、随机且至少 24 字符的 `gatewayKey`。
- 不要将网关端口暴露到公网。
- 上游密钥只在转发时发送给对应上游，不返回给客户端。
- 仅支持 Chat Completions 路由；其他 API 路由返回 404。

## 构建

使用 Android Studio 打开项目。CI 使用 JDK 17 和 Gradle 8.9 执行：

```bash
gradle assembleDebug
```

当前项目尚未提交 Gradle Wrapper。
