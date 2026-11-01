package com.dsh.android.ui;

import android.content.Context;

import com.dsh.android.kernel.Lang;

/**
 * 界面文案。
 *
 * <p>表里每条给三份底稿：简体、繁体（以中国台湾用词书写）、英文。
 * 中国香港与中国澳门在繁体基础上换成当地惯用词（軟件、網絡、用戶、數據），
 * 中国台湾保留（軟體、網路、使用者、資料）。
 *
 * <p>阿拉伯语、日语、韩语及其他语言统一回退英文——与其给出机翻的半成品，
 * 不如给一份完整通顺的英文。
 */
public final class Texts {

    private Texts() {}

    /** key -> {简体, 繁體(中国台湾), English} */
    private static final Object[][] TABLE = {
            {"settings", new String[]{"设置", "設定", "Settings"}},
            {"endpoint", new String[]{"接口与密钥", "介面與金鑰", "Endpoint & key"}},
            {"tools", new String[]{"工具能力", "工具能力", "Tools"}},
            {"plugins", new String[]{"插件", "外掛", "Plugins"}},
            {"appearance", new String[]{"外观", "外觀", "Appearance"}},
            {"language", new String[]{"语言", "語言", "Language"}},
            {"about", new String[]{"关于", "關於", "About"}},
            {"sub_endpoint", new String[]{"接口地址、API Key、模型", "介面位址、API Key、模型", "Endpoint, API key, models"}},
            {"sub_tools", new String[]{"屏幕操作", "螢幕操作", "Screen control"}},
            {"sub_plugins", new String[]{"本地插件、插件市场", "本機外掛、外掛市集", "Local plugins, market"}},
            {"sub_appearance", new String[]{"主题", "主題", "Theme"}},
            {"sub_language", new String[]{"界面语言与文字方向", "介面語言與文字方向", "Interface language and direction"}},
            {"sub_about", new String[]{"版本、更新、作者、开源许可", "版本、更新、作者、開源授權", "Version, updates, author, licence"}},
            {"version", new String[]{"当前版本", "目前版本", "Current version"}},
            {"updates", new String[]{"更新", "更新", "Updates"}},
            {"check_update", new String[]{"检查更新", "檢查更新", "Check for updates"}},
            {"auto_update", new String[]{"自动检查更新", "自動檢查更新", "Auto-check for updates"}},
            {"auto_update_sub", new String[]{"每次打开应用时查一次，每天最多一次", "每次開啟應用程式時查一次，每天最多一次", "Once per launch, at most once a day"}},
            {"beta", new String[]{"接收 Beta 版本", "接收 Beta 版本", "Receive beta builds"}},
            {"beta_sub", new String[]{"开启后，测试版本也会提示更新", "開啟後，測試版本也會提示更新", "When on, prereleases are offered too"}},
            {"open_source", new String[]{"开源", "開源", "Open source"}},
            {"license", new String[]{"开源许可", "開源授權", "Licence"}},
            {"repo", new String[]{"源码仓库", "原始碼倉庫", "Source repository"}},
            {"author", new String[]{"作者", "作者", "Author"}},
            {"issue", new String[]{"提交 issue", "提交 issue", "Open an issue"}},
            {"a11y", new String[]{"屏幕操作", "螢幕操作", "Screen control"}},
            {"a11y_on", new String[]{"允许 Agent 读取和操作屏幕", "允許 Agent 讀取和操作螢幕", "Allow the agent to read and control the screen"}},
            {"open_a11y", new String[]{"打开无障碍设置", "開啟無障礙設定", "Open accessibility settings"}},
            {"open_appinfo", new String[]{"打开应用信息", "開啟應用程式資訊", "Open app info"}},
            {"try_root", new String[]{"尝试用 root 直接开启", "嘗試用 root 直接開啟", "Try enabling with root"}},
            {"copy_diag", new String[]{"复制无障碍诊断信息", "複製無障礙診斷資訊", "Copy accessibility diagnostics"}},
            {"copy_adb", new String[]{"复制 ADB 开启命令", "複製 ADB 開啟命令", "Copy ADB command"}},
            {"state_ready", new String[]{"已连接", "已連線", "Connected"}},
            {"state_notconnected", new String[]{"系统已开启 · 尚未连接", "系統已開啟 · 尚未連線", "Enabled, not connected yet"}},
            {"state_off", new String[]{"未开启 · 点此开启", "未開啟 · 點此開啟", "Disabled · tap to enable"}},
            {"endpoint_url", new String[]{"接口地址", "介面位址", "Endpoint"}},
            {"api_key", new String[]{"API Key", "API Key", "API key"}},
            {"show", new String[]{"显示", "顯示", "Show"}},
            {"hide", new String[]{"隐藏", "隱藏", "Hide"}},
            {"save", new String[]{"保存", "儲存", "Save"}},
            {"saved", new String[]{"已保存", "已儲存", "Saved"}},
            {"models", new String[]{"模型", "模型", "Models"}},
            {"reload_models", new String[]{"重新读取模型列表", "重新讀取模型清單", "Reload model list"}},
            {"models_hint", new String[]{"模型与推理强度在聊天页标题下方切换。", "模型與推理強度在聊天頁標題下方切換。", "Model and reasoning effort are switched under the chat title."}},
            {"theme", new String[]{"主题", "主題", "Theme"}},
            {"theme_sub", new String[]{"跟随系统时随系统深色模式自动切换", "跟隨系統時隨系統深色模式自動切換", "Follow system dark mode"}},
            {"follow_system", new String[]{"跟随系统", "跟隨系統", "Follow system"}},
            {"light", new String[]{"浅色", "淺色", "Light"}},
            {"dark", new String[]{"深色", "深色", "Dark"}},
            {"lang_follow", new String[]{"跟随系统", "跟隨系統", "Follow system"}},
            {"lang_full", new String[]{"已完整翻译", "已完整翻譯", "Fully translated"}},
            {"lang_partial", new String[]{"其他语言", "其他語言", "Other languages"}},
            {"lang_note", new String[]{
                    "切换后立即生效。未翻译的语言界面为英文。",
                    "切換後立即生效。未翻譯的語言介面為英文。",
                    "Applies immediately. Untranslated languages use English."}},
            {"lang_rtl", new String[]{"从右到左", "由右至左", "Right to left"}},
            {"market", new String[]{"插件市场", "外掛市集", "Plugin market"}},
            {"plugin_dir", new String[]{"插件目录 · 点击复制路径", "外掛目錄 · 點擊複製路徑", "Plugin directory · tap to copy"}},
            {"rescan", new String[]{"重新扫描插件", "重新掃描外掛", "Rescan plugins"}},
            {"sample", new String[]{"查看插件格式示例", "檢視外掛格式範例", "View plugin format sample"}},
            {"cancel", new String[]{"取消", "取消", "Cancel"}},
            {"ok", new String[]{"确定", "確定", "OK"}},
            {"later", new String[]{"稍后", "稍後", "Later"}},
            {"retry", new String[]{"重试", "重試", "Retry"}},
            {"install", new String[]{"下载并安装", "下載並安裝", "Download and install"}},
            {"new_version", new String[]{"发现新版本", "發現新版本", "Update available"}},
            {"current_version", new String[]{"当前版本", "目前版本", "Current version"}},
            {"reasoning", new String[]{"推理强度", "推理強度", "Reasoning effort"}},
            {"file_scope", new String[]{"文件作用域", "檔案範圍", "File scope"}},
            {"workspace", new String[]{"工作区　应用私有目录，最安全", "工作區　應用程式私有目錄，最安全", "Workspace — app-private, safest"}},
            {"device", new String[]{"全局　可读写整个存储（需授权）", "全域　可讀寫整個儲存空間（需授權）", "Device — whole storage (permission needed)"}},
            {"ws_short", new String[]{"工作区", "工作區", "Workspace"}},
            {"device_short", new String[]{"全局", "全域", "Device"}},
            {"off", new String[]{"关闭", "關閉", "off"}},
            {"copy", new String[]{"复制", "複製", "Copy"}},
            {"regen", new String[]{"重新生成", "重新產生", "Regenerate"}},
            {"copied", new String[]{"已复制", "已複製", "Copied"}},
    };

    private static int region(Context c) {
        String tag = Lang.textTag(c);
        if (tag.startsWith("zh-HK")) return 1;
        if (tag.startsWith("zh-MO")) return 2;
        if (tag.startsWith("zh-TW") || tag.startsWith("zh-Hant")) return 3;
        if (tag.startsWith("zh")) return 0;
        return 4;
    }

    /** 取文案。找不到键时原样返回键名，方便发现漏翻。 */
    public static String t(Context c, String key) {
        for (int i = 0; i < TABLE.length; i++) {
            if (!key.equals(TABLE[i][0])) continue;
            String[] v = (String[]) TABLE[i][1];
            int r = region(c);
            if (r == 0) return v[0];
            if (r == 4) return v[2];
            String tw = v[1];
            if (r == 1) return hkTyped(tw);   // 中国香港
            if (r == 2) return hkTyped(tw);   // 中国澳门同香港惯用
            return tw;                        // 中国台湾
        }
        return key;
    }

    /** 中国香港/澳门惯用词与台湾不同：软件、网络、用户、数据这类。 */
    private static String hkTyped(String tw) {
        return tw.replace("軟體", "軟件")
                .replace("網路", "網絡")
                .replace("使用者", "用戶")
                .replace("資料", "數據")
                .replace("資訊", "資訊")
                .replace("螢幕", "螢幕")
                .replace("設定", "設定");
    }
}
