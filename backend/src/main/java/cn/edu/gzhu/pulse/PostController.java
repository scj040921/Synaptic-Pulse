package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private final JdbcTemplate db;
    private final AuthService auth;

    public PostController(JdbcTemplate db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }

    @GetMapping
    public List<Post> list(HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        return db.query("SELECT p.id, p.author_id, u.display_name, p.content, p.created_at " +
                        "FROM posts p JOIN users u ON u.id = p.author_id " +
                        "WHERE p.author_id NOT IN (SELECT blocked_id FROM blocks WHERE blocker_id = ?) " +
                        "AND p.author_id NOT IN (SELECT blocker_id FROM blocks WHERE blocked_id = ?) " +
                        "ORDER BY p.id DESC LIMIT 50",
                (rs, row) -> new Post(rs.getLong("id"), rs.getLong("author_id"),
                        rs.getString("display_name"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), ownId, ownId);
    }

    @PostMapping
    public Post create(@Valid @RequestBody NewPost input, HttpServletRequest request) {
        long authorId = auth.requireUser(request);
        String content = input.content().trim();
        if (content.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "内容不能为空");
        KeyHolder keys = new GeneratedKeyHolder();
        db.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO posts(author_id, content) VALUES (?, ?)",
                    new String[] { "ID" });
            ps.setLong(1, authorId);
            ps.setString(2, content);
            return ps;
        }, keys);
        return db.queryForObject("SELECT p.id, p.author_id, u.display_name, p.content, p.created_at " +
                        "FROM posts p JOIN users u ON u.id = p.author_id " +
                        "WHERE p.id = ?",
                (rs, row) -> new Post(rs.getLong("id"), rs.getLong("author_id"),
                        rs.getString("display_name"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), keys.getKey().longValue());
    }

    public record NewPost(@NotBlank @Size(max = 2000) String content) { }
    public record Post(long id, long authorId, String authorName, String content, LocalDateTime createdAt) { }
}
