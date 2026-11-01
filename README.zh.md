# DeepSeek Harness for Android

[English](README.md) | 中文

把 DeepSeek（以及任何 OpenAI 兼容接口）做成一个**能在手机上真正干活**的 Agent 客户端。

不是又一个聊天套壳。区别在于几件硬事：上下文能自己压缩、能一次读多个文件、能通过无障碍服务操作手机本身、能装第三方插件。

纯 Java + Android 自带 API，**零第三方依赖**，构建链是手写的，不依赖 Gradle。

---

## 功能

### 对话

- 流式输出，Markdown 实时渲染（标题 / 粗斜体 / 代码块 / 列表 / 引用 / 表格）
- 长按消息：复制、重新生成
- 会话管理，标题自动取首条消息
- 深色 / 浅色 / 跟随系统，切换时以圆形扩散过渡

### 模型与推理强度

聊天页标题下方是 `模型 · 推理强度 ▾`，点开是气泡菜单：

- 模型列表来自接口的 `/models`，实时读取
- 推理强度六档：`off / low / medium / high / xhigh / max`
- 滑块拉到 `max` 时填充区转为蓝紫渐变，并有粒子向右流动

各档位映射到不同的输出上限（1024 → 8192）；旧的三档设置会自动迁移。

### 工具调用

Agent 循环**不设轮数上限**：只要模型还在请求工具就继续跑。防止原地打转靠两条：相同调用返回相同结果时提示模型「没有进展」，连续多次完全相同才判定卡死。

同一步内模型可以并发调用多个只读工具（文件读取、网页抓取、读屏），写入/点击类操作一律串行，结果按模型给出的顺序回写。

| 工具 | 说明 |
|---|---|
| `fs.list` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.delete` / `fs.info` | 文件操作，受「文件作用域」限制（工作区 / 全局） |
| `web.search` / `web.fetch` | 联网搜索与取网页正文 |
| `ui.ask` | 向用户提选择题，选项卡片直接渲染在聊天流里 |
| `a11y.*` | 读屏幕节点树、点击、滑动、输入（需无障碍权限） |
| `app.open` / `notify` | 打开应用、发通知 |

### 上下文自动压缩

历史超过模型窗口后服务端会直接拒单。处理分两层：

1. **每一步工具调用前都检查**，而不是只在开头查一次——工具返回的长内容是撑爆上下文的主要来源。
2. 超过阈值先截断超长工具结果，仍不够则把较早的整轮对话折叠成摘要。**摘要会延续上一次的摘要**，多轮压缩不会丢掉更早的内容。

界面会出现 `↻ 已折叠 5 轮 24.1k → 13.8k tokens` 这样的灰字，过程可见。

若仍被拒且错误属于「上下文过长」，会激进压缩后重试一次，不把服务端原文甩给用户。

### 选择题卡片

模型遇到需要拍板的情况会调用 `ui.ask` 给出 2–6 个选项，卡片内联在聊天流里，按真实时序排列：

```
文字 A  →  选项卡片  →  点选  →  文字 B
```

**历史可回放**：卡片不额外存储，直接从对话历史重建（问题与选项在 `tool_calls` 参数里，选择紧跟在 `tool` 消息里）。切会话、重开应用、上下文压缩都不影响。

### 附件

一次最多 8 个文件（单个 2 MB，总文本 12 万字符），重复选择会去重。

输入框上方以卡片呈现（类型徽标 · 文件名 · 大小 · 移除），点开可预览前 60 行；发送后消息气泡里同样是卡片。

### 无障碍操作

开启后模型能读屏幕内容、点击、滑动、输入文字。

Android 不允许应用自行授予无障碍权限，必须在系统设置里手动开启：

- 设置页「打开无障碍设置」直达本应用的详情页
- 未开启时提供诊断信息（组件声明、intent-filter、meta-data 解析、是否被系统列出）
- 设备已 root 时可直接写入系统设置，只追加不覆盖

### 插件

两类插件共用同一目录 `/sdcard/DShAndroid/plugins/`，靠入口文件区分。

#### 声明式 HTTP 插件

放一个 `plugin.json` 即可接入任意 HTTP 接口，不需要写代码：

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
    "request": { "method": "GET", "url": "/v1/now?city={city}", "resultPath": "data.temperature" }
  }]
}
```

- 占位符：`{参数}` 做 URL 编码、`{参数|raw}` 原样、`{参数|json}` JSON 转义
- 密钥：`${NAME}` 从 `secrets` 取，header 与 url 都可用
- `resultPath` 支持 `a.b` 与 `list[0].name`，只把需要的字段交给模型
- 允许 http/https；响应最多读 512KB、交给模型前截到 2 万字符

#### DSH 兼容插件

支持 DeepSeek Harness 的工具类插件：

```js
import { defineTool } from '@deepseek-ai/dsh-tools'

export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: 'greet',
    description: 'Greet someone by name.',
    parameters: { name: { type: 'string', required: true } },
    async execute(args) { return `Hello, ${args.name}!` },
  }))
}
```

已实现的扩展点：

| 扩展点 | 说明 |
|---|---|
| `ctx.tools.register` | 注册工具 |
| `ctx.on(name, fn)` | 事件订阅：`turn/start`、`tool/call`、`tool/result`、`message` |
| `ctx.command(name, desc, fn)` | 输入框以 `/名字 参数` 触发，支持异步 |
| `ctx.service` / `ctx.get` | 同进程服务注册表，插件间共享 |
| `fs` / `path` / `os` / `process` / `util` / `url` / `crypto` | 兼容层，文件访问限制在插件自身数据目录 |

执行在系统 WebView 中进行，原生支持 `async / await` 与 `fetch`。TypeScript 类型标注与 ESM 语法在装载前自动改写。

未实现的能力不会让插件崩溃：未知模块返回宽松占位对象，`apply()` 中途异常会保留已注册的工具。

**界面类插件（`ctx.slots` / `ctx.client` / `ctx.render`）无法使用**：DSH 的客户端插件是 React 组件，渲染在它的前端里，Android 侧没有对应的组件树可以挂载。

#### 插件市场

设置 → 插件 → 打开插件市场，数据来自 dshmarket 的开源索引，可直接搜索并一键安装：自动下载、解包到插件目录、立即重载。

---

## 安装

从 [Releases](../../releases/latest) 下载 APK。要求 Android 6.0（API 23）及以上。

> 无障碍的手势派发需要 Android 7.0+；更低版本上点击会退化为节点级操作，对部分自定义控件无效。

### 配置

设置 → 接口与密钥：填接口地址（默认 `https://api.deepseek.com`）与 API Key，保存后自动读取可用模型。

---

## 从源码构建

### 为什么不用 Gradle

整个工程只有 22 个 Java 文件、零第三方依赖，用不上 Gradle 的依赖解析与增量构建；而 Gradle 需要联网下载发行版、占用数百 MB。这里用 javac + d8 + 自写的 AXML/ARSC 打包器，几十秒出包。

### 依赖

```sh
# 1. android.jar（编译期 API 存根，不参与打包）
#    从 Android SDK 取：$ANDROID_HOME/platforms/android-29/android.jar

# 2. r8.jar（内含 d8，用于 dex 转换）

# 3. apksigner.jar（仅构建签名包时需要）

# 4. org.json（仅跑单元测试时需要）
curl -Lo deps/json.jar https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
```

### 构建

```sh
# 未签名（调试用）
ANDROID_JAR=/path/to/android.jar sh build.sh

# 签名（自备 keystore）
ANDROID_JAR=/path/to/android.jar \
R8_JAR=deps/r8.jar \
APKSIGNER_JAR=/path/to/apksigner.jar \
KEYSTORE=/path/to/release.p12 KS_ALIAS=youralias \
STORE_PASS_FILE=/path/to/pass.txt \
sh build.sh
```

### 测试

```sh
sh test/run.sh
```

215 项纯 JVM 测试，不需要设备：上下文压缩、版本比较、发布挑选、插件解析与装载、tar.gz 解包、推理等级映射。

---

## 项目结构

```
app/src/com/dsh/android/
├── a11y/        无障碍服务、状态检测、权限引导
├── kernel/      常量、会话存储、版本、更新、安装包提供者
├── llm/         模型客户端、Agent 循环、上下文压缩
├── market/      插件市场索引与安装
├── plugins/     插件模型、扫描、JS 运行时（含 Node 兼容层）
├── tools/       工具注册表与执行器
└── ui/          界面：聊天、设置、市场、自绘组件
app/assets/      插件引导脚本
tools/           手写构建链（AXML / ARSC / 打包）
test/            纯 JVM 测试
```

---

## 已知限制

- 界面类 DSH 插件无法运行（缺前端渲染体系）
- `child_process`、`net`、`dgram`、`worker_threads` 等 Node 能力不可用
- 无障碍权限必须由用户在系统设置里手动开启一次
- 目标 SDK 29，未适配 Android 12+ 的精确通知权限等新约束

---

## 许可

MIT。见 [LICENSE](LICENSE)。

滑块视觉参数参考了 [dsh-effort-switcher](https://github.com/lemonorangeapple/dsh-effort-switcher)；
并发工具调度、请求重试与压缩重试的思路参考了 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（MIT）。
本仓库不包含这两个项目的源代码。
