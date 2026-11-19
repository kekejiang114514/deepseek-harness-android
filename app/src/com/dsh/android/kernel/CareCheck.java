package com.dsh.android.kernel;

/**
 * 负面情绪识别。
 *
 * <p>只做一件事：判断一句话里是否出现了明确的自伤/轻生表达。
 * 判据取「高精度、低召回」——宁可漏掉含糊的句子，也不要频繁误报
 * 打断正常对话（比如「这 bug 让我想死」这种口头语不该弹窗）。
 *
 * <p>弹窗里给出的号码必须是可核实的公共资源，不能编。
 */
public final class CareCheck {

    private CareCheck() {}

    /** 明确的自伤 / 轻生表达。命中任一条即触发。 */
    private static final String[] PATTERNS = {
            "自杀", "自尽", "轻生", "割腕", "跳楼", "安眠药自杀",
            "想死", "不想活", "活不下去", "不想活了", "结束生命", "了结自己",
            "伤害自己", "自残", "没有活下去的意义",
            "suicide", "kill myself", "end my life", "self-harm", "self harm",
            "want to die", "don't want to live", "do not want to live",
    };

    /** 是否值得弹出关怀提示。 */
    public static boolean looksDistressed(String text) {
        if (text == null) return false;
        String s = text.toLowerCase(java.util.Locale.US);
        for (int i = 0; i < PATTERNS.length; i++) {
            if (s.contains(PATTERNS[i])) return true;
        }
        return false;
    }

    /**
     * 弹窗正文。
     *
     * <p>号码说明：12356 是国家卫健委开通的全国统一心理援助热线；
     * 010-82951332 是北京心理危机研究与干预中心的 24 小时热线。
     * 两者都可能随时间调整，因此正文里同时提示「以当地公布为准」。
     */
    public static String careText() {
        return "看到你现在不太好受，先停一下。\n\n"
                + "你不需要一个人扛着。可以找人说说：\n"
                + "· 全国心理援助热线：12356（24 小时）\n"
                + "· 北京心理危机研究与干预中心：010-82951332\n"
                + "· 紧急情况：120（急救）或 110（报警）\n\n"
                + "如果你身边有人，也可以直接告诉对方「我现在状态很差，"
                + "需要你陪一会儿」。\n\n"
                + "号码以当地最新公布为准；本应用只提供信息，不能替代专业帮助。";
    }

    /** 弹窗标题。 */
    public static String careTitle() {
        return "你并不孤单";
    }
}
