package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PracticeController {
    private final AuthService auth; private final AiService ai; private final JdbcTemplate db;
    public PracticeController(AuthService auth, AiService ai, JdbcTemplate db) { this.auth = auth; this.ai = ai; this.db = db; }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities(HttpServletRequest request) { auth.requireUser(request); return Map.of("ai", ai.configured()); }

    @PostMapping("/practice/respond")
    public PracticeReply respond(@Valid @RequestBody PracticeRequest input, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        String level = input.level() == null ? "新手" : input.level();
        if (!List.of("新手", "进阶", "专家").contains(level)) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择有效难度");
        String mode = input.mode() == null ? "offline" : input.mode();
        String reply; String source;
        List<String> tips = new ArrayList<>();
        if (input.message().strip().length() < 8) tips.add("补充一个具体细节，让对方更容易接话。");
        if (!input.message().contains("？") && !input.message().contains("?")) tips.add("尝试加入一个开放问题，例如“你平时怎么安排？”");
        if (tips.isEmpty()) tips.add("已经提供了具体内容和提问，接下来可以回应对方提到的细节。");
        if (mode.equals("ai")) {
            if (!input.consent()) throw new ApiException(HttpStatus.BAD_REQUEST, "请先同意发送本次练习内容");
            List<Map<String, String>> history = new ArrayList<>();
            if (input.history() != null) for (Turn turn : input.history()) {
                if (!List.of("user", "assistant").contains(turn.role())) throw new ApiException(HttpStatus.BAD_REQUEST, "练习记录类型无效");
                history.add(Map.of("role", turn.role(), "content", turn.content()));
            }
            history.add(Map.of("role", "user", "content", input.message()));
            reply = ai.complete("你是校园社交模拟伙伴，扮演场景中的同龄人。场景：" + input.scenario() + "，难度：" + level +
                    "。新手给出清晰友善回应；进阶引导讨论与协作；专家加入不同意见但保持尊重。只给出一段自然回应和一条具体沟通建议，不诊断心理问题或给出情绪评分，不索要个人身份资料。", history);
            source = "ai";
        } else if (mode.equals("offline")) {
            reply = switch (input.scenario()) {
                case "社团招新" -> "欢迎来了解我们的社团！你对哪种活动最感兴趣？我们可以从你熟悉的事情聊起。";
                case "课堂小组讨论" -> "我觉得可以先把任务拆开，再一起确认时间。你最希望负责哪一部分？";
                case "校园活动组织" -> "这个想法不错。我们先明确参与者和时间吧，你觉得怎样让第一次来的同学也能参与？";
                default -> "很高兴认识你。你刚刚提到的事情我也想多了解一些，可以说说一个具体的例子吗？";
            };
            // 本地模式按表达主题提供下一步模拟提示，不声称是模型推理。
            if (input.history() != null && !input.history().isEmpty()) {
                String message = input.message();
                if (message.contains("时间") || message.contains("周") || message.contains("下午") || message.contains("有空")) {
                    reply = "可以先提供两个候选时间，让大家分别确认。你会怎样邀请对方选择，又照顾暂时没空的同学？";
                } else if (message.contains("分工") || message.contains("负责") || message.contains("任务")) {
                    reply = "我们可以列出任务和每个人能投入的时间，再约定一次进度确认。你想先负责哪一项，为什么？";
                } else if (message.contains("不同") || message.contains("反对") || message.contains("意见")) {
                    reply = "我理解你有不同的考虑。我们先说清各自最在意的事情，再找共同点，你愿意先讲讲你的理由吗？";
                } else {
                    reply = "你补充的细节让我更了解你的想法了。我们接下来可以约定一个小步骤，你希望先尝试什么？";
                }
            }
            if (level.equals("进阶")) reply += " 如果大家的时间不一致，你会怎么协调？";
            if (level.equals("专家")) reply += " 我可能有另一种看法，你愿意先听听并一起找一个折中的方案吗？";
            source = "offline";
        } else throw new ApiException(HttpStatus.BAD_REQUEST, "练习模式无效");
        String key = input.practiceId() == null ? UUID.randomUUID().toString() : input.practiceId();
        try { db.update("INSERT INTO training_sessions(user_id, practice_key, level_name, scenario, source) VALUES (?, ?, ?, ?, ?)",
                ownId, key, level, input.scenario(), source); } catch (org.springframework.dao.DuplicateKeyException ignored) { }
        return new PracticeReply(reply, tips, source, key);
    }

    @GetMapping("/me/stats")
    public Growth stats(HttpServletRequest request) {
        long id = auth.requireUser(request);
        int posts = db.queryForObject("SELECT COUNT(*) FROM posts WHERE author_id = ?", Integer.class, id);
        int practices = db.queryForObject("SELECT COUNT(*) FROM training_sessions WHERE user_id = ?", Integer.class, id);
        int deep = db.queryForObject("SELECT COUNT(*) FROM (SELECT CASE WHEN sender_id = ? THEN receiver_id ELSE sender_id END peer " +
                "FROM messages WHERE sender_id = ? OR receiver_id = ? GROUP BY peer HAVING COUNT(*) >= 6 " +
                "AND SUM(CASE WHEN sender_id = ? THEN 1 ELSE 0 END) > 0 AND SUM(CASE WHEN receiver_id = ? THEN 1 ELSE 0 END) > 0) t", Integer.class, id, id, id, id, id);
        var levels = db.query("SELECT level_name, COUNT(*) total FROM training_sessions WHERE user_id = ? GROUP BY level_name",
                (rs, row) -> new LevelProgress(rs.getString(1), rs.getInt(2)), id);
        List<String> achievements = new ArrayList<>();
        if (posts > 0) achievements.add("突触先锋"); if (deep > 0) achievements.add("脑波同频者"); if (practices >= 3) achievements.add("社交练习者");
        return new Growth(posts, practices, deep, Math.min(posts, 50) * 2 + Math.min(practices, 20) * 5 + deep * 10, achievements, levels);
    }
    public record PracticeRequest(@NotBlank @Size(max=60) String scenario, @NotBlank @Size(max=1000) String message,
                                  String level, String mode, boolean consent, @Size(max=64) String practiceId,
                                  @Size(max=10) List<@NotNull @Valid Turn> history) { }
    public record Turn(@NotBlank String role, @NotBlank @Size(max=2000) String content) { }
    public record PracticeReply(String reply, List<String> tips, String source, String practiceId) { }
    public record LevelProgress(String level, int count) { }
    public record Growth(int postCount, int practiceCount, int deepConversations, int pulses, List<String> achievements, List<LevelProgress> levels) { }
}
