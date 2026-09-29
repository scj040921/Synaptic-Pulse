package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/matches")
public class MatchController {
    private final AuthService auth;
    private final UserService users;

    public MatchController(AuthService auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    @GetMapping
    public List<Match> matches(HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        Set<String> ownInterests = new HashSet<>(users.interests(ownId));
        return users.candidates(ownId).stream().map(candidate -> {
            Set<String> common = new HashSet<>(candidate.interests());
            common.retainAll(ownInterests);
            Set<String> all = new HashSet<>(candidate.interests());
            all.addAll(ownInterests);
            // 第一阶段基线：Jaccard(共同标签 / 标签并集)，方便解释与后续模型对照。
            int score = all.isEmpty() ? 0 : Math.round(common.size() * 100f / all.size());
            return new Match(candidate, score, common.stream().sorted().toList());
        }).sorted(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(match -> match.user().id(), Comparator.reverseOrder()))
                .limit(20).toList();
    }

    public record Match(UserService.UserView user, int score, List<String> commonInterests) { }
}
