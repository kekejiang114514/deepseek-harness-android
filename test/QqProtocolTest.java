import com.dsh.android.kernel.QqProtocol;
import org.json.JSONObject;

/** QQ 协议层的纯 JVM 测试：拼装与解析，不发任何请求。 */
public final class QqProtocolTest {

    private static int pass = 0, fail = 0;
    private static void ok(boolean c, String w) {
        if (c) { pass++; System.out.println("  PASS  " + w); }
        else { fail++; System.out.println("  FAIL  " + w); }
    }

    public static void main(String[] a) throws Exception {
        System.out.println("t1 鉴权串与 intents");
        ok("QQBot abc".equals(QqProtocol.authToken("abc")), "QQBot 前缀");
        ok("QQBot ".equals(QqProtocol.authToken(null)), "null 不炸");
        ok(QqProtocol.INTENT_GROUP_AND_C2C == (1 << 25), "群聊/单聊 intent 位");

        System.out.println("t2 identify");
        JSONObject id = new JSONObject(QqProtocol.identify("tok", QqProtocol.INTENT_GROUP_AND_C2C));
        ok(id.optInt("op") == 2, "op=2");
        ok("QQBot tok".equals(id.getJSONObject("d").optString("token")), "token 正确");
        ok(id.getJSONObject("d").optInt("intents") == (1 << 25), "intents 正确");

        System.out.println("t3 心跳");
        ok(new JSONObject(QqProtocol.heartbeat(5L)).optInt("op") == 1, "op=1");
        ok(new JSONObject(QqProtocol.heartbeat(null)).opt("d") == JSONObject.NULL, "无序号时发 null");

        System.out.println("t4 解析帧");
        QqProtocol.Frame f = QqProtocol.parseFrame(
                "{\"op\":0,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"s\":12,\"d\":{}}");
        ok(f != null && f.op == 0, "op 解析");
        ok("GROUP_AT_MESSAGE_CREATE".equals(f.type), "事件名解析");
        ok(f.seq == 12, "序号解析");
        ok(QqProtocol.parseFrame("不是 json") == null, "坏数据返回 null");
        ok(QqProtocol.parseFrame("") == null, "空串返回 null");

        System.out.println("t5 收到群消息");
        String raw = "{\"op\":0,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"s\":3,\"d\":{"
                + "\"id\":\"msg-1\",\"content\":\" 你好\",\"group_openid\":\"G1\","
                + "\"author\":{\"id\":\"U1\"}}}";
        QqProtocol.Incoming in = QqProtocol.incoming(QqProtocol.parseFrame(raw));
        ok(in != null, "解析出消息");
        ok("group".equals(in.kind), "类型为群");
        ok("G1".equals(in.openid), "群 openid");
        ok("msg-1".equals(in.msgId), "消息 id（被动回复要用）");
        ok("你好".equals(in.content), "去掉 @ 后的前导空格");
        ok("group:msg-1".equals(in.dedupKey()), "去重键");

        System.out.println("t6 单聊与忽略其它事件");
        String c2c = "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"m2\","
                + "\"content\":\"hi\",\"author\":{\"id\":\"U9\",\"user_openid\":\"OPEN9\"}}}";
        QqProtocol.Incoming in2 = QqProtocol.incoming(QqProtocol.parseFrame(c2c));
        ok(in2 != null && "c2c".equals(in2.kind), "单聊类型");
        ok("OPEN9".equals(in2.openid), "单聊用 author.user_openid");
        ok("U9".equals(in2.authorId), "author.id 单独保留");
        ok(QqProtocol.incoming(QqProtocol.parseFrame(
                "{\"op\":0,\"t\":\"READY\",\"d\":{}}")) == null, "其它事件忽略");

        System.out.println("t7 发送体");
        JSONObject body = new JSONObject(QqProtocol.messageBody("回复", "msg-1", 1));
        ok("回复".equals(body.optString("content")), "正文");
        ok(body.optInt("msg_type") == 0, "文本类型");
        ok("msg-1".equals(body.optString("msg_id")), "被动回复带 msg_id");
        ok(body.optInt("msg_seq") == 1, "序号从 1 开始");
        JSONObject active = new JSONObject(QqProtocol.messageBody("主动", "", 0));
        ok(!active.has("msg_id"), "主动消息不带 msg_id");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
