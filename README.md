# 反代 AI API

极简 Android 个人 AI API 反向代理。支持多个上游、OpenAI 兼容接口、模型映射与 SSE 流式转发；支持本机及可选局域网访问。

## 当前状态

项目初始化阶段。首版目标：Kotlin + Android 原生 UI + 本地 HTTP 网关。

## 安全边界

- 默认仅监听 `127.0.0.1:8765`。
- 开启局域网访问时必须使用独立网关密钥。
- 上游 API Key 仅保存在设备本地，不写入日志。
- 不实现隐藏请求内容或规避上游服务商审计的功能。

## 构建

使用 Android Studio 打开项目，或在项目根目录执行 `./gradlew assembleDebug`（待 Gradle Wrapper 补齐后）。
