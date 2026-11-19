# GPT Dynamic Hook

独立安装的 LSPosed 模块 APK，使用现代 libxposed API。产品目标是提供受控的客户端识别、动态 Hook Profile、请求处理与流式适配能力。

## 工程边界

- 本仓库从干净基线重新编写，不继承旧 HTTP 反向代理业务。
- 只构建一个 APK：`GPT Dynamic Hook`。
- DSH 主 APK 是外部集成对象；不复制其内部代码，不修改或替换 DSH。
- 首版采用 Kotlin + Android + libxposed API 102；Native Hook 暂不纳入。
- 默认不对任何目标应用安装 Hook。必须先有显式启用且通过校验的 Profile。

## 当前实现

- 单 APK Android 工程与现代 libxposed 入口元数据。
- libxposed API 102 编译期依赖。
- HookProfile 声明模型。
- 按 ClassLoader、类名、方法名和参数类型精确解析方法的基础解析器。
- 基础启动页；当前版本不执行目标应用 Hook。

## 构建

需要 JDK 17、Android SDK 35 和 Gradle 8.9：

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

## 安全边界

- 仅在用户授权的 LSPosed 作用域内运行。
- 不自动扩大作用域，不绕过目标应用认证、服务端访问控制或安全策略。
- 目标 Profile 默认禁用；安装 Hook 前必须验证目标包名、签名/版本约束与方法签名。
- 敏感参数和凭据不得写入普通日志。
