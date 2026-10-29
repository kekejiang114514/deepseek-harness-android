package com.dsh.android.plugins;

/**
 * 给用户抄的示例清单。设置页「查看示例」直接展示这段文本，可复制后改。
 *
 * 刻意选了不需要 API Key 的接口，用户粘出去就能跑通，再照着改自己的服务。
 */
public final class PluginSample {

    private PluginSample() {}

    public static final String JSON =
        "{\n" +
        "  \"id\": \"demo\",\n" +
        "  \"name\": \"示例插件\",\n" +
        "  \"version\": \"1.0.0\",\n" +
        "  \"description\": \"两个不需要密钥的接口，改改就能接自己的服务\",\n" +
        "  \"author\": \"你\",\n" +
        "  \"baseUrl\": \"https://geocoding-api.open-meteo.com\",\n" +
        "  \"headers\": {\n" +
        "    \"Accept\": \"application/json\"\n" +
        "  },\n" +
        "  \"secrets\": {\n" +
        "    \"TOKEN\": \"如果你的接口要密钥，填这里，下面用 ${TOKEN} 引用\"\n" +
        "  },\n" +
        "  \"tools\": [\n" +
        "    {\n" +
        "      \"name\": \"demo.city\",\n" +
        "      \"description\": \"按城市名查询经纬度。用户问某地在哪、要查当地天气时先用它。\",\n" +
        "      \"parameters\": {\n" +
        "        \"type\": \"object\",\n" +
        "        \"properties\": {\n" +
        "          \"city\": { \"type\": \"string\", \"description\": \"城市名，中文或英文\" }\n" +
        "        },\n" +
        "        \"required\": [\"city\"]\n" +
        "      },\n" +
        "      \"request\": {\n" +
        "        \"method\": \"GET\",\n" +
        "        \"url\": \"/v1/search?name={city}&count=1&language=zh\",\n" +
        "        \"resultPath\": \"results[0]\",\n" +
        "        \"timeoutMs\": 15000\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"name\": \"demo.weather\",\n" +
        "      \"description\": \"按经纬度查当前天气。\",\n" +
        "      \"parameters\": {\n" +
        "        \"type\": \"object\",\n" +
        "        \"properties\": {\n" +
        "          \"lat\": { \"type\": \"number\", \"description\": \"纬度\" },\n" +
        "          \"lon\": { \"type\": \"number\", \"description\": \"经度\" }\n" +
        "        },\n" +
        "        \"required\": [\"lat\", \"lon\"]\n" +
        "      },\n" +
        "      \"request\": {\n" +
        "        \"method\": \"GET\",\n" +
        "        \"url\": \"https://api.open-meteo.com/v1/forecast?latitude={lat|raw}&longitude={lon|raw}&current=temperature_2m,weather_code\",\n" +
        "        \"resultPath\": \"current\"\n" +
        "      }\n" +
        "    }\n" +
        "  ]\n" +
        "}\n";

    /** 字段速查，界面上一并给出，省得用户回去翻文档。 */
    public static final String CHEATSHEET =
        "• id       必填，字母数字 _ -，全局唯一\n" +
        "• baseUrl  可选，相对 url 会拼在它后面\n" +
        "• headers  可选，值里能写 ${密钥名}\n" +
        "• secrets  可选，密钥写这里，模板里用 ${名} 引用\n" +
        "• tools[]  必填，每个工具 = 一段描述 + 一次 HTTP 请求\n" +
        "\n" +
        "请求字段：method（GET/POST/PUT/PATCH/DELETE）、url、body、\n" +
        "contentType（json/form/其它）、resultPath、timeoutMs。\n" +
        "\n" +
        "占位符：{参数} URL 编码 · {参数|raw} 原样 · {参数|json} JSON 转义\n" +
        "resultPath 支持 a.b 与 list[0].name。";
}
