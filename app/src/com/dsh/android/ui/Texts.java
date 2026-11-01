package com.dsh.android.ui;

import android.content.Context;

import com.dsh.android.kernel.Lang;

/**
 * 界面文案。
 *
 * <p>只放「骨架」文案（设置项、按钮、常见状态），长句说明仍以中文为主并回退英文。
 * 逐条翻译到八种语言：简体、繁体三地（用词各自本地化）、英语、阿拉伯语、日语、韩语。
 * 其他语言回退英文，避免出现半句中文半句外语。
 */
public final class Texts {

    private Texts() {}

    // 语言：0=zh-Hans 1=zh-HK 2=zh-MO 3=zh-TW 4=en 5=ar 6=ja 7=ko
    private static final int HANS = 0, HK = 1, MO = 2, TW = 3, EN = 4, AR = 5, JA = 6, KO = 7;

    private static String[] row(String hans, String hk, String mo, String tw,
                                String en, String ar, String ja, String ko) {
        return new String[]{hans, hk, mo, tw, en, ar, ja, ko};
    }

    /** 文案表。键名用简短英文，值按上面八个下标排列。 */
    private static final Object[][] TABLE = {
            {"settings", row("设置", "設定", "設定", "設定", "Settings", "الإعدادات", "設定", "설정")},
            {"endpoint", row("接口与密钥", "介面與密鑰", "介面與密鑰", "介面與金鑰", "Endpoint & key", "الواجهة والمفتاح", "エンドポイントとキー", "엔드포인트 및 키")},
            {"tools", row("工具能力", "工具能力", "工具能力", "工具能力", "Tools", "الأدوات", "ツール", "도구")},
            {"plugins", row("插件", "外掛", "外掛", "外掛", "Plugins", "الإضافات", "プラグイン", "플러그인")},
            {"appearance", row("外观", "外觀", "外觀", "外觀", "Appearance", "المظهر", "外観", "모양")},
            {"about", row("关于", "關於", "關於", "關於", "About", "حول", "情報", "정보")},
            {"language", row("语言", "語言", "語言", "語言", "Language", "اللغة", "言語", "언어")},
            {"theme", row("主题", "主題", "主題", "主題", "Theme", "السمة", "テーマ", "테마")},
            {"model", row("模型", "模型", "模型", "模型", "Model", "النموذج", "モデル", "모델")},
            {"reasoning", row("推理强度", "推理強度", "推理強度", "推理強度", "Reasoning effort", "قوة الاستدلال", "推論の強さ", "추론 강도")},
            {"file_scope", row("文件作用域", "檔案範圍", "檔案範圍", "檔案範圍", "File scope", "نطاق الملفات", "ファイル範囲", "파일 범위")},
            {"check_update", row("检查更新", "檢查更新", "檢查更新", "檢查更新", "Check for updates", "التحقق من التحديثات", "アップデートを確認", "업데이트 확인")},
            {"auto_update", row("自动检查更新", "自動檢查更新", "自動檢查更新", "自動檢查更新", "Auto-check for updates", "التحقق التلقائي", "自動確認", "자동 확인")},
            {"beta", row("接收 Beta 版本", "接收 Beta 版本", "接收 Beta 版本", "接收 Beta 版本", "Receive beta builds", "استلام إصدارات بيتا", "ベータ版を受け取る", "베타 버전 받기")},
            {"version", row("版本", "版本", "版本", "版本", "Version", "الإصدار", "バージョン", "버전")},
            {"open_source", row("开源", "開源", "開源", "開源", "Open source", "المصدر المفتوح", "オープンソース", "오픈 소스")},
            {"license", row("开源许可", "開源許可", "開源許可", "開源授權", "Licence", "الترخيص", "ライセンス", "라이선스")},
            {"source_repo", row("源码仓库", "原始碼倉庫", "原始碼倉庫", "原始碼倉庫", "Source repository", "مستودع المصدر", "ソースリポジトリ", "소스 저장소")},
            {"author", row("作者", "作者", "作者", "作者", "Author", "المؤلف", "作者", "작성자")},
            {"save", row("保存", "儲存", "儲存", "儲存", "Save", "حفظ", "保存", "저장")},
            {"show", row("显示", "顯示", "顯示", "顯示", "Show", "إظهار", "表示", "표시")},
            {"hide", row("隐藏", "隱藏", "隱藏", "隱藏", "Hide", "إخفاء", "非表示", "숨기기")},
            {"cancel", row("取消", "取消", "取消", "取消", "Cancel", "إلغاء", "キャンセル", "취소")},
            {"later", row("稍后", "稍後", "稍後", "稍後", "Later", "لاحقًا", "あとで", "나중에")},
            {"ok", row("确定", "確定", "確定", "確定", "OK", "حسنًا", "OK", "확인")},
            {"retry", row("重试", "重試", "重試", "重試", "Retry", "إعادة المحاولة", "再試行", "다시 시도")},
            {"download_install", row("下载并安装", "下載並安裝", "下載並安裝", "下載並安裝", "Download and install", "تنزيل وتثبيت", "ダウンロードしてインストール", "다운로드 및 설치")},
            {"screen_ops", row("屏幕操作", "螢幕操作", "螢幕操作", "螢幕操作", "Screen control", "التحكم بالشاشة", "画面操作", "화면 조작")},
            {"open_a11y", row("打开无障碍设置", "開啟無障礙設定", "開啟無障礙設定", "開啟無障礙設定", "Open accessibility settings", "فتح إعدادات إمكانية الوصول", "ユーザー補助設定を開く", "접근성 설정 열기")},
            {"copy_diag", row("复制无障碍诊断信息", "複製無障礙診斷資訊", "複製無障礙診斷資訊", "複製無障礙診斷資訊", "Copy accessibility diagnostics", "نسخ تشخيص إمكانية الوصول", "診断情報をコピー", "진단 정보 복사")},
            {"market", row("插件市场", "外掛市集", "外掛市集", "外掛市集", "Plugin market", "متجر الإضافات", "プラグインマーケット", "플러그인 마켓")},
            {"network", row("网络", "網絡", "網絡", "網路", "Network", "الشبكة", "ネットワーク", "네트워크")},
            {"software", row("软件", "軟件", "軟件", "軟體", "Software", "البرنامج", "ソフトウェア", "소프트웨어")},
            {"user", row("用户", "用戶", "用戶", "使用者", "User", "المستخدم", "ユーザー", "사용자")},
            {"default", row("默认", "預設", "預設", "預設", "Default", "افتراضي", "既定", "기본값")},
            {"data", row("数据", "數據", "數據", "資料", "Data", "البيانات", "データ", "데이터")},
            {"follow_system", row("跟随系统", "跟隨系統", "跟隨系統", "跟隨系統", "Follow system", "حسب النظام", "システムに従う", "시스템 설정 따르기")},
            {"light", row("浅色", "淺色", "淺色", "淺色", "Light", "فاتح", "ライト", "라이트")},
            {"dark", row("深色", "深色", "深色", "深色", "Dark", "داكن", "ダーク", "다크")},
            {"lang_note", row(
                    "语言会立即生效；未完整翻译的语言回退英文。",
                    "語言會即時生效；未完整翻譯的語言回退英文。",
                    "語言會即時生效；未完整翻譯的語言回退英文。",
                    "語言會立即生效；未完整翻譯的語言回退英文。",
                    "Changes apply immediately. Languages without a full translation fall back to English.",
                    "تُطبَّق التغييرات فورًا. اللغات غير المترجمة بالكامل تعود إلى الإنجليزية.",
                    "変更はすぐに反映されます。未翻訳の言語は英語で表示されます。",
                    "변경 사항은 즉시 적용됩니다. 번역되지 않은 언어는 영어로 표시됩니다.")},
    };

    private static int indexOf(String tag) {
        if (tag == null) return EN;
        if (tag.startsWith("zh-Hans") || tag.startsWith("zh-CN") || tag.startsWith("zh-SG")) return HANS;
        if (tag.startsWith("zh-HK")) return HK;
        if (tag.startsWith("zh-MO")) return MO;
        if (tag.startsWith("zh-TW") || tag.startsWith("zh-Hant")) return TW;
        if (tag.startsWith("ar")) return AR;
        if (tag.startsWith("ja")) return JA;
        if (tag.startsWith("ko")) return KO;
        return EN;
    }

    /** 取文案；找不到键时返回键本身，便于发现漏翻。 */
    public static String t(Context c, String key) {
        int idx = indexOf(Lang.textTag(c));
        for (int i = 0; i < TABLE.length; i++) {
            if (key.equals(TABLE[i][0])) {
                String[] values = (String[]) TABLE[i][1];
                String v = values[idx];
                return v == null ? values[EN] : v;
            }
        }
        return key;
    }
}
