# GPT Dynamic Hook — 实用手册

一个可被外部程序驱动的 LSPosed 模块：规则文件热加载 + 反射命令通道，用来观察和改写目标进程里的行为。

目标客户端实测环境：`com.openai.chatgpt` 1.2026.314（versionCode 2631432），Android 17 / arm64 / root / LSPosed。

---

## 1. 两个文件就是全部接口

```
/data/data/<target>/files/gpt_rules.json     规则（本模块读）
/data/data/<target>/files/gpt_report.jsonl   命中记录（本模块写）
/data/data/<target>/files/probe_cmd.json     命令（本模块读）
/data/data/<target>/files/probe_result.json  命令结果（本模块写）
```

轮询 800ms–2000ms，改文件即生效，**不需要重启目标应用，不需要重编 APK**。

写入时注意属主：

```sh
cp rules.json /data/data/$PKG/files/gpt_rules.json
chown $(stat -c %u:%g /data/data/$PKG) /data/data/$PKG/files/gpt_rules.json
```

---

## 2. 规则格式

```json
{
  "schemaVersion": 1,
  "rules": [{
    "id": "sweep-net",
    "classNames": ["com.openai.network.ApiServiceError"],
    "classRegex": "com\\.openai\\.network\\..*",
    "methodRegex": ".*",
    "enabled": true,
    "captureStack": 10,
    "captureElapsed": true,
    "captureResult": true,
    "captureResultDepth": 24,
    "sampleEvery": 5,
    "maxHooks": 120,
    "expose": "call",
    "exposeTarget": "self",
    "rewrite": {
      "args": {"0": "literal", "1": "42"},
      "result": "null",
      "skip": false,
      "dryRun": true
    }
  }]
}
```

| 字段 | 作用 |
|---|---|
| `classNames` / `classRegex` | 二选一；正则走 `DexFile.entries()` 全量枚举 |
| `captureStack` | 0–16，栈帧数（自动过滤 hook 框架代理帧） |
| `captureResult` | 返回值**结构**快照：`Map(13){key:Type}`、`String(len=24)`，不含值 |
| `expose` | 把对象注册进命令通道，后续用 `target:"名字"` 引用 |
| `exposeTarget` | `self` / `arg0` / `result` |
| `rewrite` | 改参数 / 改返回值 / 跳过原方法；`dryRun` 只记录不生效 |
| `sampleEvery` | 每 N 次命中记 1 条 |
| `maxHooks` | 单条规则最多装多少方法（防卡死，建议 ≤120） |

**经验值**：一次 hook 超过 ~150 个热路径方法会让目标 app 白屏。先小范围，确认命中再加。

---

## 3. 命令通道

```json
{"type":"list"}
{"type":"dump","target":"call","depth":5}
{"type":"get","target":"call","path":"c.g.c","emitValues":true}
{"type":"set","target":"call","path":"a.f","value":"x"}
{"type":"call","target":"call","name":"b","args":[]}
{"type":"http","target":"https://example.com/","method":"GET","headers":{"k":"v"}}
```

| 命令 | 说明 |
|---|---|
| `list` | 已注册对象名 |
| `dump` | 按 `depth`（0–6）展开字段；`emitValues:true` 才输出字符串/字节内容 |
| `get` | 按路径取值，路径用 `.` 分隔，Map/List/Bean 都支持 |
| `set` | 反射写字段，字面量按字段类型转换 |
| `call` | 反射调方法（参数是字面量） |
| `http` | **在目标进程内**发 HTTPS 请求，返回 `{status, body}` |

`http` 的意义：请求从 app 自己的进程发出，能走它的网络栈（包括 VPN、代理、证书配置）。但**不带 app 的认证凭据** —— 它只发你给的东西。

---

## 4. 关键限制（实测，不是推测）

### 4.1 认证凭据抓不到

目标客户端用 DPoP：

```
AttributeKey: AccessTokenDPoPPlugin
AttributeKey: DPoPProofPlugin
```

- 每次请求用 Keystore 里的私钥现签 proof
- 磁盘上所有 token 都是 Fernet 加密（`gAAAAAB...` / `0gAAAAAB...`），密钥在 Keystore
- 抓到 token 后外部重放：`{"detail":"Could not parse your authentication token"}`

**结论：凭据无法外部化。** 想做外部反代，只能改用网页版 cookie 或官方 API key。

### 4.2 ktor 的请求对象不可定位

试过并失败的锚点：`j090#a`、`h090#c`、`fix#send`、`prb1#c`、`fjh0#u0/v0/B0`、`x1j0#q`、`tpm0#a`。

- `h090#c` 的 arg0 是 `z290`（HttpClientConfig），不是请求
- `nxz0`（call 对象）只有 4 个字段，无 request
- 静态 `append(String,String):void` 有 1476 个同名候选

**结论：靠名字猜锚点行不通，需要指令级分析。**

### 4.3 MITM 被 VPN 阻断

设备开着 VPN 时，`iptables --uid-owner` 匹配不到任何包（`tun0` 接管了出站流量），`ip6tables nat` 在部分内核上未编译。

MITM 本身可用（`mitm.mjs` + `certgen.mjs` 已验证能解 TLS 并读 header），前提是关掉 VPN。

### 4.4 构造器 hook 不支持

`methodRegex` 匹配的是 `declaredMethods`，不含 `<init>`。要 hook 构造器需要改 `RuleEngine`。

---

## 5. 已经验证有效的用法

```bash
# 1) 看响应头（真实数据）
{"type":"get","target":"call","path":"c.g.c","emitValues":true}
→ [{"a":"date","b":"..."},{"a":"content-type","b":"application/json"},...]

# 2) 看响应体
{"type":"get","target":"call","path":"c.b","emitValues":true}
→ "ByteArray(1326){\"token\":\"gAAAAAB...\"}"

# 3) 在 app 进程内发请求
{"type":"http","target":"https://chatgpt.com/backend-api/models"}
→ {"status":401,"body":"{\"detail\":\"Unauthorized\"}"}

# 4) 验证改写决策（不生效）
规则里 "rewrite":{"args":{"2":"x"},"dryRun":true}
→ [rewrite:dry] d090#a arg2 <- x
```

---

## 6. 构建

CI（GitHub Actions）：`gradle assembleDebug` + `testDebugUnitTest` + 元数据审计。

本机没有可用的 JVM（`libjvm.so` 依赖 `libandroid-shmem`，缺库），所以不能在设备上直接编译。

---

## 7. 文件清单

```
core/rules/HookRule.kt            规则模型
core/rules/HookRuleCodec.kt       编解码（fail closed，带上限钳制）
core/report/ResultShape.kt        返回值结构快照（不含值）
core/channel/ReflectiveChannel.kt 反射执行器 + http
core/channel/ChannelCommandCodec.kt 命令解析
hook/RuleEngine.kt                批量安装 / 栈采集 / 改写 / 落盘
hook/RuleWatcher.kt               规则文件轮询
hook/ChannelWatcher.kt            命令文件轮询
hook/ModuleEntry.kt               libxposed 入口
```
