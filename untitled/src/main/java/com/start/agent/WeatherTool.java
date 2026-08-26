package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.repository.UserAliasRepository;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 天气查询工具（高德地图版）。
 *
 * <p>采用高德 Web 服务 API：先查内置 adcode 表定位城市，再调 weatherInfo。
 * 不维护静态城市映射表（adcode 表是只读的，零网络成本）。
 *
 * <p>地点优先级：primary_location > secondary_location > 询问用户
 */
public class WeatherTool implements Tool {
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private final UserAliasRepository aliasRepo;

    /** 高德 API Key，从 application.properties 注入。空字符串表示未配置。 */
    private final String gaodeApiKey;

    public WeatherTool(UserAliasRepository aliasRepo, String gaodeApiKey) {
        this.aliasRepo = aliasRepo;
        String key = (gaodeApiKey == null) ? "" : gaodeApiKey.trim();
        this.gaodeApiKey = key.isEmpty() ? loadGaodeKey() : key;
    }

    public WeatherTool(UserAliasRepository aliasRepo) {
        this(aliasRepo, "");
    }

    public WeatherTool() {
        this(new UserAliasRepository(), "");
    }

    /**
     * 兜底加载高德 Key。
     * <p>优先级：系统属性 {@code gaode.api-key}（IDE/Maven 注入） > 环境变量 {@code GAODE_API_KEY}（服务器部署）。
     * <p>为什么不在 BotConfig 加 getter：BotConfig.java 属于 SelfEvolveTool 硬阻断的安全文件。
     */
    private static String loadGaodeKey() {
        String sys = System.getProperty("gaode.api-key");
        if (sys != null && !sys.isBlank()) return sys.trim();
        String env = System.getenv("GAODE_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        return "";
    }

    @Override public String getName() { return "get_weather"; }

    @Override
    public String getDescription() {
        return "查询天气。用户明确说城市→直接用；用户没指定→city填UNKNOWN，系统自动用主地点。days默认1，最多7天预报。绝不要自己猜城市。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名（中文/拼音/区名），未指定填 UNKNOWN"),
                        "user_id", Map.of("type", "string", "description", "当前用户 ID，用于查记忆中的地点"),
                        "days", Map.of("type", "string", "description", "预报天数，默认1，最多7")
                ),
                "required", Arrays.asList("city"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (gaodeApiKey.isEmpty()) {
            return "天气功能未启用：缺少高德 API Key。请联系管理员配置 GAODE_API_KEY。";
        }

        String city = (String) args.get("city");
        String userId = (String) args.get("user_id");
        String groupId = (String) args.getOrDefault("group_id", "0");
        int days = parseIntSafe((String) args.get("days"), 1);
        if (days < 1) days = 1;
        if (days > 7) days = 7;

        if (city == null || city.trim().isEmpty() || "UNKNOWN".equalsIgnoreCase(city.trim())) {
            if (userId != null && !userId.isEmpty()) {
                Optional<String> loc = aliasRepo.getLocation(userId, groupId);
                if (loc.isPresent()) { city = loc.get(); }
                else { return "我不知道你在哪个城市，可以告诉我吗？比如'我在北京'"; }
            } else { return "我不知道你在哪个城市，可以告诉我吗？"; }
        }

        String originalCity = city.trim();

        // 查完后写入 secondary_location
        if (userId != null && !userId.isEmpty() && !"UNKNOWN".equalsIgnoreCase(originalCity)) {
            aliasRepo.updateLocation(userId, groupId, originalCity, false);
        }

        // 1. 解析城市 → adcode
        ResolvedCity resolved = resolveCity(originalCity);
        if (resolved == null) {
            return "未找到城市 [" + originalCity + "]。试试标准写法，比如'北京'、'上海'、'广州'？";
        }

        try {
            // 2. 实时天气
            String liveUrl = "https://restapi.amap.com/v3/weather/weatherInfo?key=" + gaodeApiKey
                    + "&city=" + resolved.adcode + "&extensions=base";
            JsonNode liveBody = httpGetJson(liveUrl);
            if (!"1".equals(liveBody.path("status").asText())) {
                String info = liveBody.path("info").asText("未知错误");
                String code = liveBody.path("infocode").asText("");
                if ("10001".equals(code) || "10002".equals(code) || "10003".equals(code)) {
                    return "天气服务鉴权失败（" + info + "），请检查 GAODE_API_KEY 是否正确。";
                }
                if ("10020".equals(code)) {
                    return "天气服务限流了，稍等一下再试～";
                }
                return "天气服务异常：" + info;
            }

            JsonNode live = liveBody.path("lives").path(0);
            if (live.isMissingNode()) return "未获取到 [" + resolved.displayName + "] 的实时天气。";

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📍%s 当前：%s，%s°C，%s%s风，湿度%s%%",
                    resolved.displayName,
                    live.path("weather").asText("未知"),
                    live.path("temperature").asText("--"),
                    live.path("winddirection").asText(""),
                    live.path("windpower").asText(""),
                    live.path("humidity").asText("--")));
            String reportTime = live.path("reporttime").asText("");
            if (!reportTime.isEmpty()) {
                sb.append("（").append(reportTime).append(" 更新）");
            }

            // 3. 预报天气
            if (days > 1) {
                String fcUrl = "https://restapi.amap.com/v3/weather/weatherInfo?key=" + gaodeApiKey
                        + "&city=" + resolved.adcode + "&extensions=all";
                JsonNode fcBody = httpGetJson(fcUrl);
                if ("1".equals(fcBody.path("status").asText())) {
                    JsonNode casts = fcBody.path("forecasts").path(0).path("casts");
                    if (casts.isArray()) {
                        int n = Math.min(days, casts.size());
                        String[] dayLabels = {"今日", "明日", "后天", "大后天", "第5天", "第6天", "第7天"};
                        for (int i = 0; i < n; i++) {
                            JsonNode cast = casts.get(i);
                            String dayWeather = cast.path("dayweather").asText();
                            String nightWeather = cast.path("nightweather").asText();
                            String dayTemp = cast.path("daytemp").asText("--");
                            String nightTemp = cast.path("nighttemp").asText("--");
                            String dayWind = cast.path("daywind").asText("");
                            String dayPower = cast.path("daypower").asText("");
                            String label = i < dayLabels.length ? dayLabels[i] : cast.path("date").asText("").substring(5);
                            String weatherDesc = dayWeather.equals(nightWeather) ? dayWeather
                                    : dayWeather + "转" + nightWeather;
                            sb.append(String.format(" | %s：%s，%s~%s°C，%s%s",
                                    label, weatherDesc, nightTemp, dayTemp, dayWind, dayPower));
                        }
                    }
                }
            }

            return sb.toString();

        } catch (IOException | InterruptedException e) {
            return "网络请求失败，稍后再试。";
        } catch (Exception e) {
            return "解析天气数据出错。";
        }
    }

    /**
     * 解析城市名 → adcode。
     * 输入支持：标准名（如"北京"）、市名后缀（"北京市"）、拼音（"beijing"）、区名（"朝阳"→北京）。
     */
    private ResolvedCity resolveCity(String input) {
        if (input == null) return null;
        String key = input.trim().toLowerCase().replace("市", "");

        // 1. 直接查（用户可能输入了"北京"/"上海"等）
        String adcode = CITY_ADCODE.get(key);
        if (adcode != null) {
            return new ResolvedCity(adcode, canonicalName(key, adcode));
        }

        // 2. 查区名（自动归到所属市）
        for (Map.Entry<String, String> e : DISTRICT_TO_CITY.entrySet()) {
            if (e.getKey().equalsIgnoreCase(input.trim()) || e.getKey().equalsIgnoreCase(key)) {
                String cityAdcode = e.getValue();
                return new ResolvedCity(cityAdcode, canonicalName(input.trim(), cityAdcode));
            }
        }

        // 3. 拼音匹配（取第一个字母匹配）
        if (key.matches("[a-z]+")) {
            for (Map.Entry<String, String> e : PINYIN_TO_ADCODE.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key)) {
                    return new ResolvedCity(e.getValue(), canonicalName(e.getValue(), e.getValue()));
                }
            }
        }

        return null;
    }

    private String canonicalName(String key, String adcode) {
        // 优先用主表里的中文名
        for (Map.Entry<String, String> e : CITY_ADCODE.entrySet()) {
            if (e.getValue().equals(adcode) && !e.getKey().matches("[a-z]+") && e.getKey().length() >= 2) {
                return e.getKey();
            }
        }
        return key;
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private JsonNode httpGetJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", "CandyBearBot/1.0 (QQ-bot weather query)")
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }

    private record ResolvedCity(String adcode, String displayName) {}

    // ============================================================
    // 内置 adcode 表（覆盖 4 直辖市 + 27 省会 + 港澳 + 30 常见地级市 + 拼音/区名别名）
    // 数据来源：高德地图官方 adcode 表（2024 版）
    // 拼音不带声调，简写只收录最常见 1-2 个字母
    // ============================================================

    private static final Map<String, String> CITY_ADCODE = new HashMap<>();
    private static final Map<String, String> PINYIN_TO_ADCODE = new HashMap<>();
    private static final Map<String, String> DISTRICT_TO_CITY = new HashMap<>();

    static {
        // 4 直辖市
        putCity("北京", "110000", "beijing", "bj");
        putCity("天津", "120000", "tianjin", "tj");
        putCity("上海", "310000", "shanghai", "sh");
        putCity("重庆", "500000", "chongqing", "cq");

        // 特别行政区
        putCity("香港", "810000", "hongkong", "xianggang", "hk");
        putCity("澳门", "820000", "macau", "aomen", "mo");

        // 27 省会（直辖市已加）
        putCity("石家庄", "130100", "shijiazhuang", "sjz");
        putCity("太原", "140100", "taiyuan", "ty");
        putCity("呼和浩特", "150100", "huhehaote", "hhht");
        putCity("沈阳", "210100", "shenyang", "sy");
        putCity("长春", "220100", "changchun", "cc");
        putCity("哈尔滨", "230100", "haerbin", "heb", "hrb");
        putCity("南京", "320100", "nanjing", "nj");
        putCity("杭州", "330100", "hangzhou", "hz");
        putCity("合肥", "340100", "hefei", "hf");
        putCity("福州", "350100", "fuzhou", "fz");
        putCity("南昌", "360100", "nanchang", "nc");
        putCity("济南", "370100", "jinan", "jn");
        putCity("郑州", "410100", "zhengzhou", "zz");
        putCity("武汉", "420100", "wuhan", "wh");
        putCity("长沙", "430100", "changsha", "cs");
        putCity("广州", "440100", "guangzhou", "gz");
        putCity("南宁", "450100", "nanning", "nn");
        putCity("海口", "460100", "haikou", "hkhn");
        putCity("成都", "510100", "chengdu", "cd");
        putCity("贵阳", "520100", "guiyang", "gy");
        putCity("昆明", "530100", "kunming", "km");
        putCity("拉萨", "540100", "lasa", "ls");
        putCity("西安", "610100", "xian", "xa");
        putCity("兰州", "620100", "lanzhou", "lz");
        putCity("西宁", "630100", "xining", "xn");
        putCity("银川", "640100", "yinchuan", "yc");
        putCity("乌鲁木齐", "650100", "wulumuqi", "wlmq");
        putCity("台北", "710000", "taibei", "taipei", "tb");

        // 30 常见地级市
        putCity("深圳", "440300", "shenzhen", "sz");
        putCity("东莞", "441900", "dongguan", "dg");
        putCity("佛山", "440600", "foshan", "fs");
        putCity("中山", "442000", "zhongshan", "zs");
        putCity("珠海", "440400", "zhuhai", "zh");
        putCity("惠州", "441300", "huizhou", "hzo");
        putCity("汕头", "440500", "shantou", "st");
        putCity("厦门", "350200", "xiamen", "xm");
        putCity("泉州", "350500", "quanzhou", "qz");
        putCity("苏州", "320500", "suzhou", "szh");
        putCity("无锡", "320200", "wuxi", "wx");
        putCity("常州", "320400", "changzhou", "cz");
        putCity("南通", "320600", "nantong", "nt");
        putCity("宁波", "330200", "ningbo", "nb");
        putCity("温州", "330300", "wenzhou", "wz");
        putCity("嘉兴", "330400", "jiaxing", "jx");
        putCity("绍兴", "330600", "shaoxing", "sx");
        putCity("金华", "330700", "jinhua", "jh");
        putCity("青岛", "370200", "qingdao", "qd");
        putCity("烟台", "370600", "yantai", "yt");
        putCity("潍坊", "370700", "weifang", "wf");
        putCity("临沂", "371300", "linyi", "ly");
        putCity("徐州", "320300", "xuzhou", "xz");
        putCity("连云港", "320700", "lianyungang", "lyg");
        putCity("大连", "210200", "dalian", "dl");
        putCity("鞍山", "210300", "anshan", "as");
        putCity("唐山", "130200", "tangshan", "ts");
        putCity("秦皇岛", "130300", "qinhuangdao", "qhd");
        putCity("保定", "130600", "baoding", "bd");
        putCity("三亚", "460200", "sanya", "syhn");
    }

    static {
        // 区名 → 所属市 adcode
        DISTRICT_TO_CITY.put("朝阳", "110000");  // 北京朝阳
        DISTRICT_TO_CITY.put("海淀", "110000");
        DISTRICT_TO_CITY.put("丰台", "110000");
        DISTRICT_TO_CITY.put("昌平", "110000");
        DISTRICT_TO_CITY.put("通州", "110000");
        DISTRICT_TO_CITY.put("浦东", "310000");
        DISTRICT_TO_CITY.put("黄浦", "310000");
        DISTRICT_TO_CITY.put("徐汇", "310000");
        DISTRICT_TO_CITY.put("天河", "440100");
        DISTRICT_TO_CITY.put("越秀", "440100");
        DISTRICT_TO_CITY.put("南山", "440300");
        DISTRICT_TO_CITY.put("福田", "440300");
        DISTRICT_TO_CITY.put("罗湖", "440300");
    }

    private static void putCity(String name, String adcode, String... aliases) {
        CITY_ADCODE.put(name, adcode);
        for (String alias : aliases) {
            PINYIN_TO_ADCODE.put(alias, adcode);
        }
    }
}
