#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 res/xml/accessibility_service_config.xml（二进制 AXML）。

这个文件被 manifest 里服务的 meta-data 引用，系统「无障碍」设置读取它来决定
服务能做什么：
  * canRetrieveWindowContent —— 没有它就拿不到窗口节点树，a11y.dump 全是空
  * canPerformGestures       —— 没有它 dispatchGesture 直接被拒，点不动屏幕
所以这两项必须写在配置里，光靠 onServiceConnected 里 setServiceInfo 补不上。

用法：python3 make_a11y_xml.py <输出路径>
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml_build as B

PUBLIC_XML = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'public.xml')

# AccessibilityEvent.TYPES_ALL_MASK
EVENT_TYPES_ALL = -1            # 0xFFFFFFFF
# AccessibilityServiceInfo.FEEDBACK_GENERIC
FEEDBACK_GENERIC = 0x10
# flagDefault(0x1) | flagIncludeNotImportantViews(0x2)
# | flagReportViewIds(0x10) | flagRetrieveInteractiveWindows(0x40)
FLAGS = 0x1 | 0x2 | 0x10 | 0x40


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '/tmp/accessibility_service_config.xml'
    ids = B.load_attr_ids(PUBLIC_XML)

    root = B.Elem('accessibility-service', attrs=[
        B.Attr('android', 'accessibilityEventTypes', B.TYPE_INT_HEX, EVENT_TYPES_ALL),
        B.Attr('android', 'accessibilityFeedbackType', B.TYPE_INT_HEX, FEEDBACK_GENERIC),
        B.Attr('android', 'accessibilityFlags', B.TYPE_INT_HEX, FLAGS),
        B.Attr('android', 'canRetrieveWindowContent', B.TYPE_INT_BOOLEAN, True),
        B.Attr('android', 'canPerformGestures', B.TYPE_INT_BOOLEAN, True),
        B.Attr('android', 'notificationTimeout', B.TYPE_INT_DEC, 100),
    ])

    data = B.build([root], [('android', B.ANDROID_NS)], ids, {'android': 1})
    with open(out, 'wb') as f:
        f.write(data)
    sys.stderr.write('a11y xml written: %d bytes\n' % len(data))
    return 0


if __name__ == '__main__':
    sys.exit(main())
