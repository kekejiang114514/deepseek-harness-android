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
        'min_sdk', 'target_sdk', 'compile_sdk', 'permissions', 'components', 'resources', 'theme',
        'platform_build_version_code', 'platform_build_version_name',
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
        self.compile_sdk = 29
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
    a.version_code = 132
    a.version_name = '1.6-beta26'
    a.min_sdk = 23
    a.target_sdk = 34
    a.platform_build_version_code = 34
    a.platform_build_version_name = '14'
    a.compile_sdk = 34
    a.uses_cleartext = True  # 便于接入自定义/本地后端调试
    a.permissions = [
        'android.permission.INTERNET',
        'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.POST_NOTIFICATIONS',
        # 语音输入（系统 SpeechRecognizer）
        'android.permission.RECORD_AUDIO',
        # 应用内更新：下载完直接用系统安装器装
        'android.permission.REQUEST_INSTALL_PACKAGES',
        # 悬浮窗：退到后台后显示 Agent 状态
        'android.permission.SYSTEM_ALERT_WINDOW',
        # 悬浮窗是个前台服务：常驻通知顺带让进程不那么容易被回收
        'android.permission.FOREGROUND_SERVICE',
        # 开机后重新登记定时任务
        'android.permission.RECEIVE_BOOT_COMPLETED',
    ]
    # 允许在 Android 10 上以旧式存储访问 /sdcard（全局文件模式需要）
    a.extra_app_attrs = [
        # 说明：networkSecurityConfig / fullBackupContent 暂不使用。
        # 前者需要 res/xml 下的二进制 AXML（含 <domain> 文本节点），
        # 本项目的 AXML 写出器还不支持文本节点；后者依赖同一套资源。
        # 备份已由 allowBackup=false 覆盖，明文策略由 usesCleartextTraffic 控制。
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
    # 电脑面板：内置浏览器画面 + 截图
    a.components.append(ComponentDef(
        'activity', '.ui.ComputerActivity', False,
        attrs=[
            ('theme', TYPE_REF, 0x0103012c),
            ('windowSoftInputMode', TYPE_INT_HEX, 0x10),
            ('configChanges', TYPE_INT_HEX, 0x06A0),
        ],
    ))
    # 可见的内置浏览器
    a.components.append(ComponentDef(
        'activity', '.ui.BrowserActivity', False,
        attrs=[
            ('theme', TYPE_REF, 0x0103012c),
            ('windowSoftInputMode', TYPE_INT_HEX, 0x10),
            ('configChanges', TYPE_INT_HEX, 0x06A0),
        ],
    ))
    # 终端
    a.components.append(ComponentDef(
        'activity', '.ui.TerminalActivity', False,
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
            # 系统「无障碍」列表里显示的名字与说明：没有 label 会退化成应用名，
            # 没有 description 那栏就是空的，用户不知道开的是什么东西
            ('label', TYPE_STR, 'AI 屏幕操作'),
            # 从最近任务里划掉应用时不要连带停掉服务（默认值在部分 ROM 上并不一致）
            ('stopWithTask', TYPE_BOOL, False),
        ],
        children=[
            ('intent-filter', [], [
                ('action', [('name', TYPE_STR, 'android.accessibilityservice.AccessibilityService')], []),
            ]),
            ('meta-data', [('name', TYPE_STR, 'android.accessibilityservice'),
                           ('resource', TYPE_REF, 0x7F020000)], []),
        ],
    ))
    # 悬浮窗（前台服务）：不导出，只由本应用自己启动
    a.components.append(ComponentDef(
        'service', '.ui.FloatService', False,
        attrs=[
            ('stopWithTask', TYPE_BOOL, False),
            # Android 14 起前台服务必须声明类型，否则启动即崩
            ('foregroundServiceType', TYPE_INT_HEX, 0x00000001),   # dataSync：无需 property，且符合同步/执行任务用途
        ],
    ))
    # 定时任务：闹钟广播 → 前台服务执行
    a.components.append(ComponentDef(
        'receiver', '.boot.TaskReceiver', False,
    ))
    a.components.append(ComponentDef(
        'receiver', '.boot.BootReceiver', True,
        children=[
            ('intent-filter', [], [
                ('action', [('name', TYPE_STR, 'android.intent.action.BOOT_COMPLETED')], []),
            ]),
            ('intent-filter', [], [
                ('action', [('name', TYPE_STR, 'android.intent.action.MY_PACKAGE_REPLACED')], []),
            ]),
        ],
    ))
    a.components.append(ComponentDef(
        'service', '.service.QqBotService', False,
        attrs=[('foregroundServiceType', TYPE_INT_HEX, 0x00000001)],  # dataSync
    ))
    a.components.append(ComponentDef(
        'service', '.service.AutoTaskService', False,
        attrs=[('foregroundServiceType', TYPE_INT_HEX, 0x00000001)],  # dataSync
    ))
    a.resources.append(ResourceDef('mipmap', 'ic_launcher', 'res/mipmap/ic_launcher.png'))
    # 无障碍能力声明（0x7F020000），被上面服务的 meta-data 引用
    a.resources.append(ResourceDef('xml', 'accessibility_service_config',
                                   'res/xml/accessibility_service_config.xml'))


    return a


def build_app():
    """App 定义。

    设置环境变量 PROBE_PACKAGE 时，产出的是一个「探针包」：
    组件、代码、签名完全相同，只把应用身份换成另一个包名。
    用途：设备上装不上正式包时，用它区分「设备拒绝这套签名/打包链」
    与「设备上已有同包名的冲突安装」。
    """
    import os
    app = build_dshandroid_app()

    # 无资源探针：把所有自定义资源去掉，图标改用系统资源。
    # 目的：验证「自定义 resources.arsc 是否被新系统拒绝」这一个假设。
    # 目标 SDK 覆盖：用于确认「新系统的安装限制是否与 targetSdk 有关」
    ts = os.environ.get('PROBE_TARGET_SDK', '').strip()
    if ts.isdigit():
        app.target_sdk = int(ts)

    # 只留 mipmap 的探针：判断「是资源表坏了」还是「无障碍那份 XML 坏了」
    # 只放 res 文件、不放 arsc：用于区分「资源表坏了」与「res 文件打包坏了」
    if os.environ.get('PROBE_PNG_ONLY', '').strip():
        app.package = os.environ.get('PROBE_PACKAGE', 'com.dsh.probe5').strip()
        app.version_name = os.environ.get('PROBE_VERSION_NAME', app.version_name)
        app.version_code = int(os.environ.get('PROBE_VERSION_CODE', str(app.version_code)))
        app.label = 'DSH Probe5'
        app.resources = []
        app.icon_attr = None
        app.round_icon_attr = None
        for c in app.components:
            if c.name == '.a11y.A11yService':
                c.children = [ch for ch in c.children if ch[0] != 'meta-data']

    if os.environ.get('PROBE_SIMPLE_RES', '').strip():
        app.package = os.environ.get('PROBE_PACKAGE', 'com.dsh.probe4').strip()
        app.version_name = os.environ.get('PROBE_VERSION_NAME', app.version_name)
        app.version_code = int(os.environ.get('PROBE_VERSION_CODE', str(app.version_code)))
        app.label = 'DSH Probe4'
        app.resources = [r for r in app.resources if r.type_name == 'mipmap']
        for c in app.components:
            if c.name == '.a11y.A11yService':
                c.children = [ch for ch in c.children if ch[0] != 'meta-data']

    if os.environ.get('PROBE_NO_RES', '').strip():
        app.package = os.environ.get('PROBE_PACKAGE', 'com.dsh.probe2').strip()
        app.version_name = os.environ.get('PROBE_VERSION_NAME', app.version_name)
        app.version_code = int(os.environ.get('PROBE_VERSION_CODE', str(app.version_code)))
        app.label = os.environ.get('PROBE_LABEL', 'DSH Probe2').strip()
        app.resources = []
        # 图标指向系统自带的占位图标（框架资源，不需要我们的 arsc）
        app.icon_attr = ('icon', 0x0108000B)       # @android:drawable/sym_def_app_icon
        app.round_icon_attr = None
        # 去掉指向 @xml/accessibility_service_config 的 meta-data
        keep = []
        for c in app.components:
            if c.name == '.a11y.A11yService':
                c.children = [ch for ch in c.children if ch[0] != 'meta-data']
            keep.append(c)
        app.components = keep
    # 版本号最后统一覆盖，避免探针分支之间互相覆盖
    vcode = os.environ.get('PROBE_VERSION_CODE', '').strip()
    if vcode.isdigit():
        app.version_code = int(vcode)
    vname = os.environ.get('PROBE_VERSION_NAME', '').strip()
    if vname:
        app.version_name = vname

    probe = os.environ.get('PROBE_PACKAGE', '').strip()
    if probe:
        app.package = probe
        app.version_name = os.environ.get('PROBE_VERSION_NAME', os.environ.get('PROBE_VERSION', 'probe-1'))
        app.version_code = int(os.environ.get('PROBE_VERSION_CODE', app.version_code))
        app.label = os.environ.get('PROBE_LABEL', 'DSH Probe').strip()
    return app
