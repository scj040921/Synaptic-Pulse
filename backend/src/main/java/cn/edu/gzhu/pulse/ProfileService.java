package cn.edu.gzhu.pulse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Optional, self-described preferences. They are not psychological assessments. */
@Service
public class ProfileService {
    private final ObjectMapper json;
    public ProfileService(ObjectMapper json) { this.json = json; }

    private static final List<Group> GROUPS = List.of(
        group("grade", "学习阶段", 1, "大一", "大二", "大三", "大四", "大五及以上", "硕士研究生", "博士研究生", "已毕业", "其他"),
        group("personality", "性格自述", 4, "慢热", "开朗", "善于倾听", "喜欢思考", "好奇心强", "行动派", "有计划", "随性", "细心", "幽默", "安静", "乐于分享"),
        group("goals", "想认识怎样的伙伴", 3, "兴趣交流", "学习搭子", "运动搭子", "活动同行", "创作合作", "语言交流", "认识新朋友"),
        group("communication", "交流方式", 1, "轻松随聊", "围绕共同话题", "偏好深入交流", "先倾听再回应", "喜欢主动破冰", "没有固定偏好"),
        group("pace", "社交节奏", 1, "先在线聊聊", "熟悉后再线下见面", "愿意参加公开活动", "慢慢建立联系", "没有固定偏好"),
        group("groupSize", "活动人数偏好", 1, "一对一交流", "三到五人小组", "多人社团活动", "都可以"),
        group("availability", "通常方便的时间", 4, "工作日白天", "工作日晚上", "周末白天", "周末晚上")
    );
    private static final List<Group> INTERESTS = List.of(
        group("sports", "运动户外", 10, "篮球", "羽毛球", "跑步", "足球", "乒乓球", "游泳", "骑行", "徒步", "健身", "瑜伽"),
        group("arts", "艺术创作", 10, "摄影", "绘画", "设计", "写作", "视频剪辑", "手工", "书法", "舞蹈"),
        group("culture", "音乐影视", 10, "音乐", "吉他", "钢琴", "唱歌", "电影", "动漫", "戏剧", "播客"),
        group("tech", "科技学习", 10, "编程", "人工智能", "机器人", "电子制作", "数学", "科学探索", "英语", "学术研究"),
        group("games", "游戏休闲", 10, "桌游", "电子游戏", "围棋", "象棋", "剧本杀", "拼图", "阅读"),
        group("life", "校园生活", 10, "美食", "旅行", "咖啡", "烘焙", "园艺", "志愿服务", "社团活动", "校园探索")
    );

    private static Group group(String key, String title, int max, String... options) {
        return new Group(key, title, max, List.of(options));
    }
    public Catalog catalog() { return new Catalog(GROUPS, INTERESTS); }
    public Portrait empty() { return new Portrait("", "", Map.of()); }

    public Portrait normalize(Portrait input) {
        if (input == null) return empty();
        String college = clean(input.college()); String major = clean(input.major());
        if (college.length() > 80 || major.length() > 80) throw bad("院系与专业各最多 80 字");
        Map<String, List<String>> choices = input.choices() == null ? Map.of() : input.choices();
        if (choices.size() > GROUPS.size() || choices.keySet().stream().anyMatch(key -> GROUPS.stream().noneMatch(g -> g.key().equals(key)))) {
            throw bad("画像包含不支持的选项，请刷新页面后重试");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Group group : GROUPS) {
            List<String> values = choices.get(group.key());
            if (values == null) continue;
            if (values.size() > group.max() || values.stream().anyMatch(value -> value == null || !group.options().contains(value))) {
                throw bad(group.title() + "选项无效，最多选择 " + group.max() + " 项");
            }
            List<String> normalized = List.copyOf(new LinkedHashSet<>(values));
            if (!normalized.isEmpty()) result.put(group.key(), normalized);
        }
        return new Portrait(college, major, result);
    }
    private static String clean(String text) { return text == null ? "" : text.strip(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }

    public Portrait read(String value) {
        if (value == null || value.isBlank()) return empty();
        try { return normalize(json.readValue(value, Portrait.class)); }
        catch (JsonProcessingException error) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "个人画像读取失败"); }
    }
    public String write(Portrait value) {
        try { return json.writeValueAsString(normalize(value)); }
        catch (JsonProcessingException error) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "个人画像保存失败"); }
    }
    public record Group(String key, String title, int max, List<String> options) { }
    public record Catalog(List<Group> groups, List<Group> interests) { }
    public record Portrait(String college, String major, Map<String, List<String>> choices) { }
}
