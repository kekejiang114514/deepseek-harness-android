#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 AndroidManifest.xml（二进制 AXML），从 app_def 读取 App 定义。

用法：
    manifest_gen.py <输出路径> <app_def模块或函数名>

    app_def 可以是：
      - 'dshandroid' -> app_def.build_dshandroid_app()
      - 'github'   -> app_def.build_github_app()
      - 任意 'module:func'，如 'myapp:build_my_app'
默认 'dshandroid'。
"""
import sys, os, importlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml_build as B
import app_def as AD
import os

PUBLIC_XML = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'public.xml')

TYPE_STR = AD.TYPE_STR
TYPE_REF = AD.TYPE_REF
TYPE_INT_DEC = AD.TYPE_INT_DEC
TYPE_INT_HEX = AD.TYPE_INT_HEX
TYPE_BOOL = AD.TYPE_BOOL

A = B.Attr
E = B.Elem

ANDROID_NS = 'http://schemas.android.com/apk/res/android'


def an(name, vtype, value):
    return A('android', name, vtype, value)


def resolve_app(spec):
    """把 'dshandroid' / 'mod:func' 解析成 AppDef。"""
    if spec in (None, ''):
        return AD.build_app()
    if ':' in spec:
        mod_name, func_name = spec.split(':', 1)
        mod = importlib.import_module(mod_name)
        return getattr(mod, func_name)()
    return getattr(AD, 'build_%s_app' % spec)()


def elem_from_component(c):
    """把 ComponentDef 转成 axml 的 Elem（含子元素树）。"""
    attrs = [an('name', TYPE_STR, c.name), an('exported', TYPE_BOOL, c.exported)]
    for name, vtype, value in c.attrs:
        attrs.append(an(name, vtype, value))
    children = [_elem_from_tuple(t) for t in c.children]
    return E(c.kind, attrs=attrs, children=children)


def _elem_from_tuple(t):
    """(name, [(attr, type, value)...], [(child tuple)...]) -> Elem"""
    name, attrs, children = t
    return E(name,
             attrs=[an(a, vtype, v) for a, vtype, v in attrs],
             children=[_elem_from_tuple(c) for c in children])


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'AndroidManifest.xml'
    spec = sys.argv[2] if len(sys.argv) > 2 else 'dshandroid'
    app = resolve_app(spec)

    # ---- application 节点（属性顺序与原脚本对齐：label,icon,roundIcon,allowBackup,supportsRtl,usesCleartextTraffic,hardwareAccelerated）----
    app_attrs = [
        an('label', TYPE_STR, app.label),
    ]
    if app.icon_attr:
        app_attrs.append(an(app.icon_attr[0], TYPE_REF, app.icon_attr[1]))
    if app.round_icon_attr:
        app_attrs.append(an(app.round_icon_attr[0], TYPE_REF, app.round_icon_attr[1]))
    app_attrs += [
        an('allowBackup', TYPE_BOOL, False),
        an('supportsRtl', TYPE_BOOL, True),
        an('usesCleartextTraffic', TYPE_BOOL, app.uses_cleartext),
        an('hardwareAccelerated', TYPE_BOOL, True),
    ]
    for name, vtype, value in getattr(app, 'extra_app_attrs', []):
        app_attrs.append(an(name, vtype, value))

    application = E('application', attrs=app_attrs,
                    children=[elem_from_component(c) for c in app.components])

    # ---- manifest 根节点 ----
    root_attrs = [
        A(None, 'package', TYPE_STR, app.package),
        an('versionCode', TYPE_INT_DEC, app.version_code),
        an('versionName', TYPE_STR, app.version_name),
    ]
    manifest_children = [
        E('uses-sdk', attrs=[
            an('minSdkVersion', TYPE_INT_DEC, app.min_sdk),
            an('targetSdkVersion', TYPE_INT_DEC, app.target_sdk),
        ]),
    ]
    for p in app.permissions:
        manifest_children.append(E('uses-permission', attrs=[an('name', TYPE_STR, p)]))
    manifest_children.append(application)
    manifest_children.extend(app.extra_manifest_roots)

    root = E('manifest', attrs=root_attrs, children=manifest_children)

    ids = B.load_attr_ids(PUBLIC_XML)
    data = B.build([root], [('android', ANDROID_NS)], ids, {'android': 1})
    with open(out, 'wb') as f:
        f.write(data)
    sys.stderr.write('manifest written: %d bytes (%s)\n' % (len(data), spec))
    return 0


if __name__ == '__main__':
    sys.exit(main())
