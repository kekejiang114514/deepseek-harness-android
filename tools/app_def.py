#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""App 定义（通用）：描述一个 App 的包名、组件、权限、版本、资源，供 manifest_gen 与 arsc_build 使用。

设计目标：让 manifest_gen.py / arsc_build.py 从"写死鲸鱼娘桌宠"变成"读任意 App 定义"。
每个 App 是一个 Python 函数 build_app()，返回 AppDef。
"""

# ---- 属性类型常量（与 axml_build.py 对齐）----
TYPE_REF = 0x01
TYPE_STR = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_BOOL = 0x12


class AppDef(object):
    """一个 Android App 的完整定义。"""
    __slots__ = (
        'package', 'label', 'icon_attr', 'round_icon_attr', 'version_code', 'version_name',
        'min_sdk', 'target_sdk', 'permissions', 'components', 'resources', 'theme',
        'uses_cleartext', 'extra_manifest_roots', 'extra_app_attrs',
    )

    def __init__(self):
        self.package = ''
        self.label = ''                      # app 名（直接写进 manifest 的 TYPE_STR）
        self.icon_attr = None                # (attr_name, res_ref) 如 ('icon', 0x7F010000)
        self.round_icon_attr = None
        self.version_code = 1
        self.version_name = '1.0'
        self.min_sdk = 23
        self.target_sdk = 29
        self.permissions = []                # 权限名字符串列表
        self.components = []                 # 组件定义列表（见 ComponentDef）
        self.resources = []                  # 资源定义列表（见 ResourceDef）
        self.theme = None                    # 例如 0x0103012c
        self.uses_cleartext = False
        self.extra_manifest_roots = []       # 额外顶层元素（如 uses-sdk 之外的自定义）
        self.extra_app_attrs = []            # application 节点额外属性 [(name,type,value)]


class ComponentDef(object):
    """manifest 里的一个组件（activity/service/receiver/provider）。"""
    __slots__ = ('kind', 'name', 'exported', 'attrs', 'children')

    def __init__(self, kind, name, exported, attrs=None, children=None):
        self.kind = kind                     # 'activity' | 'service' | 'receiver' | 'provider'
        self.name = name                     # 如 '.MainActivity'
        self.exported = exported
        self.attrs = attrs or []             # (attr_name, type, value) 额外属性
        self.children = children or []       # 子元素，如 intent-filter


class ResourceDef(object):
    """resources.arsc 里的一个资源条目。"""
    __slots__ = ('type_name', 'res_name', 'file_path')

    def __init__(self, type_name, res_name, file_path):
        self.type_name = type_name           # 'mipmap' / 'drawable' / 'string' 等
        self.res_name = res_name             # 'ic_launcher' / 'app_name' 等
        self.file_path = file_path           # APK 内的路径，如 'res/mipmap/ic_launcher.png'


def build_dshandroid_app():
    """DShAndroid：本地 DeepSeek Agent 客户端（含无障碍操作）。"""
    a = AppDef()
    a.package = 'com.dsh.android'
    a.label = 'DShAndroid'
    a.icon_attr = ('icon', 0x7F010000)
    a.round_icon_attr = ('roundIcon', 0x7F010000)
    a.version_code = 25
    a.version_name = '1.0 beta 10'
    a.min_sdk = 23
    a.target_sdk = 29
    a.uses_cleartext = True  # 便于接入自定义/本地后端调试
    a.permissions = [
        'android.permission.INTERNET',
        'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.POST_NOTIFICATIONS',
        # 应用内更新：下载完直接用系统安装器装
        'android.permission.REQUEST_INSTALL_PACKAGES',
    ]
    # 允许在 Android 10 上以旧式存储访问 /sdcard（全局文件模式需要）
    a.extra_app_attrs = [
        ('requestLegacyExternalStorage', TYPE_BOOL, True),
        # 插件市场要解析约 3MB 的索引 JSON，给大一点的堆更稳
        ('largeHeap', TYPE_BOOL, True),
    ]
    # 主界面
    a.components.append(ComponentDef(
        'activity', '.ui.MainActivity', True,
        attrs=[
            ('theme', TYPE_REF, 0x0103012c),
            ('windowSoftInputMode', TYPE_INT_HEX, 0x10),
            ('configChanges', TYPE_INT_HEX, 0x06A0),
        ],
        children=[
            ('intent-filter', [], [
                ('action', [('name', TYPE_STR, 'android.intent.action.MAIN')], []),
                ('category', [('name', TYPE_STR, 'android.intent.category.LAUNCHER')], []),
            ]),
        ],
    ))
    # 设置页（独立菜单，仿 Minis 的设置入口）
    a.components.append(ComponentDef(
        'activity', '.ui.SettingsActivity', False,
        attrs=[
            ('theme', TYPE_REF, 0x0103012c),
            ('windowSoftInputMode', TYPE_INT_HEX, 0x10),
            ('configChanges', TYPE_INT_HEX, 0x06A0),
        ],
    ))
    # 插件市场：从 dshmarket 索引一键装插件
    a.components.append(ComponentDef(
        'activity', '.ui.MarketActivity', False,
        attrs=[
            ('theme', TYPE_REF, 0x0103012c),
            ('windowSoftInputMode', TYPE_INT_HEX, 0x10),
            ('configChanges', TYPE_INT_HEX, 0x06A0),
        ],
    ))
    # 关于软件
    a.components.append(ComponentDef(
        'activity', '.ui.AboutActivity', False,
        attrs=[
            ('theme', TYPE_REF, 0x0103012c),
            ('windowSoftInputMode', TYPE_INT_HEX, 0x10),
            ('configChanges', TYPE_INT_HEX, 0x06A0),
        ],
    ))
    # 关于作者
    a.components.append(ComponentDef(
        'activity', '.ui.AuthorActivity', False,
        attrs=[
            ('theme', TYPE_REF, 0x0103012c),
            ('windowSoftInputMode', TYPE_INT_HEX, 0x10),
            ('configChanges', TYPE_INT_HEX, 0x06A0),
        ],
    ))
    # 安装包分发：自实现的 FileProvider（零依赖，只读 cache/apk/）
    a.components.append(ComponentDef(
        'provider', '.kernel.ApkProvider', False,
        attrs=[
            ('authorities', TYPE_STR, 'com.dsh.android.apk'),
            ('grantUriPermissions', TYPE_BOOL, True),
        ],
    ))
    # 无障碍服务。
    # 服务必须在 manifest 里声明，系统「无障碍」列表才会出现它；meta-data 指向
    # @xml/accessibility_service_config，声明「能读窗口内容、能派发手势」。
    # 缺了这份配置，服务能开启但 dump 拿不到节点、手势也被系统拒绝。
    a.components.append(ComponentDef(
        'service', '.a11y.A11yService', True,
        # 最小化声明：只保留 permission，与标准工具构建的服务结构保持一致
        attrs=[
            ('permission', TYPE_STR, 'android.permission.BIND_ACCESSIBILITY_SERVICE'),
        ],
        children=[
            ('intent-filter', [], [
                ('action', [('name', TYPE_STR, 'android.accessibilityservice.AccessibilityService')], []),
            ]),
            ('meta-data', [('name', TYPE_STR, 'android.accessibilityservice'),
                           ('resource', TYPE_REF, 0x7F020000)], []),
        ],
    ))
    a.resources.append(ResourceDef('mipmap', 'ic_launcher', 'res/mipmap/ic_launcher.png'))
    # 无障碍能力声明（0x7F020000），被上面服务的 meta-data 引用
    a.resources.append(ResourceDef('xml', 'accessibility_service_config',
                                   'res/xml/accessibility_service_config.xml'))

    return a


def build_app():
    """本仓库唯一的 App 定义。"""
    return build_dshandroid_app()
