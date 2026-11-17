import com.dsh.android.kernel.ChatImport;

/** 导入解析测试：五种来源各一份真实结构的样本。 */
public final class ChatImportTest {
    static int pass = 0, fail = 0;

    static void ok(boolean c, String what) {
        if (c) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    /** 造一个 zip：条目名 → 内容，用来验证「DeepSeek 导出是 zip」这条路径。 */
    static byte[] zip(String name, String text) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(bo);
        zo.putNextEntry(new java.util.zip.ZipEntry(name));
        zo.write(text.getBytes("UTF-8"));
        zo.closeEntry();
        zo.close();
        return bo.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        System.out.println("t1 DeepSeek 导出");
        String deepseek = "{\"chat_sessions\":[{\"id\":\"s1\",\"title\":\"部署问题\"}],"
                + "\"chat_messages\":["
                + "{\"chat_session_id\":\"s1\",\"role\":\"assistant\",\"content\":\"第二句\",\"inserted_at\":200},"
                + "{\"chat_session_id\":\"s1\",\"role\":\"user\",\"content\":\"第一句\",\"inserted_at\":100,\"thinking\":\"\"},"
                + "{\"chat_session_id\":\"s1\",\"role\":\"assistant\",\"content\":\"带思考\",\"inserted_at\":300,\"thinking\":\"先看文档\"}]}";
        ChatImport.Result r = ChatImport.parse(deepseek);
        ok(r.format.contains("DeepSeek"), "识别为 DeepSeek 导出");
        ok(r.sessions.size() == 1, "1 个会话");
        ok("部署问题".equals(r.sessions.get(0).title), "标题取会话表");
        ok(r.sessions.get(0).messages.length() == 3, "3 条消息");
        ok("第一句".equals(r.sessions.get(0).messages.getJSONObject(0).optString("content")), "按时间排序");
        ok("先看文档".equals(r.sessions.get(0).messages.getJSONObject(2).optString("reasoning")), "thinking 转成 reasoning");

        System.out.println("t2 ChatGPT 导出（保留分支）");
        String gpt = "[{\"title\":\"写脚本\",\"mapping\":{"
                + "\"n0\":{\"parent\":null,\"message\":{\"author\":{\"role\":\"system\"},\"content\":{\"parts\":[\"你是助手\"]}}},"
                + "\"n1\":{\"parent\":\"n0\",\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"parts\":[\"帮我写\"]}}},"
                + "\"n2\":{\"parent\":\"n1\",\"message\":{\"author\":{\"role\":\"assistant\"},\"content\":{\"parts\":[\"好的，第一版\"]}}},"
                + "\"n3\":{\"parent\":\"n1\",\"message\":{\"author\":{\"role\":\"assistant\"},\"content\":{\"parts\":[\"换个写法\"]}}},"
                + "\"n4\":{\"parent\":\"n3\",\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"parts\":[\"继续\"]}}}"
                + "}}]";
        ChatImport.Result g = ChatImport.parse(gpt);
        ok(g.format.contains("ChatGPT"), "识别为 ChatGPT 导出");
        ok(g.sessions.size() == 1, "1 个会话");
        ok(g.sessions.get(0).messages.length() == 5, "5 条消息（含 system）");
        int withParent = 0;
        for (int i = 0; i < g.sessions.get(0).messages.length(); i++) {
            if (g.sessions.get(0).messages.getJSONObject(i).optString("parent", "").length() > 0) withParent++;
        }
        ok(withParent == 4, "父子关系保留了 4 条");
        ok(g.sessions.get(0).messages.getJSONObject(0).optString("role").equals("system"), "system 保留");

        System.out.println("t3 Claude 导出");
        String claude = "[{\"uuid\":\"c1\",\"name\":\"翻译\",\"chat_messages\":["
                + "{\"sender\":\"human\",\"text\":\"翻一段\"},"
                + "{\"sender\":\"assistant\",\"text\":\"好\"}]}]";
        ChatImport.Result c = ChatImport.parse(claude);
        ok(c.format.contains("Claude"), "识别为 Claude 导出");
        ok("翻译".equals(c.sessions.get(0).title), "标题取 name");
        ok("user".equals(c.sessions.get(0).messages.getJSONObject(0).optString("role")), "human → user");

        System.out.println("t4 通用消息数组");
        ChatImport.Result p = ChatImport.parse("[{\"role\":\"user\",\"content\":\"你好\"},{\"role\":\"assistant\",\"content\":\"你好呀\"}]");
        ok(p.sessions.size() == 1 && p.messageCount() == 2, "通用数组 2 条");

        System.out.println("t5 messages 包裹");
        ChatImport.Result q = ChatImport.parse("{\"title\":\"导出\",\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
        ok("导出".equals(q.sessions.get(0).title), "取外层 title");

        System.out.println("t6 JSONL");
        ChatImport.Result j = ChatImport.parse("{\"role\":\"user\",\"content\":\"一\"}\n{\"role\":\"assistant\",\"content\":\"二\"}\n坏行");
        ok(j.sessions.size() == 1 && j.messageCount() == 2, "JSONL 收下 2 条，坏行跳过");

        System.out.println("t7 DShAndroid 自己导出（含分支）");
        String own = "{\"app\":\"DShAndroid\",\"schema\":1,\"sessions\":[{\"id\":\"x\",\"title\":\"分支会话\",\"group\":\"工作\","
                + "\"messages\":[{\"id\":\"a\",\"parent\":null,\"role\":\"user\",\"content\":\"问\"},"
                + "{\"id\":\"b\",\"parent\":\"a\",\"role\":\"assistant\",\"content\":\"答一\"},"
                + "{\"id\":\"c\",\"parent\":\"b\",\"role\":\"user\",\"content\":\"追问\"}]}]}";
        ChatImport.Result o = ChatImport.parse(own);
        ok(o.format.contains("DShAndroid"), "识别为自己的导出");
        ok("工作".equals(o.sessions.get(0).group), "分组带过来了");
        ok(o.sessions.get(0).messages.length() == 3, "3 条消息");
        ok("a".equals(o.sessions.get(0).messages.getJSONObject(1).optString("parent"))
                && "b".equals(o.sessions.get(0).messages.getJSONObject(2).optString("parent")),
                "父子关系原样保留");

        System.out.println("t8 认不出的文件要明确报错");
        boolean threw = false;
        try { ChatImport.parse("{\"foo\":1}"); } catch (Exception e) { threw = true; }
        ok(threw, "无法识别时抛异常，不静默成功");
        threw = false;
        try { ChatImport.parse("   "); } catch (Exception e) { threw = true; }
        ok(threw, "空文件报错");

        System.out.println("t9 角色映射");
        ok("user".equals(ChatImport.mapRole("human")), "human → user");
        ok("assistant".equals(ChatImport.mapRole("AI")), "AI → assistant");
        ok("".equals(ChatImport.mapRole("narrator")), "不认识的角色返回空，由调用方跳过");

        System.out.println("t10 zip 里的 DeepSeek 导出");
        String dse = "{\"chat_sessions\":[{\"id\":\"s1\",\"title\":\"压缩包里的会话\"}],"
                + "\"chat_messages\":[{\"chat_session_id\":\"s1\",\"role\":\"user\",\"content\":\"来自 zip\"}]}";
        ChatImport.Result z = ChatImport.parseBytes(zip("export/chat.json", dse));
        ok(z.sessions.size() == 1, "zip 解出 1 个会话");
        ok(z.format.startsWith("ZIP"), "来源标为 ZIP");
        ok("来自 zip".equals(z.sessions.get(0).messages.getJSONObject(0).optString("content")), "内容正确");
        ok("压缩包里的会话".equals(z.sessions.get(0).title), "标题取会话表");

        System.out.println("t11 zip 里没有聊天记录要报错");
        boolean zipThrew = false;
        try {
            ChatImport.parseBytes(zip("readme.txt", "这不是聊天记录"));
        } catch (Exception e) {
            zipThrew = true;
        }
        ok(zipThrew, "zip 里没有聊天记录时明确报错");

        System.out.println("t12 非 zip 的纯 JSON 字节仍走文本解析");
        ChatImport.Result plain = ChatImport.parseBytes(dse.getBytes("UTF-8"));
        ok(plain.sessions.size() == 1, "普通 json 字节也能导入");
        ok(plain.format.contains("DeepSeek"), "仍识别为 DeepSeek");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
