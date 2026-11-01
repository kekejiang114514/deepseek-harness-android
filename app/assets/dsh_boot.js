/*
 * DShAndroid 的 DSH 插件引导脚本。
 *
 * 由 JsHost 在 WebView 初始化完成后注入。作用是把 Cordis 插件那套接口
 * 在浏览器环境里模拟出来，让 DSH 的「工具类插件」能直接跑：
 *
 *   - __mod(name)   模块解析（fs / path / os / process / util / url / crypto）
 *   - defineTool()  原样返回工具声明
 *   - ctx.tools.register() 收集工具
 *   - ctx.on / ctx.plugin / ctx.service  明确抛错，不静默失效
 *
 * fs 由 Java 侧 DshFs 桥接，路径被限制在该插件自己的数据目录内。
 */
(function () {
  var reg = {};
  var mods = {};

  // ---------------------------------------------------------------- 模块

  function parse(r) {
    try { return JSON.parse(r); } catch (e) { return { ok: false, error: '桥接返回异常' }; }
  }

  // 当前正在跑哪个插件：__load 与 __call 都会先设好，
  // fs 调用据此落到该插件自己的数据目录（插件之间互相看不到）。
  function pid() { return window.__currentPlugin || '_default'; }

  function bridge(method, args) {
    if (typeof DshFs === 'undefined') {
      throw new Error('文件系统桥不可用（插件可能没有被正确加载）');
    }
    var fn = DshFs[method];
    if (typeof fn !== 'function') throw new Error('桥接方法缺失: ' + method);
    return parse(fn.apply(DshFs, [pid()].concat(args)));
  }

  function mkFs() {
    function need(r) {
      if (!r.ok) throw new Error(r.error || 'fs 操作失败');
      return r.data;
    }
    var fs = {
      readFileSync: function (p) { return need(bridge('readText', [String(p)])); },
      writeFileSync: function (p, d) { need(bridge('writeText', [String(p), String(d)])); },
      appendFileSync: function (p, d) { need(bridge('append', [String(p), String(d)])); },
      existsSync: function (p) {
        var r = bridge('exists', [String(p)]);
        return !!(r.ok && r.data);
      },
      mkdirSync: function (p) { need(bridge('mkdir', [String(p)])); },
      readdirSync: function (p) { return need(bridge('list', [String(p)])); },
      statSync: function (p) { return need(bridge('stat', [String(p)])); },
      unlinkSync: function (p) { need(bridge('remove', [String(p)])); },
      rmSync: function (p) { need(bridge('remove', [String(p)])); },
      rmdirSync: function (p) { need(bridge('remove', [String(p)])); },
      realpathSync: function (p) { return String(p); },
      constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 }
    };

    function cbify(fn) {
      return function () {
        var args = Array.prototype.slice.call(arguments);
        var cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
        var p = new Promise(function (resolve) {
          try { resolve(fn.apply(null, args)); }
          catch (e) { throw e; }
        });
        if (cb) {
          p.then(function (v) { cb(null, v); }, function (e) { cb(e); });
        }
        return p;
      };
    }

    fs.readFile = cbify(function (p) { return fs.readFileSync(p); });
    fs.writeFile = cbify(function (p, d) { return fs.writeFileSync(p, d); });
    fs.appendFile = cbify(function (p, d) { return fs.appendFileSync(p, d); });
    fs.readdir = cbify(function (p) { return fs.readdirSync(p); });
    fs.stat = cbify(function (p) { return fs.statSync(p); });
    fs.mkdir = cbify(function (p) { return fs.mkdirSync(p); });
    fs.unlink = cbify(function (p) { return fs.unlinkSync(p); });
    fs.exists = cbify(function (p) { return fs.existsSync(p); });

    fs.promises = {
      readFile: function (p) { return Promise.resolve(fs.readFileSync(p)); },
      writeFile: function (p, d) { return Promise.resolve(fs.writeFileSync(p, d)); },
      appendFile: function (p, d) { return Promise.resolve(fs.appendFileSync(p, d)); },
      readdir: function (p) { return Promise.resolve(fs.readdirSync(p)); },
      stat: function (p) { return Promise.resolve(fs.statSync(p)); },
      mkdir: function (p) { return Promise.resolve(fs.mkdirSync(p)); },
      unlink: function (p) { return Promise.resolve(fs.unlinkSync(p)); },
      rm: function (p) { return Promise.resolve(fs.rmSync(p)); },
      access: function (p) {
        return fs.existsSync(p) ? Promise.resolve() : Promise.reject(new Error('ENOENT'));
      }
    };
    return fs;
  }

  function mkPath() {
    function norm(p) {
      var abs = p.charAt(0) === '/';
      var parts = p.split('/');
      var out = [];
      for (var i = 0; i < parts.length; i++) {
        var s = parts[i];
        if (!s || s === '.') continue;
        if (s === '..') { if (out.length && out[out.length - 1] !== '..') out.pop(); else if (!abs) out.push('..'); }
        else out.push(s);
      }
      return (abs ? '/' : '') + out.join('/');
    }
    var path = {
      sep: '/',
      delimiter: ':',
      normalize: norm,
      isAbsolute: function (p) { return String(p).charAt(0) === '/'; },
      join: function () {
        var a = Array.prototype.slice.call(arguments).filter(function (x) { return x != null && x !== ''; });
        return norm(a.join('/')) || '.';
      },
      resolve: function () {
        var a = Array.prototype.slice.call(arguments).filter(function (x) { return x != null && x !== ''; });
        var abs = a.filter(function (x) { return String(x).charAt(0) === '/' || /^[a-zA-Z]:/.test(String(x)); });
        var rest = a.filter(function (x) { return abs.indexOf(x) < 0; });
        var base = abs.length ? abs[abs.length - 1] : (typeof DshFs !== 'undefined' ? DshFs.cwd(pid()) : '/');
        return norm(String(base) + '/' + rest.join('/'));
      },
      dirname: function (p) {
        var s = String(p).replace(/\/+$/, '');
        var i = s.lastIndexOf('/');
        if (i < 0) return '.';
        if (i === 0) return '/';
        return s.substring(0, i);
      },
      basename: function (p, ext) {
        var s = String(p).replace(/\/+$/, '');
        var i = s.lastIndexOf('/');
        var b = i < 0 ? s : s.substring(i + 1);
        if (ext && b.slice(-ext.length) === ext) b = b.slice(0, -ext.length);
        return b;
      },
      extname: function (p) {
        var b = path.basename(p);
        var i = b.lastIndexOf('.');
        return i <= 0 ? '' : b.substring(i);
      },
      relative: function (from, to) {
        var f = norm(from).split('/').filter(Boolean);
        var t = norm(to).split('/').filter(Boolean);
        while (f.length && t.length && f[0] === t[0]) { f.shift(); t.shift(); }
        return f.map(function () { return '..'; }).concat(t).join('/');
      }
    };
    path.posix = path;
    return path;
  }

  function mkOs() {
    return {
      EOL: '\n',
      platform: function () { return 'android'; },
      type: function () { return 'Linux'; },
      arch: function () { return 'arm64'; },
      release: function () { return ''; },
      homedir: function () { return (typeof DshFs !== 'undefined' ? DshFs.cwd(pid()) : '/'); },
      tmpdir: function () { return (typeof DshFs !== 'undefined' ? DshFs.cwd(pid()) : '/'); },
      cpus: function () { return []; },
      totalmem: function () { return 0; },
      freemem: function () { return 0; },
      hostname: function () { return 'android'; },
      userInfo: function () { return { username: 'user', homedir: osMod.homedir() }; }
    };
  }

  function mkProcess() {
    var root = typeof DshFs !== 'undefined' ? DshFs.cwd(pid()) : '/';
    return {
      env: {},
      argv: [],
      platform: 'android',
      arch: 'arm64',
      version: 'v20.0.0',
      versions: { node: '20.0.0' },
      pid: 1,
      cwd: function () { return typeof DshFs !== 'undefined' ? DshFs.cwd(pid()) : root; },
      chdir: function () {},
      nextTick: function (fn) { setTimeout(fn, 0); },
      exit: function () { throw new Error('插件调用了 process.exit，已阻止'); },
      on: function () {},
      stdout: { write: function (s) { return true; } },
      stderr: { write: function (s) { return true; } }
    };
  }

  var fsMod = mkFs();
  var pathMod = mkPath();
  var osMod = mkOs();
  var processMod = mkProcess();

  mods['fs'] = fsMod;
  mods['node:fs'] = fsMod;
  mods['fs/promises'] = fsMod.promises;
  mods['node:fs/promises'] = fsMod.promises;
  mods['path'] = pathMod;
  mods['node:path'] = pathMod;
  mods['os'] = osMod;
  mods['node:os'] = osMod;
  mods['process'] = processMod;
  mods['node:process'] = processMod;
  mods['util'] = {
    format: function () { return Array.prototype.slice.call(arguments).join(' '); },
    inspect: function (o) { try { return JSON.stringify(o, null, 2); } catch (e) { return String(o); } },
    inherits: function (a, b) { a.prototype = Object.create(b.prototype); a.prototype.constructor = a; },
    types: { isArray: Array.isArray, isDate: function (x) { return x instanceof Date; } }
  };
  mods['node:util'] = mods['util'];
  mods['url'] = {
    URL: window.URL,
    URLSearchParams: window.URLSearchParams,
    parse: function (u) { var x = new URL(u, 'https://localhost/'); return { href: x.href, pathname: x.pathname, query: x.search, host: x.host }; }
  };
  mods['node:url'] = mods['url'];
  mods['crypto'] = {
    randomUUID: function () {
      try { return crypto.randomUUID(); } catch (e) {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
          var r = Math.random() * 16 | 0;
          return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
        });
      }
    },
    randomBytes: function (n) {
      var a = new Uint8Array(n);
      crypto.getRandomValues(a);
      return a;
    }
  };
  mods['node:crypto'] = mods['crypto'];

  // DSH 自己的两个包：defineTool 是声明式身份函数，类型来自 cordis
  mods['@deepseek-ai/dsh-tools'] = { defineTool: function (s) { return s; } };
  mods['@deepseek-ai/cordis'] = {};

  // 明确不支持的模块：提前给出可读的原因，而不是让插件在调用处崩得莫名其妙
  var banned = {
    'child_process': '启动子进程',
    'node:child_process': '启动子进程',
    'net': '原始 TCP 套接字',
    'node:net': '原始 TCP 套接字',
    'dgram': 'UDP',
    'node:dgram': 'UDP',
    'http': '启动 HTTP 服务（发请求请用 fetch）',
    'node:http': '启动 HTTP 服务（发请求请用 fetch）',
    'https': '启动 HTTPS 服务（发请求请用 fetch）',
    'node:https': '启动 HTTPS 服务（发请求请用 fetch）',
    'worker_threads': '多线程 worker',
    'node:worker_threads': '多线程 worker',
    'vm': '动态脚本编译',
    'node:vm': '动态脚本编译',
    'cluster': '多进程',
    'node:cluster': '多进程'
  };

  // 强制兼容用：未实现的模块返回一个「万能 stub」。
  // 任何属性、任何调用都返回另一个 stub，不会抛错，插件可以继续加载完。
  var degraded = {};
  var handlers = {};    // 事件名 -> [{id, fn}]
  var services = {};    // 服务名 -> 实现
  var commands = {};    // "插件id/命令名" -> {plugin, name, desc, fn}
  function noteDegraded(id, what) {
    var list = degraded[id] = degraded[id] || [];
    if (list.indexOf(what) < 0) list.push(what);
  }
  function stub(name) {
    var f = function () { return stub(name); };
    try {
      return new Proxy(f, {
        get: function (t, k) {
          if (k === 'then') return undefined;          // 别被当成 Promise
          if (k === '__esModule') return true;
          if (k === Symbol.toPrimitive) return function () { return ''; };
          if (k === 'toString') return function () { return ''; };
          if (k === 'valueOf') return function () { return 0; };
          if (k === 'default') return stub(name);
          return stub(name);
        },
        apply: function () { return stub(name); },
        construct: function () { return stub(name); }
      });
    } catch (e) {
      return f;                                       // 老内核没有 Proxy 时退化成函数
    }
  }

  window.__mod = function (name) {
    var n = String(name || '');
    if (mods[n]) return mods[n];
    noteDegraded(window.__currentPlugin || '_default', n);
    return stub(n);
  };
  window.process = processMod;
  window.defineTool = function (s) { return s; };

  // ---------------------------------------------------------------- 插件

  function ctxFor(id) {
    return {
      tools: {
        register: function (t) {
          if (!t || !t.name) throw new Error('工具缺少 name');
          if (typeof t.execute !== 'function') throw new Error('工具 ' + t.name + ' 缺少 execute');
          (reg[id] = reg[id] || []).push({
            name: String(t.name),
            description: String(t.description || ''),
            parameters: t.parameters || {},
            execute: t.execute
          });
          return t;
        }
      },
      logger: { info: function () {}, warn: function () {}, error: function () {}, debug: function () {} },
      // 事件订阅：真正挂了监听，宿主在对应时机 __emit
      on: function (name, fn) {
        if (typeof fn !== 'function') return function () {};
        (handlers[name] = handlers[name] || []).push({ id: id, fn: fn });
        return function () {
          var l = handlers[name] || [];
          for (var i = l.length - 1; i >= 0; i--) if (l[i].fn === fn) l.splice(i, 1);
        };
      },
      // 服务：注册后同进程内所有插件都能 ctx.get 到
      service: function (name, impl) {
        services[String(name)] = impl;
        return impl;
      },
      get: function (name) { return services[String(name)] || stub(String(name)); },
      // 命令：输入框里以 /名字 触发
      command: function (name, desc, fn) {
        if (typeof desc === 'function') { fn = desc; desc = ''; }
        if (typeof fn !== 'function') return function () {};
        commands[id + '/' + name] = { plugin: id, name: String(name), desc: String(desc || ''), fn: fn };
        return function () { delete commands[id + '/' + name]; };
      },
      set: function () { return function () {}; },
      effect: function () { return function () {}; },
      plugin: function () { noteDegraded(id, 'ctx.plugin'); return function () {}; },
      // 以下是没有等价渲染能力的面板类 API：保留调用入口，插件不会崩
      slots: stub('ctx.slots'),
      client: stub('ctx.client'),
      render: function () { noteDegraded(id, 'ctx.render'); return null; },
      timer: { setTimeout: setTimeout, setInterval: setInterval, clearTimeout: clearTimeout, clearInterval: clearInterval }
    };
  }

  window.__errors = {};

  window.__load = function (id, src) {
    reg[id] = [];
    window.__currentPlugin = id;
    try {
      var fn = new Function('module', 'exports', 'require', '__mod',
        '"use strict";\n' + src +
        '\n;return {apply: typeof apply === "function" ? apply : null,' +
        ' name: typeof name === "string" ? name : "",' +
        ' plugin: (module.exports && (module.exports.apply || module.exports.default)) ||' +
        '         (typeof __default !== "undefined" ? __default : null)};');
      var module = { exports: {} };
      var req = function (n) { return window.__mod(n); };
      var r = fn(module, module.exports, req, req) || {};
      var api = r.apply ? { apply: r.apply } : r.plugin;
      if (!api || typeof api.apply !== 'function') {
        throw new Error('没有导出 apply 函数（需要 export function apply(ctx)）');
      }
      var ret;
      var applyError = null;
      try {
        ret = api.apply(ctxFor(id));
      } catch (e) {
        // 强制兼容：apply 后半段崩了，也保留已经注册成功的工具
        applyError = String((e && e.message) || e);
        noteDegraded(id, 'apply: ' + applyError);
      }
      if (ret && typeof ret.then === 'function') { ret.catch(function () {}); }
      var tools = (reg[id] || []).length;
      if (tools === 0 && applyError) throw new Error(applyError);
      return JSON.stringify({
        ok: true, name: r.name || '', tools: tools, applyError: applyError
      });
    } catch (e) {
      window.__errors[id] = String((e && e.message) || e);
      return JSON.stringify({ ok: false, error: window.__errors[id] });
    }
  };

  /** 宿主触发事件；返回各监听器返回的非空字符串（可直接并入工具结果或提示词）。 */
  window.__emit = function (eventName, payloadJson) {
    var list = handlers[eventName] || [];
    var out = [];
    var arg = null;
    try { arg = JSON.parse(payloadJson || 'null'); } catch (e) { arg = payloadJson; }
    for (var i = 0; i < list.length; i++) {
      try {
        var r = list[i].fn(arg);
        if (typeof r === 'string' && r.length > 0) out.push(r);
      } catch (e) {
        noteDegraded(list[i].id, 'handler(' + eventName + '): ' + ((e && e.message) || e));
      }
    }
    return JSON.stringify(out);
  };

  /** 所有插件注册的命令。 */
  window.__commands = function () {
    var out = [];
    for (var k in commands) {
      if (Object.prototype.hasOwnProperty.call(commands, k)) {
        out.push({ plugin: commands[k].plugin, name: commands[k].name, desc: commands[k].desc });
      }
    }
    return JSON.stringify(out);
  };

  window.__runCommand = function (pluginId, name, argsJson) {
    var c = commands[pluginId + '/' + name];
    if (!c) return JSON.stringify({ ok: false, error: '命令不存在' });
    var arg = null;
    try { arg = JSON.parse(argsJson || 'null'); } catch (e) { arg = argsJson; }
    try {
      var r = c.fn(arg);
      if (r && typeof r.then === 'function') {
        return r.then(function (v) { return JSON.stringify({ ok: true, value: v == null ? '' : String(v) }); },
                      function (e) { return JSON.stringify({ ok: false, error: String((e && e.message) || e) }); });
      }
      return JSON.stringify({ ok: true, value: r == null ? '' : String(r) });
    } catch (e) {
      return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
    }
  };

  window.__degraded = function (id) {
    return JSON.stringify(degraded[id] || []);
  };

  window.__describe = function (id) {
    var list = reg[id] || [];
    return JSON.stringify(list.map(function (t) {
      return { name: t.name, description: t.description, parameters: t.parameters };
    }));
  };

  window.__call = function (id, tool, args) {
    window.__currentPlugin = id;
    return Promise.resolve().then(function () {
      var list = reg[id] || [], hit = null;
      for (var i = 0; i < list.length; i++) if (list[i].name === tool) hit = list[i];
      if (!hit) throw new Error('工具不存在: ' + tool);
      return hit.execute(args || {});
    }).then(function (v) {
      if (typeof v === 'string') return v;
      if (v === undefined) return 'ok';
      try { return JSON.stringify(v); } catch (e) { return String(v); }
    }).catch(function (e) {
      return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
    });
  };

  window.__unload = function (id) { delete reg[id]; delete window.__errors[id]; };
})();
