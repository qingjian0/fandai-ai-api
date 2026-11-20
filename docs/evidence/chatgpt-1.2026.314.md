# ChatGPT Android 1.2026.314 — 静态证据报告

对象：`com.openai.chatgpt`
样本来源：设备（root）`pm path` 提取 `base.apk`，未修改、未重打包。
分析口径：只读 DEX 结构（`string_ids` / `type_ids` / `proto_ids` / `method_ids` / `class_defs` / `class_data_item`），不做任何指令执行、不触发网络、不读取应用私有数据。
复核工具：任意 DEX 解析器（`baksmali` / `dexdump` / `dexlib2`）在同一 `byte offset` 上应得到相同结果。

---

## 1. 样本指纹

### APK

```
file            base.apk
size            101,536,416 bytes
SHA-256         b31a9537ffc6c18fa3657966ab0254e8c8fc68f8cf35b281bc2649e69e7154af
packageName     com.openai.chatgpt
versionName     1.2026.314
versionCode     2631432
minSdk          32
targetSdk       37
signer SHA-256  13e17c5dd7ee6be96f56625dce30f7bad62082b9a4834aa2a7ef3f9bfa9d9fff
```

`sdkVersion` / `signer` 取自 `dumpsys package com.openai.chatgpt`；`signer SHA-256` 为 APK Signature Scheme v2/v3 签名块中唯一证书的 DER 编码 SHA-256 摘要。

### DEX（9 个，共 76,359,352 bytes）

| 文件 | size | classes | methods | SHA-256 |
|---|---:|---:|---:|---|
| classes.dex | 9,957,000 | 9,600 | 65,534 | `fa9f86f880acaad798286989cca87699446828c151aa41d0cc095455d0a05615` |
| classes2.dex | 9,180,764 | 9,203 | 65,522 | `8a69752e93368feaf2efb214e74de2f90c6f89491f21ee285ceb8141d82b1530` |
| classes3.dex | 9,116,900 | 10,816 | 61,071 | `aff6f40f6013d0ddc4a2a64c4661c52085447486ae91191677a99e899f6bb6f4` |
| classes4.dex | 14,145,568 | 10,588 | 65,536 | `81a4e81992012a0b375a0aeb3314a1909434255452f2b61fe7de72b5726d3fff` |
| classes5.dex | 8,831,780 | 12,297 | 59,354 | `916b06b67e0794fd90ea2e2091e64225d22faba8e9007938bc756ad95a97809c` |
| classes6.dex | 8,502,944 | 11,816 | 57,444 | `f20d233e3cb5026543e2efb44b723b9117892468e6a1fa775aec5bc9f834e581` |
| classes7.dex | 10,859,428 | 12,414 | 63,009 | `c80cc88940e953875d775fc501ec6967bc887d6be9b671a21f1f0a6567295bc7` |
| classes8.dex | 9,012,080 | 11,632 | 63,544 | `508df04da122a7a9d3df8cc9587ab29ff035c42fcb43472c634c822501eb9622` |
| classes9.dex | 4,718,428 | 5,819 | 26,765 | `fe5347d57405ccbe1648b0dd139420bd1f43d141bb9ce8a6db494988e7070882` |
| classes10.dex | 285,040 | 158 | 1,821 | `045fe917eb708b814ada5b19d5b11c3f5170d52e5a8201c5bd69b0db8a49a24f` |

---

## 2. `NetworkRequestObservation` —— 否证结论

**该类名在 9 个 DEX 的 `type_ids`（31,121 项/包均查）和 `class_defs` 中均不存在。**

| 检查 | 结果 |
|---|---|
| `class_defs` 中 descriptor 匹配 | 0 处 |
| `type_ids` 中含该字符串 | 0 处 |
| `method_ids` 中同名方法 | 0 处 |
| 任一线程 descriptor 引用该类型 | 0 处 |
| 作为裸 UTF-8 字符串出现在 | `classes2.dex`、`classes4.dex` |

字符串存在但类型/方法都不存在，说明它在该版本中已被 R8 内联消除，只残留为常量（日志 tag 或反射字面量）。**它不能作为 Hook 锚点，也无法被 `ExecutableResolver` 解析。**
这一条同时否证了上一轮"`NetworkRequestObservation` 是最像观测点"的推测；此前只依据 `grep` 字符串命中，属证据不足。

## 3. `NetworkRequestMetadataThrowable` —— 确证结构

位于 `classes2.dex`：

```
class descriptor    Lcom/openai/network/NetworkRequestMetadataThrowable;
class_def offset    0x17420c
class_data offset   0x86007f
access_flags        public | final              (0x11)
superclass          Ljava/lang/Throwable;
instance fields     1
static fields       0
virtual methods     0
direct methods      1
```

唯一方法：

```
access_flags  0x10001  = public | constructor
signature     Lcom/openai/network/NetworkRequestMetadataThrowable;-><init>(Lg1l0;)V
code_off      0x6911ac
```

对 `Lg1l0;` 的核实：在 `classes2.dex` 中真实存在，`class_def offset 0x17e3cc`，`superclass = Ljava/lang/Object;`。

**用途判定**：继承 `java.lang.Throwable` 且仅有一个接收元数据对象的构造函数，因此它是**异常类型**，不是元数据容器。它被抛出时携带 `g1l0` 实例，但字段本身不可从异常名推断。

**可观测范围**：通过它只能得到"该异常被构造/抛出"这一事实本身（类名、构造次数、耗时、异常类名）。**看不到** 请求 method、host、header 名、响应状态——这些字段不在该类上。若需要这些字段，锚点必须落在 `g1l0` 的实现上，或落在真正的传输层。

## 4. okhttp3 公开类名已不可用（复核上一轮结论）

全 APK 中 `okhttp3/` 前缀仅 8 次命中，且不存在 `okhttp3/Request`、`okhttp3/Request$Builder`、`okhttp3/Response`、`okhttp3/Interceptor`、`okhttp3/Call`：

```
okhttp3/OkHttpClient
okhttp3/internal/ech/EchUntrustedException
okhttp3/internal/http2/ConnectionShutdownException
okhttp3/internal/http2/StreamResetException
okhttp3/internal/platform/PlatformInitializer
```

结论：任何以 `okhttp3.Request$Builder` 为锚点的 Profile 在本版本必然解析失败。

### 4.1 真实 HTTP 栈是 Ktor，不是 OkHttp

`io/ktor/` 前缀共 91 次命中，且存在完整的 OkHttp **引擎适配层**：

```
Lio/ktor/client/engine/okhttp/StreamAdapterIOException;
Lio/ktor/client/engine/okhttp/UnsupportedFrameTypeException;
Lio/ktor/client/engine/ClientEngineClosedException;
Lio/ktor/client/statement/HttpResponse;
Lio/ktor/http/Cookie;
```

即 `ktor-client-okhttp` 引擎。OkHttp 只作为 Ktor 的传输引擎被间接依赖，其公开 API 面被 R8 剥离，因此 `okhttp3.Request` 这类类名不可用；而 Ktor 自身也只保留了异常类与少量公共值类名，`HttpClientEngine`、`HttpRequestBuilder`、`HttpSend`、`RequestData`、`HttpClientCall` 在 `class_defs` 中均未命中（已按类名前缀 + 方法过滤查询），说明这些核心类型同样被重命名或内联。

可用的稳定锚点因此只剩两类：

1. **未混淆的应用类**（`com.openai.**`，372 个），例如 `com.openai.network.ApiServiceError` 保留了两个 public final 取值方法 `a()Ljava/lang/String;`、`b()Ljava/lang/String;`。
2. **未混淆的异常类**，可在构造时机观察，但看不到请求字段。

### 4.2 构造器作为锚点的产品限制

`NetworkRequestMetadataThrowable` 只有 `<init>`，而当前 `HookProfileValidator` 的 `memberPattern` 为 `^[A-Za-z_$][A-Za-z0-9_$]*$`，**不接受 `<init>` / `<clinit>`**，因此该类目前无法写入 Profile。这是一条产品决策点：是否允许构造 Hook。本报告不假设它已被支持。

因此提供的可解析 probe 使用 `com.openai.network.ApiServiceError->a()Ljava/lang/String;`（无参数，dex 中有确证定义）。该方法的名称是 R8 产物，跨版本不稳定，仅用于验证"解析 → 安装 → pass-through 回调 → 报告回传"链路，不作为业务观测点。

---

## 5. 未完成项（明确标注，不声称已完成）

1. **精确调用链**（发送入口 → 观测点 → 传输层）需要 `.code_item` 指令级解码（`invoke-*` 的 `method_idx`）。本报告只做到类/方法/descriptor 层级，未做指令流解析，因此**不提供调用链**。
2. `g1l0`、`aq91`、`fq91` 等混淆元数据类的字段与方法尚未枚举，因此其能否承载 method/host/header names 未知。
3. 全部结论未在真机运行验证：解析成功不等于 Hook 成功，Hook 成功不等于字段可读。

## 5.1 提供的可解析 Profile

`docs/evidence/profiles/chatgpt-1.2026.314-resolve-probe.json`：

```json
{
  "id": "chatgpt-1.2026.314-api-error-getter",
  "targetPackage": "com.openai.chatgpt",
  "className": "com.openai.network.ApiServiceError",
  "methodName": "a",
  "parameterTypeNames": [],
  "enabled": false,
  "targetVersionCode": 2631432,
  "targetSignerSha256": "13e17c5d…9fff"
}
```

- `enabled=false`：默认不安装 Hook。
- 无参数、返回 `String`（`0x11` = public final），`ExecutableResolver` 只需 `Class.forName` + `declaredMethods` 名称/参数匹配即可命中，**不会触发任何网络行为**——它只是取值方法，Hook 采用 `ExceptionMode.PROTECTIVE` + pass-through。
- 该 Profile 的用途是验证链路，不是采集业务数据。若 pin 生效，则只在 `2631432` + 上述签名指纹的目标上安装。

## 6. 复现步骤

```bash
# 1) 取 APK 与 dex
pm path com.openai.chatgpt
cp <base.apk> /data/local/tmp/chatgpt.apk
mkdir -p /data/local/tmp/dex
unzip -o -q /data/local/tmp/chatgpt.apk 'classes*.dex' -d /data/local/tmp/dex

# 2) 指纹
sha256sum /data/local/tmp/chatgpt.apk /data/local/tmp/dex/*.dex

# 3) 类结构（任一 DEX 工具，注意 class_def offset 应与本报告一致）
dexdump -f -d /data/local/tmp/dex/classes2.dex | grep -A5 NetworkRequestMetadataThrowable
```

## 7. 未包含的内容

本报告不含 APK 本体、DEX 本体、字符串 dump、资源、证书文件、账号信息或任何应用私有数据；只含结构偏移与摘要。
