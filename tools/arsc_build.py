#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Android resources.arsc（单包），从 app_def 读取包名与资源列表。

支持任意数量的资源（mipmap 图标 + xml 配置等，按 app_def 里的顺序分配 ID）。
用法：
    arsc_build.py <输出路径> <app_def名>
    app_def 名：'dshandroid' / 'mod:func'，默认 'dshandroid'。
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app_def as AD

PUBLIC_XML = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'public.xml')

RES_STRING_POOL = 0x0001
RES_TABLE = 0x0002
RES_TABLE_PACKAGE = 0x0200
RES_TABLE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC = 0x0202

UTF8_FLAG = 1 << 8
CONFIG_SIZE = 64
PKG_HEADER_SIZE = 288
TYPE_HEADER_SIZE = 20 + CONFIG_SIZE


def enc_len8(v):
    if v > 0x7F:
        return bytes([0x80 | (v >> 8), v & 0xFF])
    return bytes([v])


def enc_len16(v):
    if v > 0x7FFF:
        return struct.pack('<HH', 0x8000 | (v >> 16), v & 0xFFFF)
    return struct.pack('<H', v)


def build_string_pool(strings, utf8=True):
    count = len(strings)
    offsets = []
    data = bytearray()
    if utf8:
        for s in strings:
            b = s.encode('utf-8')
            offsets.append(len(data))
            data += enc_len8(len(s.encode('utf-16-le')) // 2) + enc_len8(len(b)) + b + b'\x00'
        flags = UTF8_FLAG
    else:
        for s in strings:
            units = s.encode('utf-16-le')
            offsets.append(len(data))
            data += enc_len16(len(units) // 2) + units + b'\x00\x00'
        flags = 0

    str_start = 28 + 4 * count
    pad = (-str_start) % 4
    str_start += pad
    total = str_start + len(data)
    total_pad = (-total) % 4
    total += total_pad

    out = bytearray()
    out += struct.pack('<HHIIIIII', RES_STRING_POOL, 28, total, count, 0, flags, str_start, 0)
    for o in offsets:
        out += struct.pack('<I', o)
    out += b'\x00' * pad
    out += data
    out += b'\x00' * total_pad
    assert len(out) == total, (len(out), total)
    return bytes(out)


def build_config(density=0):
    cfg = bytearray(CONFIG_SIZE)
    struct.pack_into('<I', cfg, 0, CONFIG_SIZE)
    if density:
        struct.pack_into('<H', cfg, 14, density)
    return bytes(cfg)


def build_arsc(package_name, type_name, res_name, file_path, density=0, pkg_id=0x7F):
    """单资源：type_name/res_name -> file_path（如 mipmap/ic_launcher -> res/mipmap/ic_launcher.png）"""
    global_pool = build_string_pool([file_path], utf8=True)
    type_pool = build_string_pool([type_name], utf8=False)
    key_pool = build_string_pool([res_name], utf8=True)

    spec = bytearray()
    spec += struct.pack('<HHIBBHI', RES_TABLE_TYPE_SPEC, 16, 16 + 4, 1, 0, 0, 1)
    spec += struct.pack('<I', 0)

    entry = struct.pack('<HHI', 8, 0, 0)
    entry += struct.pack('<HBBI', 8, 0, 0x03, 0)
    entries_start = TYPE_HEADER_SIZE + 4
    type_size = entries_start + len(entry)
    typ = bytearray()
    typ += struct.pack('<HHIBBHII', RES_TABLE_TYPE, TYPE_HEADER_SIZE, type_size, 1, 0, 0, 1, entries_start)
    typ += build_config(density)
    typ += struct.pack('<I', 0)
    typ += entry

    ts_off = PKG_HEADER_SIZE
    ks_off = ts_off + len(type_pool)
    pkg_size = ks_off + len(key_pool) + len(spec) + len(typ)

    hdr = bytearray(PKG_HEADER_SIZE)
    struct.pack_into('<HHII', hdr, 0, RES_TABLE_PACKAGE, PKG_HEADER_SIZE, pkg_size, pkg_id)
    nm = package_name.encode('utf-16-le')
    assert len(nm) <= 256, 'package 名过长: %s' % package_name
    hdr[12:12 + len(nm)] = nm
    struct.pack_into('<IIIII', hdr, 268, ts_off, 0, ks_off, 0, 0)

    pkg = bytes(hdr) + type_pool + key_pool + bytes(spec) + bytes(typ)
    assert len(pkg) == pkg_size

    total = 12 + len(global_pool) + len(pkg)
    root = struct.pack('<HHII', RES_TABLE, 12, total, 1)
    return root + global_pool + pkg



# ---------------- 多资源支持 ----------------

def build_arsc_multi(package_name, resources, pkg_id=0x7F):
    """多资源版本：resources = [(type_name, res_name, file_path), ...]。

    类型 id 按 type_name 首次出现的顺序分配（1 开始），条目 id 按同类型内
    的先后顺序分配。所以 icon 必须排在第一位，才能拿到 0x7F010000。
    """
    order = []
    groups = {}
    for t, n, p in resources:
        if t not in groups:
            groups[t] = []
            order.append(t)
        groups[t].append((n, p))

    keys = []
    paths = []
    types = []          # [(type_name, [(key_index, global_path_index), ...])]
    for t in order:
        ents = []
        for n, p in groups[t]:
            if n not in keys:
                keys.append(n)
            paths.append(p)
            ents.append((keys.index(n), len(paths) - 1))
        types.append((t, ents))

    # string 类型的资源，池子里放的是字面量而不是文件路径
    global_pool = build_string_pool(paths, utf8=True)
    type_pool = build_string_pool(order, utf8=False)
    key_pool = build_string_pool(keys, utf8=True)

    chunks = bytearray()
    for tid, (tname, ents) in enumerate(types, start=1):
        count = len(ents)
        spec_size = 16 + 4 * count
        spec = struct.pack('<HHIBBHI', RES_TABLE_TYPE_SPEC, 16, spec_size, tid, 0, 0, count)
        spec += struct.pack('<I', 0) * count

        entry_data = bytearray()
        offsets = []
        for key_idx, global_idx in ents:
            offsets.append(len(entry_data))
            entry_data += struct.pack('<HHI', 8, 0, key_idx)
            entry_data += struct.pack('<HBBI', 8, 0, 0x03, global_idx)

        entries_start = TYPE_HEADER_SIZE + 4 * count
        type_size = entries_start + len(entry_data)
        typ = bytearray()
        typ += struct.pack('<HHIBBHII', RES_TABLE_TYPE, TYPE_HEADER_SIZE, type_size,
                           tid, 0, 0, count, entries_start)
        typ += build_config(0)
        for o in offsets:
            typ += struct.pack('<I', o)
        typ += entry_data
        chunks += spec + typ

    ts_off = PKG_HEADER_SIZE
    ks_off = ts_off + len(type_pool)
    pkg_size = ks_off + len(key_pool) + len(chunks)

    hdr = bytearray(PKG_HEADER_SIZE)
    struct.pack_into('<HHII', hdr, 0, RES_TABLE_PACKAGE, PKG_HEADER_SIZE, pkg_size, pkg_id)
    nm = package_name.encode('utf-16-le')
    assert len(nm) <= 256, 'package 名过长: %s' % package_name
    hdr[12:12 + len(nm)] = nm
    struct.pack_into('<IIIII', hdr, 268, ts_off, 0, ks_off, 0, 0)

    pkg = bytes(hdr) + type_pool + key_pool + bytes(chunks)
    assert len(pkg) == pkg_size, (len(pkg), pkg_size)

    total = 12 + len(global_pool) + len(pkg)
    root = struct.pack('<HHII', RES_TABLE, 12, total, 1)
    return root + global_pool + pkg

def resolve_app(spec):
    """解析 app 定义：'模块:函数' 或直接写函数名（都在 app_def 里）。"""
    if spec in (None, ''):
        return AD.build_app()
    if ':' in spec:
        import importlib
        mod_name, func_name = spec.split(':', 1)
        return getattr(importlib.import_module(mod_name), func_name)()
    return getattr(AD, 'build_%s_app' % spec)()


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'resources.arsc'
    spec = sys.argv[2] if len(sys.argv) > 2 else 'dshandroid'
    app = resolve_app(spec)

    # 取第一个 mipmap 资源作为图标（GitHub 客户端只有这一个）
    icon = None
    for r in app.resources:
        if r.type_name == 'mipmap':
            icon = r
            break
    if icon is None:
        raise ValueError('app %r 没有任何 mipmap 资源' % spec)

    # 多资源：mipmap 排第一（保证图标仍是 0x7F010000），其余按定义顺序
    res = [(icon.type_name, icon.res_name, icon.file_path)]
    for r in app.resources:
        if r is icon:
            continue
        res.append((r.type_name, r.res_name, r.file_path))
    data = build_arsc_multi(app.package, res)
    with open(out, 'wb') as f:
        f.write(data)
    sys.stderr.write('arsc written: %d bytes (%s)\n' % (len(data), spec))
    return 0


if __name__ == '__main__':
    sys.exit(main())
