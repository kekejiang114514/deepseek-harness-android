package com.dsh.android.kernel;

/**
 * 项目信息。
 *
 * <p>只保留与项目本身相关的公开信息（仓库、issue 入口）。
 * 不记录任何个人联系方式或私人描述。
 */
public final class About {

    /** 项目名 */
    public static final String NAME = "DShAndroid";

    public static final String REPO = "https://github.com/kekejiang114514/deepseek-harness-android";
    public static final String REPO_NAME = "kekejiang114514/deepseek-harness-android";

    /** 仓库主页，用于反馈问题 */
    public static final String GITHUB = "https://github.com/kekejiang114514/deepseek-harness-android";
    public static final String ISSUES = REPO + "/issues";

    /** 关于页底部的说明文字。 */
    public static final String FOOTER =
            "本项目以 MIT 协议开源，仓库里的构建脚本可以自己跑一遍再签名。\n"
            + "问题和建议请提到仓库的 issue。";

    private About() {}
}
