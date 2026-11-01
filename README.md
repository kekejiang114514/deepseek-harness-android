# DeepSeek Harness for Android

English | [中文](README.zh.md)

An Agent client that makes DeepSeek (or any OpenAI-compatible endpoint) **actually do work on a phone**.

This is not another chat wrapper. What sets it apart: the context compacts itself, multiple files can be read at once, the phone itself can be operated through the accessibility service, and third-party plugins can be installed.

Pure Java on top of the Android platform APIs — **zero third-party dependencies**, hand-written build chain, no Gradle.

---

## Features

### Conversation

- Streaming output with live Markdown rendering (headings, emphasis, code blocks, lists, quotes, tables)
- Long-press a message to copy or regenerate
- Session management with automatic titles from the first message
- Dark / light / follow-system, with a circular-reveal transition

### Model and reasoning effort

Below the chat title sits `model · effort ▾`, which opens a popup:

- Models are read live from the endpoint's `/models`
- Six effort levels: `off / low / medium / high / xhigh / max`
- At `max` the filled track turns into a blue-to-purple gradient with particles flowing right

Each level maps to a different output budget (1024 → 8192). Settings from the old three-level scheme migrate automatically.

### Tool calling

The agent loop has **no step limit**: it keeps going as long as the model keeps asking for tools. Two guards prevent spinning in place — a repeated call returning an identical result is flagged to the model, and several identical results in a row are treated as a stall.

Within one step, read-only tools (file reads, web fetches, screen dumps) run concurrently; writes and taps stay sequential. Results are written back in the order the model requested them.

| Tool | Purpose |
|---|---|
| `fs.list` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.delete` / `fs.info` | File access, bounded by the file scope (workspace / device) |
| `web.search` / `web.fetch` | Web search and page text extraction |
| `ui.ask` | Ask the user a multiple-choice question, rendered inline in the chat |
| `a11y.*` | Read the node tree, tap, swipe, type (requires accessibility) |
| `app.open` / `notify` | Launch an app, post a notification |

### Automatic context compaction

Once history exceeds the model window the server rejects the request outright. Two layers handle it:

1. **Checked before every tool step**, not just once at the start — long tool results are the main contributor.
2. Over the threshold, oversized tool results are truncated first; if that is not enough, earlier turns are folded into a summary. **The summary extends the previous one**, so repeated compaction never drops older content.

A grey line such as `↻ folded 5 turns 24.1k → 13.8k tokens` appears in the chat so the process stays visible.

If the request is still rejected with a context-overflow error, history is compacted aggressively and retried once instead of surfacing the raw error.

### Multiple-choice cards

When a decision needs the user, the model calls `ui.ask` with 2–6 options. Cards are inline in the chat and follow the real ordering:

```
text A  →  option card  →  selection  →  text B
```

**History replays correctly**: cards are not stored separately, they are rebuilt from the conversation itself (question and options live in the `tool_calls` arguments, the answer in the following `tool` message). Switching sessions, restarting the app, or compacting the context does not break them.

### Attachments

Up to 8 files per message (2 MB each, 120k characters total). Re-selecting the same file is merged, not duplicated.

Attachments appear as cards above the input (type badge, name, size, remove); tapping shows the first 60 lines. Sent messages render the same cards.

### Accessibility control

With the service enabled, the model can read the screen, tap, swipe and type.

Android does not allow an app to grant itself accessibility — the user must enable it manually:

- The settings entry deep-links straight to this app's accessibility page
- Diagnostics report component declaration, intent-filter, meta-data parsing, and whether the system lists the service
- On rooted devices the app can write the setting directly, appending rather than overwriting

### Plugins

Two kinds share one directory, `/sdcard/DShAndroid/plugins/`, distinguished by their entry file.

#### Declarative HTTP plugins

A single `plugin.json` connects any HTTP API, no code required:

```json
{
  "id": "weather",
  "name": "Weather",
  "version": "1.0.0",
  "baseUrl": "https://api.example.com",
  "headers": { "Authorization": "Bearer ${TOKEN}" },
  "secrets": { "TOKEN": "your-key" },
  "tools": [{
    "name": "weather.now",
    "description": "Current weather for a city",
    "parameters": {
      "type": "object",
      "properties": { "city": { "type": "string" } },
      "required": ["city"]
    },
    "request": { "method": "GET", "url": "/v1/now?city={city}", "resultPath": "data.temperature" }
  }]
}
```

- Placeholders: `{arg}` URL-encoded, `{arg|raw}` verbatim, `{arg|json}` JSON-escaped
- Secrets: `${NAME}` resolves from `secrets`, usable in headers and URLs
- `resultPath` supports `a.b` and `list[0].name` so only the needed fields reach the model
- http/https only; responses are capped at 512 KB on read and 20k characters before reaching the model

#### DSH-compatible plugins

Tool plugins written for DeepSeek Harness run as-is:

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

Implemented extension points:

| Extension point | Behaviour |
|---|---|
| `ctx.tools.register` | Register a tool |
| `ctx.on(name, fn)` | Event subscription: `turn/start`, `tool/call`, `tool/result`, `message` |
| `ctx.command(name, desc, fn)` | `/name args` in the input box, async supported |
| `ctx.service` / `ctx.get` | In-process service registry shared between plugins |
| `fs` / `path` / `os` / `process` / `util` / `url` / `crypto` | Compatibility layer; file access is confined to the plugin's own data directory |

Execution happens in the system WebView, so `async / await` and `fetch` work natively. TypeScript annotations and ESM syntax are rewritten before loading.

Missing capabilities never crash a plugin: unknown modules return a permissive placeholder, and a throwing `apply()` keeps whatever tools were already registered.

**UI plugins (`ctx.slots` / `ctx.client` / `ctx.render`) cannot work**: DSH client plugins are React components rendered inside its frontend; there is no component tree on the Android side to mount them into.

#### Plugin market

Settings → Plugins → Open plugin market. The index comes from dshmarket's open data; search and install in one tap — download, unpack into the plugin directory, reload immediately.

---

## Install

Download the APK from [Releases](../../releases/latest). Android 6.0 (API 23) or newer.

> Gesture dispatch requires Android 7.0+; on older versions taps fall back to node-level actions, which fail on some custom-drawn controls.

### Configuration

Settings → Endpoint and key: enter the base URL (default `https://api.deepseek.com`) and an API key; available models load automatically after saving.

---

## Building from source

### Why not Gradle

The whole project is 22 Java files with zero third-party dependencies, so Gradle's dependency resolution and incremental builds buy nothing — while costing a downloaded distribution and hundreds of megabytes. javac + d8 + a hand-written AXML/ARSC packer produces an APK in tens of seconds.

### Dependencies

```sh
# 1. android.jar (compile-time stubs, not packaged)
#    From the Android SDK: $ANDROID_HOME/platforms/android-29/android.jar

# 2. r8.jar (contains d8, performs dex conversion)

# 3. apksigner.jar (only needed to produce a signed build)

# 4. org.json (only needed to run unit tests)
curl -Lo deps/json.jar https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
```

### Build

```sh
# Unsigned (for debugging)
ANDROID_JAR=/path/to/android.jar sh build.sh

# Signed (bring your own keystore)
ANDROID_JAR=/path/to/android.jar \
R8_JAR=deps/r8.jar \
APKSIGNER_JAR=/path/to/apksigner.jar \
KEYSTORE=/path/to/release.p12 KS_ALIAS=youralias \
STORE_PASS_FILE=/path/to/pass.txt \
sh build.sh
```

### Tests

```sh
sh test/run.sh
```

215 pure-JVM tests, no device needed: context compaction, version comparison, release selection, plugin parsing and loading, tar.gz extraction, effort-level mapping.

---

## Project layout

```
app/src/com/dsh/android/
├── a11y/        Accessibility service, state detection, permission guidance
├── kernel/      Constants, session storage, version, updater, APK provider
├── llm/         Model client, agent loop, context compaction
├── market/      Plugin market index and installer
├── plugins/     Plugin model, discovery, JS runtime (with Node compatibility layer)
├── tools/       Tool registry and executors
└── ui/          UI: chat, settings, market, hand-drawn components
app/assets/      Plugin bootstrap script
tools/           Hand-written build chain (AXML / ARSC / packaging)
test/            Pure-JVM tests
```

---

## Known limitations

- UI-type DSH plugins cannot run (no frontend rendering layer)
- Node-only capabilities such as `child_process`, `net`, `dgram`, `worker_threads` are unavailable
- Accessibility must be enabled once by the user in system settings
- Targets SDK 29; newer Android policy changes (e.g. exact notification permission) are not handled

---

## License

MIT. See [LICENSE](LICENSE).

Slider visuals reference [dsh-effort-switcher](https://github.com/lemonorangeapple/dsh-effort-switcher);
concurrent tool scheduling, request retry and compaction-retry follow the approach of
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT). This repository contains
source code from neither project.
