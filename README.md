# GPT Dynamic Hook

独立安装的 LSPosed 模块 APK，使用现代 libxposed API 102。

## 产品边界

- 从干净基线重新编写，不继承旧 HTTP 反向代理业务。
- 只交付一个 APK：`GPT Dynamic Hook`。
- DSH 主 APK 保持原样；DSH 是外部集成对象，不复制其内部实现。
- 首版使用 Kotlin、Android、libxposed API 102 和 libxposed Service 102。
- Native Hook 暂不纳入首版。

## 已实现

- 单 APK Android 工程与现代 libxposed 模块入口。
- LSPosed Service 连接与框架状态读取。
- 通过 libxposed Remote Preferences 在模块 UI 与目标进程之间共享 Profile。
- Profile JSON 编解码、字段校验、精确包名匹配。
- 运行时按类名、方法名和参数类型解析方法。
- Profile 集合按稳定 ID 执行 upsert、删除和启停，具备纯 JVM 单元测试。
- UI 支持 Profile 列表、新建、编辑、删除、启用/禁用。
- Profile 远程读写在单线程后台队列执行，避免阻塞主线程并保持 UI 写入顺序。
- 仅对显式启用且完全匹配的 Profile 安装 pass-through Hook。

## 当前限制

- pass-through Hook 仅验证动态安装链路，不修改请求，也不代理网络。
- GPT 请求拦截、SSE、DSH Gateway/Agent/插件适配尚未实现。
- 需要在真实 LSPosed 环境中验证 Remote Preferences、Hook 生命周期及不同 Android/客户端版本。
- 目前尚无目标客户端的真实类名/方法签名 Profile；示例表单默认配置不启用。

## 构建

需要 JDK 17、Android SDK 37 Preview 和 Gradle 9.3.1。当前 CI 使用 Android 17 Preview 平台：

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

JVM 单元测试使用独立的 `org.json` 测试依赖；APK 运行时仍使用 Android 平台 JSON 实现。

CI 对同一分支或 PR 的新提交取消过期运行，并设置 25 分钟作业超时，避免旧提交长期占用构建资源。

## 安全边界

- 仅在用户授权的 LSPosed 作用域内运行。
- 不自动扩大作用域；不绕过客户端认证、服务端访问控制或安全策略。
- Profile 默认禁用；只有包名、类名、方法名和参数签名全部通过校验才会尝试 Hook。
- Hook 使用 protective exception mode；配置错误时不主动改变原方法行为。
- 不记录请求正文、凭据或个人数据。
