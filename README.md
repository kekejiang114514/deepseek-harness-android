# DeepSeek Harness for Android

把 DeepSeek（以及任何 OpenAI 兼容接口）做成一个**能在手机上真正干活**的 Agent 客户端。

这不是又一个「聊天套壳」。区别在于三个硬骨头：上下文能自己压缩、能同时读多个文件、能通过无障碍服务操作手机本身。

纯 Java + Android 自带 API，**零第三方依赖**，整个 APK 只有 85 KB。

---

## 功能

### 对话

- 流式输出，Markdown 实时渲染（标题 / 粗斜体 / 代码块 / 列表 / 引用 / 表格）
- 长按消息：复制、重新生成
- 会话管理，标题自动取首条消息
- 深色 / 浅色 / 跟随系统

### 工具调用

Agent 循环最多 12 步，工具结果回灌后继续推理。内置：

| 工具 | 说明 |
|---|---|
| `fs.list` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.delete` / `fs.info` | 文件操作，受「文件作用域」限制（工作区 / 全局） |
| `web.search` / `web.fetch` | 联网搜索与取网页正文 |
| `ui.ask` | **向用户提选择题**，选项卡片直接渲染在聊天流里 |
| `a11y.dump` / `a11y.click` / `a11y.tap` / `a11y.swipe` / `a11y.settext` 等 | 读屏幕节点树、点击、滑动、输入（需要无障碍权限） |
| `app.open` / `notify` | 打开应用、发通知 |

### 上下文自动压缩

聊长了会「发不出去」，因为历史超过模型上下文窗口后服务端直接拒单。

处理方式分两层：

1. **每一步工具调用前都检查**（不是只在开头发一次）。工具返回的长内容是撑爆上下文的主要来源，一轮对话里能累积好几次。
2. 超过 24k token 就先截断超长工具结果，仍不够则把较早的整轮对话折叠成摘要，压到 14k 左右。**摘要会延续上一次的摘要**，否则多轮压缩会把更早的内容丢掉。

界面会出现一行灰字 `↻ 已折叠 5 轮 24.1k → 13.8k tokens`，你随时知道它发生了什么。

万一还是被拒，且错误是「上下文过长」那一类（各家措辞不同，`context length exceeded`、`maximum context length`、`too many tokens` 都认），会激进压到 6k 再重试一次，不把错误直接甩给你。

### 选择题卡片

AI 遇到需要拍板的情况（多个方案、缺你的偏好）会调 `ui.ask` 给 2–6 个选项。卡片内联在聊天流里，按真实时序排列：

```
文字 A  →  选项卡片  →  你点完  →  文字 B
```

**历史可回放**。卡片不额外存一份，而是直接从对话历史重建：问题文本和选项本来就在 `tool_calls` 的参数里，你的选择在紧随其后的 `tool` 消息里（靠 call id 对上）。数据自包含，切会话、重开 App、上下文压缩都不影响它。

### 多文件上传

一次可选最多 8 个文件（单个 2 MB，总文本 12 万字符）。重复选同一个文件会去重合并。

### 无障碍控制

开启后 AI 能读屏幕内容、点按钮、滑动、输入文字。

**必须由你在系统里手动开启一次**——Android 不允许应用自行申请无障碍权限，这是系统设计，不是本应用的限制。开启方式：设置页「开启屏幕操作」→ 直奔本应用的无障碍页面 → 打开开关。开关是灰的时候，说明这是 Android 13+ 对侧载应用的「受限设置」限制，点该页右上角 ⋮ →「允许受限设置」。

模型在服务没开时尝试屏幕操作，应用会弹一次带「去开启」按钮的引导，并把原因告诉模型，避免它反复重试。

### 插件

放一个 `plugin.json` 就能接任意 HTTP 接口，不用改代码、不用重新编译。

插件目录（设置页「插件目录」可复制路径）：

```
/sdcard/DShAndroid/plugins/<插件名>/plugin.json
```

最小清单：

```json
{
  "id": "weather",
  "name": "天气",
  "version": "1.0.0",
  "baseUrl": "https://api.example.com",
  "headers": { "Authorization": "Bearer ${TOKEN}" },
  "secrets": { "TOKEN": "你的密钥" },
  "tools": [{
    "name": "weather.now",
    "description": "查询城市当前天气",
    "parameters": {
      "type": "object",
      "properties": { "city": { "type": "string" } },
      "required": ["city"]
    },
    "request": {
      "method": "GET",
      "url": "/v1/now?city={city}",
      "resultPath": "data.temperature"
    }
  }]
}
```

- 占位符：`{参数}` 做 URL 编码，`{参数|raw}` 原样，`{参数|json}` JSON 转义（放在 body 里写 `"text":"{参数|json}"`）
- 密钥：`${NAME}` 从 `secrets` 里取，header 和 url 都能用
- `resultPath` 支持 `a.b` 与 `list[0].name`，用来只把需要的字段交给模型
- POST/PUT/PATCH 可写 `body`，`contentType` 支持 `json` / `form` / 任意 MIME
- 响应最多读 512KB，交给模型前截到 2 万字符

设置页有「查看插件格式示例」，可以直接把示例写进插件目录，改改就是自己的。

---

## 安装

[**下载最新 APK**](../../releases/latest)

要求 Android 6.0 (API 23) 及以上，targetSdk 29。

> 无障碍的**手势派发**（`dispatchGesture`）需要 Android 7.0+；更低版本上点击会退化为节点级 `performAction`，对部分自定义控件无效。

### 配置

1. 打开应用 → 右上角 ⚙ → 填入 API Key 和接口地址
2. 接口地址默认 `https://api.deepseek.com/v1`，任何 OpenAI 兼容服务都能用（改 base URL 即可）
3. 保存后模型列表会自动拉取，下拉选择即可

> **开启无障碍**：设置页 → 「系统无障碍设置」→ 找到 DeepSeek Harness → 打开。
> 如果列表里找不到，说明装的是旧版本。

---

## 从源码构建

### 为什么不用 Gradle

沙盒里 `aapt2` 只有 x86-64 版本，aarch64 设备跑不了。所以这里**完全绕开 aapt2**：

- 资源表 `resources.arsc` 用 Python 手写二进制
- `AndroidManifest.xml` 用 Python 生成二进制 AXML
- 字节码用 `d8`（纯 JVM 工具，任何架构都能跑）
- APK 用 Python 手写 zip（含 4 字节对齐）

结果是不需要 Android Studio、不需要 Gradle、不需要 SDK，一台 ARM Linux 机器就能编译签名出可安装的 APK。

### 依赖

三样东西，放到 `deps/` 下（已在 `.gitignore` 里忽略）：

```sh
mkdir -p deps

# 1. android.jar（编译期 API 存根，不参与打包）
#    从 Android SDK 取：$ANDROID_HOME/platforms/android-29/android.jar
#    或任何平台的 android.jar 都可以

# 2. r8.jar（内含 d8，用于 dex 转换）
curl -Lo deps/r8.jar https://storage.googleapis.com/r8-releases/raw/main/r8.jar

# 3. apksigner.jar（签名，仅构建签名包时需要）
#    Android SDK build-tools 里有：build-tools/*/lib/apksigner.jar

# 4. org.json（仅跑单元测试时需要）
curl -Lo deps/json.jar https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
```

环境需要 JDK 17+。

### 构建

```sh
# 未签名（调试用）
ANDROID_JAR=deps/android.jar R8_JAR=deps/r8.jar SKIP_SIGN=1 sh build.sh

# 签名（自备 keystore）
keytool -genkeypair -keystore keys/release.p12 -storetype PKCS12 \
        -alias dshandroid -keyalg RSA -keysize 2048 -validity 10000
ANDROID_JAR=deps/android.jar R8_JAR=deps/r8.jar \
APKSIGNER_JAR=deps/apksigner.jar sh build.sh
```

产物在 `out/app.apk`。

所有路径都能用环境变量覆盖：`ANDROID_JAR`、`R8_JAR`、`APKSIGNER_JAR`、`KEYSTORE`、`KS_ALIAS`、`JAVA_HOME`。

### 测试

```sh
sh test/run.sh
```

纯 JVM，不需要设备。`HistoryCompactor` 和 `AskLog` 刻意只依赖 `org.json`、不碰 Android API，所以能直接在桌面 JDK 上跑——这也是它们被设计成独立类的原因。

47 项断言，覆盖压缩预算、摘要延续、重复压缩幂等、中文 token 估算、卡片重建、压缩后不错位、坏参数跳过等。

---

## 项目结构

```
app/src/com/dsh/android/
├── kernel/          常量、会话持久化（SharedPreferences + JSON）
├── llm/             HTTP 客户端（HttpURLConnection）、SSE 流式解析
│   ├── AgentLoop.java       Agent 循环：请求 → 工具 → 回灌 → 再请求
│   └── HistoryCompactor.java 上下文压缩（纯 JVM，可单测）
├── tools/           工具运行时、文件作用域限制
├── a11y/            无障碍服务
├── ui/
│   ├── MainActivity.java    聊天界面、消息渲染
│   ├── Md.java              Markdown → 原生 View（不用 WebView）
│   ├── AskBridge.java       选择题的跨线程等待
│   └── AskLog.java          从历史重建卡片（纯 JVM，可单测）
└── plugins/         系统能力封装

tools/               构建工具链
├── app_def.py       应用定义：包名、组件、权限、资源
├── arsc_build.py    resources.arsc 二进制生成
├── axml_build.py    通用 AXML 生成器
├── manifest_gen.py  AndroidManifest.xml 生成
├── make_a11y_xml.py 无障碍配置 XML（二进制 AXML）
└── pack_apk.py      zip 打包（4 字节对齐）

test/                单元测试
```

---

## 已知限制

- **0.9 之前版本产生的选择题卡片无法恢复**。当时没有把问题和选项写进历史，没有数据可重建。从 0.9 起新产生的都会留档。
- 无障碍只支持标准 View 体系，Flutter / 游戏自绘界面可能读不到节点（但坐标点击仍可用）。
- 单文件上限 2 MB，超出会跳过并提示。
- 不支持图片输入（模型端视觉能力未接入）。
- 无内置 shell 工具，文件与网络操作走结构化工具，避免任意命令执行。

---

## 许可

MIT，见 [LICENSE](LICENSE)。
