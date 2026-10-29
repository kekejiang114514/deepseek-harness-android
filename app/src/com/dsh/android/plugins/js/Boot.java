package com.dsh.android.plugins.js;

/**
 * 在 WebView 里运行的引导脚本：模拟 Cordis 插件宿主的最小面。
 *
 * <p>只实现 DSH 插件最常见的那条路径：
 * {@code export function apply(ctx) { ctx.tools.register(...) }}。
 * 事件订阅、子插件、自定义 service 一律显式抛错，而不是静默失效——
 * 让用户在设置页看到「这个插件用了不支持的能力」，比让它悄悄什么都不做强。
 *
 * <p>脚本本身用 {@code new Function} 包住插件源码，避免污染全局；
 * 异步工具直接靠 Promise，WebView 原生支持 async/await。
 */
public final class Boot {

    private Boot() {}

    /** 引导脚本。返回值用 JSON 表达，便于 Java 侧解析。 */
    public static final String JS =
        "(function(){\n" +
        "  var reg = {};          // pluginId -> [{name,description,parameters,execute}]\n" +
        "  var mtimes = {};\n" +
        "  var mods = {\n" +
        "    '@deepseek-ai/dsh-tools': { defineTool: function(s){ return s; } },\n" +
        "    '@deepseek-ai/cordis': {}\n" +
        "  };\n" +
        "  window.__mod = function(n){ return mods[n] || {}; };\n" +
        "  window.defineTool = function(s){ return s; };\n" +
        "  window.__errors = {};\n" +
        "\n" +
        "  function ctxFor(id) {\n" +
        "    return {\n" +
        "      tools: {\n" +
        "        register: function(t){\n" +
        "          if (!t || !t.name) throw new Error('工具缺少 name');\n" +
        "          if (typeof t.execute !== 'function') throw new Error('工具 ' + t.name + ' 缺少 execute');\n" +
        "          (reg[id] = reg[id] || []).push({\n" +
        "            name: String(t.name), description: String(t.description || ''),\n" +
        "            parameters: t.parameters || {}, execute: t.execute\n" +
        "          });\n" +
        "          return t;\n" +
        "        }\n" +
        "      },\n" +
        "      logger: { info: function(){}, warn: function(){}, error: function(){} },\n" +
        "      on: function(){ throw new Error('本版本不支持 ctx.on 事件订阅'); },\n" +
        "      plugin: function(){ throw new Error('本版本不支持 ctx.plugin 子插件'); },\n" +
        "      service: function(){ throw new Error('本版本不支持 ctx.service 自定义服务'); },\n" +
        "      effect: function(){ throw new Error('本版本不支持 ctx.effect'); }\n" +
        "    };\n" +
        "  }\n" +
        "\n" +
        "  // 加载一个已做 ESM 改写的插件源码；返回 {ok, tools, error}\n" +
        "  window.__load = function(id, src, opts){\n" +
        "    reg[id] = [];\n" +
        "    try {\n" +
        "      var fn = new Function('module','exports','require','__mod',\n" +
        "        '\"use strict\";\\n' + src +\n" +
        "        '\\n;return {apply: typeof apply === \"function\" ? apply : null,' +\n" +
        "        ' name: typeof name === \"string\" ? name : \"\",' +\n" +
        "        ' plugin: (module.exports && (module.exports.apply || module.exports.default)) ||' +\n" +
        "        '         (typeof __default !== \"undefined\" ? __default : null)};');\n" +
        "      var module = { exports: {} };\n" +
        "      var r = fn(module, module.exports, window.__mod, window.__mod) || {};\n" +
        "      var api = r.apply ? { apply: r.apply } : r.plugin;\n" +
        "      if (!api || typeof api.apply !== 'function') {\n" +
        "        throw new Error('没有导出 apply 函数（需要 export function apply(ctx)）');\n" +
        "      }\n" +
        "      var ctx = ctxFor(id);\n" +
        "      var ret = api.apply(ctx);\n" +
        "      // apply 允许是 async，这里只等同步注册；异步注册的工具下个版本再说\n" +
        "      if (ret && typeof ret.then === 'function') { ret.catch(function(){}); }\n" +
        "      return JSON.stringify({ ok: true, name: r.name || '', tools: reg[id].length });\n" +
        "    } catch (e) {\n" +
        "      window.__errors[id] = String(e && e.message || e);\n" +
        "      return JSON.stringify({ ok: false, error: window.__errors[id] });\n" +
        "    }\n" +
        "  };\n" +
        "\n" +
        "  window.__describe = function(id){\n" +
        "    var list = reg[id] || [];\n" +
        "    return JSON.stringify(list.map(function(t){\n" +
        "      return { name: t.name, description: t.description, parameters: t.parameters };\n" +
        "    }));\n" +
        "  };\n" +
        "\n" +
        "  // 执行工具；返回 Promise，异常转成 {ok:false,error}\n" +
        "  window.__call = function(id, tool, args){\n" +
        "    return Promise.resolve().then(function(){\n" +
        "      var list = reg[id] || [], hit = null;\n" +
        "      for (var i = 0; i < list.length; i++) if (list[i].name === tool) hit = list[i];\n" +
        "      if (!hit) throw new Error('工具不存在: ' + tool);\n" +
        "      return hit.execute(args || {});\n" +
        "    }).then(function(v){\n" +
        "      if (typeof v === 'string') return v;\n" +
        "      if (v === undefined) return 'ok';\n" +
        "      try { return JSON.stringify(v); } catch (e) { return String(v); }\n" +
        "    }).catch(function(e){\n" +
        "      return JSON.stringify({ ok: false, error: String(e && e.message || e) });\n" +
        "    });\n" +
        "  };\n" +
        "\n" +
        "  window.__unload = function(id){ delete reg[id]; delete window.__errors[id]; };\n" +
        "})();\n";
}
