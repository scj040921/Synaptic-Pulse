package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
public class WritingController {
    private final AuthService auth; private final AiService ai; private final UserService users;
    public WritingController(AuthService auth, AiService ai, UserService users) { this.auth=auth; this.ai=ai; this.users=users; }
    @PostMapping("/assist")
    public WritingReply assist(@Valid @RequestBody WritingRequest input, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        if (input.mode() != null && !List.of("offline", "ai").contains(input.mode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的文案辅助模式");
        }
        String draft; String source;
        if ("ai".equals(input.mode())) {
            if (!input.consent()) throw new ApiException(HttpStatus.BAD_REQUEST, "请先同意发送当前文案");
            draft = ai.complete("帮助大学生润色校园社区动态，保持原意，使用自然简洁的中文，最多400字。不要编造活动、时间、地点或个人经历，结尾可提出一个友善的问题。仅输出文案。",
                    List.of(Map.of("role", "user", "content", input.prompt())));
            source = "ai";
        } else {
            draft = input.prompt().strip();
            if (!draft.endsWith("？") && !draft.endsWith("?")) draft += "\n有同样兴趣的同学吗？欢迎一起聊聊。";
            source = "offline";
        }
        return new WritingReply(draft.substring(0, Math.min(draft.length(), 2000)), users.interests(ownId).stream().limit(5).toList(), source);
    }
    public record WritingRequest(@NotBlank @Size(max=2000) String prompt, String mode, boolean consent) { }
    public record WritingReply(String draft, List<String> topics, String source) { }
}
