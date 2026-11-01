package com.dsh.android.kernel;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语言列表与当前语言。
 *
 * <p>语言项分三类：
 * <ul>
 *   <li>{@link #SUPPORTED} —— 界面文案已完整翻译</li>
 *   <li>{@link #PARTIAL} —— 主要文案已翻译，长句回退英文</li>
 *   <li>其余 —— 菜单里可选，整站回退英文，避免出现半截中文半截英文</li>
 * </ul>
 *
 * <p>繁体中文按地区分列：中国香港、中国澳门、中国台湾各自使用当地惯用词，
 * 不是简单把简体转繁体。RTL 语言（阿拉伯语、希伯来语、波斯语、乌尔都语等）
 * 会同时把界面布局方向改为从右到左。
 */
public final class Lang {

    public static final String KEY_LANG = "app_language";
    /** 跟随系统。 */
    public static final String SYSTEM = "system";

    public static final int SUPPORTED = 2;   // 完整翻译
    public static final int PARTIAL = 1;     // 部分翻译
    public static final int FALLBACK = 0;    // 回退英文

    /** 一个语言项。 */
    public static final class Item {
        public final String code;      // BCP-47
        public final String native_;   // 本地写法
        public final String zh;        // 中文名（用于中文界面下的显示）
        public final int level;
        public final boolean rtl;
        /** 地区标注，用于繁体三地这类同名语言；空表示不需要。 */
        public final String region;

        Item(String code, String native_, String zh, int level, boolean rtl) {
            this.code = code;
            this.native_ = native_;
            this.zh = zh;
            this.level = level;
            this.rtl = rtl;
            if ("zh-HK".equals(code)) this.region = "中國香港";
            else if ("zh-MO".equals(code)) this.region = "中國澳門";
            else if ("zh-TW".equals(code)) this.region = "中國台灣";
            else this.region = "";
        }

        /** 列表里显示的名字；繁体三地带上地区，避免看起来一模一样。 */
        public String display() {
            return region.length() == 0 ? native_ : native_ + "（" + region + "）";
        }
    }

    private static final List<Item> ITEMS = new ArrayList<Item>();

    private static void add(String code, String native_, String zh, int level) {
        boolean rtl = isRtlTag(code);
        ITEMS.add(new Item(code, native_, zh, level, rtl));
    }

    static {
        // 中文：三地繁体分开，用词各自本地化
        add("zh-Hans", "简体中文", "简体中文", SUPPORTED);
        add("zh-HK", "繁體中文", "繁体中文（中国香港）", SUPPORTED);
        add("zh-MO", "繁體中文", "繁体中文（中国澳门）", SUPPORTED);
        add("zh-TW", "繁體中文", "繁体中文（中国台湾）", SUPPORTED);

        add("en", "English", "英语", SUPPORTED);
        add("ar", "العربية", "阿拉伯语", SUPPORTED);
        add("ja", "日本語", "日语", PARTIAL);
        add("ko", "한국어", "韩语", PARTIAL);
        add("ru", "Русский", "俄语", PARTIAL);
        add("fr", "Français", "法语", PARTIAL);
        add("de", "Deutsch", "德语", PARTIAL);
        add("es", "Español", "西班牙语", PARTIAL);
        add("pt", "Português", "葡萄牙语", PARTIAL);
        add("it", "Italiano", "意大利语", PARTIAL);

        // 以下语言菜单可选，界面回退英文
        add("he", "עברית", "希伯来语", FALLBACK);
        add("fa", "فارسی", "波斯语", FALLBACK);
        add("ur", "اردو", "乌尔都语", FALLBACK);
        add("tr", "Türkçe", "土耳其语", FALLBACK);
        add("vi", "Tiếng Việt", "越南语", FALLBACK);
        add("th", "ไทย", "泰语", FALLBACK);
        add("id", "Bahasa Indonesia", "印尼语", FALLBACK);
        add("ms", "Bahasa Melayu", "马来语", FALLBACK);
        add("hi", "हिन्दी", "印地语", FALLBACK);
        add("bn", "বাংলা", "孟加拉语", FALLBACK);
        add("ta", "தமிழ்", "泰米尔语", FALLBACK);
        add("te", "తెలుగు", "泰卢固语", FALLBACK);
        add("ml", "മലയാളം", "马拉雅拉姆语", FALLBACK);
        add("kn", "ಕನ್ನಡ", "卡纳达语", FALLBACK);
        add("gu", "ગુજરાતી", "古吉拉特语", FALLBACK);
        add("mr", "मराठी", "马拉地语", FALLBACK);
        add("pa", "ਪੰਜਾਬੀ", "旁遮普语", FALLBACK);
        add("ne", "नेपाली", "尼泊尔语", FALLBACK);
        add("si", "සිංහල", "僧伽罗语", FALLBACK);
        add("my", "မြန်မာ", "缅甸语", FALLBACK);
        add("km", "ខ្មែរ", "高棉语", FALLBACK);
        add("lo", "ລາວ", "老挝语", FALLBACK);
        add("nl", "Nederlands", "荷兰语", FALLBACK);
        add("pl", "Polski", "波兰语", FALLBACK);
        add("uk", "Українська", "乌克兰语", FALLBACK);
        add("cs", "Čeština", "捷克语", FALLBACK);
        add("sk", "Slovenčina", "斯洛伐克语", FALLBACK);
        add("hu", "Magyar", "匈牙利语", FALLBACK);
        add("ro", "Română", "罗马尼亚语", FALLBACK);
        add("bg", "Български", "保加利亚语", FALLBACK);
        add("el", "Ελληνικά", "希腊语", FALLBACK);
        add("sv", "Svenska", "瑞典语", FALLBACK);
        add("da", "Dansk", "丹麦语", FALLBACK);
        add("fi", "Suomi", "芬兰语", FALLBACK);
        add("no", "Norsk", "挪威语", FALLBACK);
        add("hr", "Hrvatski", "克罗地亚语", FALLBACK);
        add("sr", "Српски", "塞尔维亚语", FALLBACK);
        add("sl", "Slovenščina", "斯洛文尼亚语", FALLBACK);
        add("lt", "Lietuvių", "立陶宛语", FALLBACK);
        add("lv", "Latviešu", "拉脱维亚语", FALLBACK);
        add("et", "Eesti", "爱沙尼亚语", FALLBACK);
        add("ca", "Català", "加泰罗尼亚语", FALLBACK);
        add("az", "Azərbaycan", "阿塞拜疆语", FALLBACK);
        add("kk", "Қазақ", "哈萨克语", FALLBACK);
        add("uz", "Oʻzbek", "乌兹别克语", FALLBACK);
        add("mn", "Монгол", "蒙古语", FALLBACK);
        add("ka", "ქართული", "格鲁吉亚语", FALLBACK);
        add("hy", "Հայերեն", "亚美尼亚语", FALLBACK);
        add("sw", "Kiswahili", "斯瓦希里语", FALLBACK);
        add("am", "አማርኛ", "阿姆哈拉语", FALLBACK);
        add("af", "Afrikaans", "南非荷兰语", FALLBACK);
        add("tl", "Filipino", "菲律宾语", FALLBACK);
    }

    private Lang() {}

    /** 内置语言（打进安装包），其余为可下载的语言包。 */
    public static boolean isBuiltin(String code) {
        if (code == null) return false;
        return code.startsWith("zh-") || "en".equals(code) || "ru".equals(code)
                || "ja".equals(code) || "ko".equals(code);
    }

    public static List<Item> all() {
        return new ArrayList<Item>(ITEMS);
    }

    public static Item byCode(String code) {
        for (int i = 0; i < ITEMS.size(); i++) {
            if (ITEMS.get(i).code.equals(code)) return ITEMS.get(i);
        }
        return null;
    }

    /** 设置里选的语言；{@link #SYSTEM} 表示跟随系统。 */
    public static String selected(Context c) {
        return prefs(c).getString(KEY_LANG, SYSTEM);
    }

    public static void setSelected(Context c, String code) {
        prefs(c).edit().putString(KEY_LANG, code == null ? SYSTEM : code).apply();
    }

    /** 实际生效的语言项。 */
    public static Item effective(Context c) {
        String sel = selected(c);
        if (SYSTEM.equals(sel)) {
            String sys = systemTag(c);
            Item hit = match(sys);
            if (hit != null) return hit;
            return byCode("en");
        }
        Item hit = byCode(sel);
        return hit == null ? byCode("en") : hit;
    }

    /** 界面文案实际使用的语言代码。所有语言都有译文，不再降级为英文。 */
    public static String textTag(Context c) {
        return effective(c).code;
    }

    public static boolean isRtl(Context c) {
        return effective(c).rtl;
    }

    /** 系统语言（考虑地区，用于区分三地繁体）。 */
    public static String systemTag(Context c) {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage() == null ? "" : l.getLanguage().toLowerCase();
        String country = l.getCountry() == null ? "" : l.getCountry().toUpperCase();
        if ("zh".equals(lang)) {
            if ("TW".equals(country)) return "zh-TW";
            if ("HK".equals(country)) return "zh-HK";
            if ("MO".equals(country)) return "zh-MO";
            // 繁体脚本标记
            String script = l.getScript();
            if ("Hant".equalsIgnoreCase(script)) return "zh-TW";
            return "zh-Hans";
        }
        return lang;
    }

    private static Item match(String tag) {
        if (tag == null) return null;
        Item exact = byCode(tag);
        if (exact != null) return exact;
        int dash = tag.indexOf('-');
        if (dash > 0) {
            String base = tag.substring(0, dash);
            Item loose = byCode(base);
            if (loose != null) return loose;
        }
        return null;
    }

    private static boolean isRtlTag(String code) {
        String c = code == null ? "" : code.toLowerCase(Locale.US);
        int dash = c.indexOf('-');
        if (dash > 0) c = c.substring(0, dash);
        return "ar".equals(c) || "he".equals(c) || "fa".equals(c) || "ur".equals(c)
                || "ps".equals(c) || "sd".equals(c) || "ug".equals(c) || "yi".equals(c)
                || "dv".equals(c) || "ku".equals(c);
    }

    /** 把语言应用到 Configuration，供 Activity 使用。 */
    public static void applyTo(Configuration cfg, Context c) {
        Item it = effective(c);
        Locale locale = Locale.forLanguageTag(it.code);
        cfg.setLocale(locale);
        cfg.setLayoutDirection(locale);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(C.SP_NAME, Context.MODE_PRIVATE);
    }
}
