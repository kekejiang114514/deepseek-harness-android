#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 res/xml 下的两个配置（二进制 AXML）。

放在 res/xml 的资源必须是编译后的 XML（AXML），纯文本 XML 系统解析不了：
  * network_security_config —— 网络安全配置
  * backup_rules           —— full-backup-content 排除规则
两者都是无命名空间的普通元素树，这里用同一个 AXML 写出器生成。

用法：python3 make_res_xml.py <输出目录>
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml_build as B

PUBLIC_XML = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'public.xml')


def write(elems, out_path, ns=None):
    ids = B.load_attr_ids(PUBLIC_XML)
    data = B.build(elems, ns or [], ids, {} if not ns else {'android': 1})
    with open(out_path, 'wb') as f:
        f.write(data)
    sys.stderr.write('%s written: %d bytes\n' % (os.path.basename(out_path), len(data)))


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else '/tmp'
    os.makedirs(out_dir, exist_ok=True)

    # 网络安全：默认禁明文，只放行本机回环
    net = B.Elem('network-security-config', children=[
        B.Elem('base-config', attrs=[B.Attr(None, 'cleartextTrafficPermitted', B.TYPE_STR, 'false')]),
        B.Elem('domain-config', attrs=[B.Attr(None, 'cleartextTrafficPermitted', B.TYPE_STR, 'true')],
               children=[
                   B.Elem('domain', attrs=[B.Attr(None, 'includeSubdomains', B.TYPE_STR, 'false')],
                          text='127.0.0.1'),
                   B.Elem('domain', attrs=[B.Attr(None, 'includeSubdomains', B.TYPE_STR, 'false')],
                          text='localhost'),
               ]),
    ])
    write([net], os.path.join(out_dir, 'network_security_config.xml'))

    # 备份排除：四类域全部排除
    excl = []
    for domain in ('sharedpref', 'file', 'database', 'external'):
        excl.append(B.Elem('exclude', attrs=[
            B.Attr(None, 'domain', B.TYPE_STR, domain),
            B.Attr(None, 'path', B.TYPE_STR, '.'),
        ]))
    backup = B.Elem('full-backup-content', children=excl)
    write([backup], os.path.join(out_dir, 'backup_rules.xml'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
