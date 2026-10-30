package com.dsh.android.kernel;

/** 全局常量与配置。 */
public final class C {
    public static final String APP_NAME = "DShAndroid";
    public static final String VERSION = "1.0 beta 10";

    // DeepSeek 默认端点
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String MODEL_CHAT = "deepseek-chat";
    public static final String MODEL_REASONER = "deepseek-reasoner";

    // 本地持久化键
    public static final String SP_NAME = "dshandroid";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    /** 推理等级：替代温度，直接映射到 max_tokens（DeepSeek reasoner 不支持 temperature）。 */
    public static final String KEY_REASONING = "reasoning_level";
    public static final String LEVEL_FAST = "fast";
    public static final String LEVEL_BALANCED = "balanced";
    public static final String LEVEL_DEEP = "deep";

    /** 文件作用域：workspace = 只能碰自己的工作区；device = 整个设备。 */
    public static final String KEY_FS_SCOPE = "fs_scope";
    public static final String SCOPE_WORKSPACE = "workspace";
    public static final String SCOPE_DEVICE = "device";

    /** 无障碍操作总开关（随时开/关）。 */
    public static final String KEY_A11Y_ENABLED = "a11y_enabled";

    /** 插件：目录名与启用状态前缀。 */
    public static final String PLUGIN_DIR = "DShAndroid/plugins";
    public static final String KEY_PLUGIN_ON = "plugin_on_";
    public static final String KEY_PLUGIN_SEEN = "plugin_seen_";

    // 无障碍动作常量（与 JS bridge / 工具运行时共享）
    public static final String A11Y_DUMP = "a11y.dump";
    public static final String A11Y_CLICK = "a11y.click";
    public static final String A11Y_SCROLL = "a11y.scroll";
    public static final String A11Y_TEXT = "a11y.settext";
    public static final String A11Y_BACK = "a11y.back";
    public static final String A11Y_HOME = "a11y.home";

    private C() {}
}
