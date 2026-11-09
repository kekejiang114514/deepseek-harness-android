package com.dsh.android.kernel;

/** 全局常量与配置。 */
public final class C {
    public static final String APP_NAME = "DShAndroid";
    public static final String VERSION = "1.4.1";

    // DeepSeek 默认端点
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String MODEL_CHAT = "deepseek-chat";
    public static final String MODEL_REASONER = "deepseek-reasoner";

    // 本地持久化键
    public static final String SP_NAME = "dshandroid";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    /** 推理强度：替代温度，最终映射到 max_tokens（DeepSeek reasoner 不支持 temperature）。 */
    public static final String KEY_REASONING = "reasoning_level";

    /** 六档，从低到高。 */
    public static final String LEVEL_OFF = "off";
    public static final String LEVEL_LOW = "low";
    public static final String LEVEL_MEDIUM = "medium";
    public static final String LEVEL_HIGH = "high";
    public static final String LEVEL_XHIGH = "xhigh";
    public static final String LEVEL_MAX = "max";

    /** 旧版本用的三档，仅用于迁移。 */
    public static final String LEVEL_FAST = "fast";
    public static final String LEVEL_BALANCED = "balanced";
    public static final String LEVEL_DEEP = "deep";

    /** 把已存的等级（可能是旧的三档）统一成六档之一。 */
    public static String normalizeLevel(String level) {
        if (level == null) return LEVEL_MEDIUM;
        if (LEVEL_FAST.equals(level)) return LEVEL_LOW;
        if (LEVEL_BALANCED.equals(level)) return LEVEL_MEDIUM;
        if (LEVEL_DEEP.equals(level)) return LEVEL_HIGH;
        for (String[] row : LEVELS) {
            if (row[0].equals(level)) return level;
        }
        return LEVEL_MEDIUM;
    }

    /** 六档的 key 与说明。 */
    public static final String[][] LEVELS = {
            {LEVEL_OFF, "不推理，直接给答案"},
            {LEVEL_LOW, "少量思考，响应最快"},
            {LEVEL_MEDIUM, "日常默认"},
            {LEVEL_HIGH, "更细致的推理"},
            {LEVEL_XHIGH, "复杂问题深推理，较慢"},
            {LEVEL_MAX, "最大强度，耗时明显"},
    };

    /**
     * 等级 → 输出上限（max_tokens）。
     *
     * <p>DeepSeek 的输出上限是 384K，早先那句「接口上限 8192」是错的，已作废。
     * 这里不需要贴着上限：思考链也算在输出里，档位越高留的余量越大。
     */
    public static int maxTokensFor(String level) {
        String l = normalizeLevel(level);
        if (LEVEL_OFF.equals(l)) return 4096;
        if (LEVEL_LOW.equals(l)) return 8192;
        if (LEVEL_MEDIUM.equals(l)) return 16384;
        if (LEVEL_HIGH.equals(l)) return 32768;
        if (LEVEL_XHIGH.equals(l)) return 49152;
        return 65536;
    }

    /**
     * 等级 → 真正发给接口的推理强度 {@code reasoning_effort}。
     *
     * <p>接口只认 low / high / max 三档（官方「Thinking Mode」文档的参数表）。
     * 六档到三档的映射与文档里的映射表一致，没有信息丢失：
     * 文档中 medium 与 xhigh 实际都映射到 high。
     *
     * @return null 表示不显式指定（off 档改为关闭思考模式，见 {@link #thinkingOff}）
     */
    public static String reasoningEffortFor(String level) {
        String l = normalizeLevel(level);
        if (LEVEL_OFF.equals(l)) return null;
        if (LEVEL_LOW.equals(l)) return "low";
        if (LEVEL_MAX.equals(l)) return "max";
        // medium / high / xhigh：文档映射表显示这三档实际都是 high
        return "high";
    }

    /** 该等级是否要明确关闭思考模式。 */
    public static boolean thinkingOff(String level) {
        return LEVEL_OFF.equals(normalizeLevel(level));
    }

    /** 文件作用域：workspace = 只能碰自己的工作区；device = 整个设备。 */
    public static final String KEY_FS_SCOPE = "fs_scope";
    public static final String SCOPE_WORKSPACE = "workspace";
    public static final String SCOPE_DEVICE = "device";

    /** 无障碍操作总开关（随时开/关）。 */
    public static final String KEY_A11Y_ENABLED = "a11y_enabled";
    /** 悬浮窗总开关 */
    public static final String KEY_FLOAT = "float_window";
    /** 等用户在系统页授权「显示在其他应用上层」后再启动 */
    public static final String KEY_FLOAT_PENDING = "float_pending";
    /** 后台常驻：只挂通知吊住进程，避免无障碍服务被系统回收 */
    public static final String KEY_KEEPALIVE = "keep_alive";
    /** 上下文接近上限时自动压缩（默认开） */
    public static final String KEY_AUTO_COMPACT = "auto_compact";

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
