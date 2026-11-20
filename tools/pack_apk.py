#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手工打包 APK（Android 兼容：无 data descriptor、无 zip64、stored 条目 4 字节对齐）。

用法：
    pack_apk.py out.apk [z|s]name=path ...
        z: DEFLATE 压缩（dex / xml 用）
        s: 原样存储（png / resources.arsc 用，顺便 4 字节对齐）
"""
import os
import sys
import zlib
import struct

PUBLIC_XML = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'public.xml')


def pack(entries, out_apk):
    with open(out_apk, 'wb') as f:
        central = []
        offset = 0
        for name, path, compress in entries:
            data = open(path, 'rb').read()
            nb = name.encode('utf-8')
            crc = zlib.crc32(data) & 0xffffffff
            if compress:
                co = zlib.compressobj(9, zlib.DEFLATED, -15)
                payload = co.compress(data) + co.flush()
                method = 8
            else:
                payload = data
                method = 0

            base = offset + 30 + len(nb)
            # PACK_NO_PAD=1：不写补齐 extra。用于验证「extra 字段是否被某些
            # 安装器拒绝」——这类问题在离线校验里完全看不出来。
            pad = 0 if (compress or os.environ.get('PACK_NO_PAD') == '1') else (-base) % 4
            if pad:
                # extra 字段最小 4 字节（id+size），不够就再补一整轮
                elen = pad if pad >= 4 else pad + 4
                # 头里写的是“负载长度”，不是含 4 字节头的总长
                extra = struct.pack('<HH', 0xD935, elen - 4) + b'\x00' * (elen - 4)
            else:
                extra = b''

            hdr_off = offset
            local = struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, 0, method, 0, 0,
                                crc, len(payload), len(data), len(nb), len(extra))
            f.write(local + nb + extra + payload)
            offset = hdr_off + len(local) + len(nb) + len(extra) + len(payload)
            # extra 必须同时写进中央目录：新系统的 zip 解析器按中央目录的
            # extra 长度推算数据偏移，本地头与中央目录不一致会把数据读歪，
            # 表现就是「包损坏、装不上」。
            central.append((nb, method, crc, len(payload), len(data), hdr_off, extra))

        cd_off = offset
        for nb, method, crc, csize, usize, hdr_off, extra in central:
            f.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, 0, method,
                                0, 0, crc, csize, usize, len(nb), len(extra), 0, 0, 0, 0,
                                hdr_off) + nb + extra)
            offset += 46 + len(nb) + len(extra)
        cd_size = offset - cd_off
        f.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central),
                            cd_size, cd_off, 0))


def parse(spec):
    comp = True
    if spec[:2] in ('z:', 's:'):
        comp = spec[0] == 'z'
        spec = spec[2:]
    name, _, path = spec.partition('=')
    return (name, path, comp)


def main():
    out = sys.argv[1]
    entries = [parse(a) for a in sys.argv[2:]]
    pack(entries, out)
    print('apk packed: %s (%d bytes, %d entries)' % (out, os.path.getsize(out), len(entries)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
