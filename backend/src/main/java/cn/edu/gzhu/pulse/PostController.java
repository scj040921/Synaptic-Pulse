package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
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
    public List<Post> list(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        if (q.length() > 80) throw new ApiException(HttpStatus.BAD_REQUEST, "搜索关键词最多 80 字");
        String search = "%" + q.trim().toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<Post> rows = db.query("SELECT p.id, p.author_id, u.display_name, u.avatar_url, p.content, p.created_at, p.topics, p.location_name, p.latitude, p.longitude " +
                        "FROM posts p JOIN users u ON u.id = p.author_id " +
                        "WHERE p.author_id NOT IN (SELECT blocked_id FROM blocks WHERE blocker_id = ?) " +
                        "AND p.author_id NOT IN (SELECT blocker_id FROM blocks WHERE blocked_id = ?) " +
                        "AND (LOWER(p.content) LIKE ? ESCAPE '!' OR LOWER(p.topics) LIKE ? ESCAPE '!' " +
                        "OR LOWER(u.display_name) LIKE ? ESCAPE '!' OR LOWER(p.location_name) LIKE ? ESCAPE '!') " +
                        "ORDER BY p.id DESC LIMIT 50",
                (rs, row) -> new Post(rs.getLong("id"), rs.getLong("author_id"),
                        rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime(), List.of(), 0, rs.getString("topics"),
                        rs.getString("location_name"), 0, false, (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude")), ownId, ownId, search, search, search, search);
        return rows.stream().map(post -> withMediaAndCount(post, ownId)).toList();
    }

    @GetMapping("/{postId}")
    public Post detail(@PathVariable long postId, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        requireVisiblePost(postId, ownId);
        return find(postId, ownId);
    }

    // Apply visibility and geographic filtering before limiting the map feed.
    public List<Post> mapPosts(Double lat, Double lng, Integer radius, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        CampusMapService.validatePosition(lat, lng, radius);
        double minLat = radius == null ? -90 : Math.max(-90, lat - radius / 110000.0);
        double maxLat = radius == null ? 90 : Math.min(90, lat + radius / 110000.0);
        List<Post> rows = db.query("SELECT p.id, p.author_id, u.display_name, u.avatar_url, p.content, p.created_at, p.topics, p.location_name, p.latitude, p.longitude " +
                "FROM posts p JOIN users u ON u.id=p.author_id WHERE p.latitude BETWEEN ? AND ? AND p.longitude IS NOT NULL " +
                "AND u.campus=(SELECT campus FROM users WHERE id=?) " +
                "AND p.author_id NOT IN (SELECT blocked_id FROM blocks WHERE blocker_id=?) " +
                "AND p.author_id NOT IN (SELECT blocker_id FROM blocks WHERE blocked_id=?) ORDER BY p.id DESC",
                (rs, row) -> new Post(rs.getLong("id"), rs.getLong("author_id"), rs.getString("display_name"), rs.getString("avatar_url"),
                    rs.getString("content"), rs.getTimestamp("created_at").toLocalDateTime(), List.of(), 0, rs.getString("topics"),
                    rs.getString("location_name"), 0, false, (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude")),
                minLat, maxLat, ownId, ownId, ownId);
        return rows.stream().filter(p -> radius == null || CampusMapService.distance(lat, lng, p.latitude(), p.longitude()) <= radius)
                .limit(100).map(p -> withMediaAndCount(p, ownId)).toList();
    }

    @PostMapping
    @Transactional
    public Post create(@Valid @RequestBody NewPost input, HttpServletRequest request) {
        long authorId = auth.requireUser(request);
        String content = input.content() == null ? "" : input.content().trim();
        List<String> imageUrls = input.imageUrls() == null ? List.of() : input.imageUrls();
        List<String> topics = input.topics() == null ? List.of() : input.topics().stream()
                .map(topic -> topic == null ? "" : topic.trim().replace("#", "")).filter(topic -> !topic.isEmpty()).distinct().toList();
        if (topics.size() > 5 || topics.stream().anyMatch(topic -> topic.length() > 30 || topic.contains(","))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "最多 5 个话题，每个最多 30 字");
        }
        CampusMapService.validatePosition(input.latitude(), input.longitude(), null);
        String location = input.locationName() == null ? "" : input.locationName().trim();
        if (content.isEmpty() && imageUrls.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写动态内容或添加图片");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        String postContent = content;
        db.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO posts(author_id, content, topics, location_name, latitude, longitude) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[] { "ID" });
            ps.setLong(1, authorId);
            ps.setString(2, postContent);
            ps.setString(3, String.join(",", topics));
            ps.setString(4, location);
            ps.setObject(5, input.latitude());
            ps.setObject(6, input.longitude());
            return ps;
        }, keys);
        long postId = keys.getKey().longValue();
        for (int i = 0; i < imageUrls.size(); i++) {
            String imageUrl = imageUrls.get(i);
            Integer owned = db.queryForObject("SELECT COUNT(*) FROM image_assets WHERE uploader_id = ? AND image_url = ?",
                    Integer.class, authorId, imageUrl);
            if (owned == null || owned != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "图片无效或不属于当前用户");
            int updated = db.update("UPDATE post_images SET post_id = ?, sort_order = ? " +
                            "WHERE uploader_id = ? AND post_id IS NULL AND image_url = ?",
                    postId, i, authorId, imageUrl);
            if (updated == 0) {
                try {
                    db.update("INSERT INTO post_images(post_id, uploader_id, image_url, sort_order) VALUES (?, ?, ?, ?)",
                            postId, authorId, imageUrl, i);
                } catch (org.springframework.dao.DuplicateKeyException ex) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "图片已用于其他动态");
                }
            }
        }
        return find(postId, authorId);
    }

    @PutMapping("/{postId}/like")
    public Post like(@PathVariable long postId, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        requireVisiblePost(postId, ownId);
        db.update("MERGE INTO post_likes(post_id, user_id) KEY(post_id, user_id) VALUES (?, ?)", postId, ownId);
        return find(postId, ownId);
    }

    @DeleteMapping("/{postId}/like")
    public Post unlike(@PathVariable long postId, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        requireVisiblePost(postId, ownId);
        db.update("DELETE FROM post_likes WHERE post_id = ? AND user_id = ?", postId, ownId);
        return find(postId, ownId);
    }

    @DeleteMapping("/{postId}")
    public void delete(@PathVariable long postId, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        if (db.update("DELETE FROM posts WHERE id = ? AND author_id = ?", postId, ownId) != 1) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能删除自己的动态");
        }
    }

    @GetMapping("/{postId}/comments")
    public List<Comment> comments(@PathVariable long postId, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        requireVisiblePost(postId, ownId);
        List<Comment> result = db.query("SELECT c.id, c.post_id, c.author_id, u.display_name, u.avatar_url, c.content, c.created_at " +
                        "FROM post_comments c JOIN users u ON u.id = c.author_id WHERE c.post_id = ? " +
                        "AND c.author_id NOT IN (SELECT blocked_id FROM blocks WHERE blocker_id = ?) " +
                        "AND c.author_id NOT IN (SELECT blocker_id FROM blocks WHERE blocked_id = ?) " +
                        "ORDER BY c.id DESC LIMIT 100",
                (rs, row) -> new Comment(rs.getLong("id"), rs.getLong("post_id"),
                        rs.getLong("author_id"), rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), postId, ownId, ownId);
        Collections.reverse(result);
        return result;
    }

    @PostMapping("/{postId}/comments")
    public Comment addComment(@PathVariable long postId, @Valid @RequestBody NewComment input,
                              HttpServletRequest request) {
        long authorId = auth.requireUser(request);
        requireVisiblePost(postId, authorId);
        String content = input.content().trim();
        KeyHolder keys = new GeneratedKeyHolder();
        db.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO post_comments(post_id, author_id, content) VALUES (?, ?, ?)",
                    new String[] { "ID" });
            ps.setLong(1, postId);
            ps.setLong(2, authorId);
            ps.setString(3, content);
            return ps;
        }, keys);
        return db.queryForObject("SELECT c.id, c.post_id, c.author_id, u.display_name, u.avatar_url, c.content, c.created_at " +
                        "FROM post_comments c JOIN users u ON u.id = c.author_id WHERE c.id = ?",
                (rs, row) -> new Comment(rs.getLong("id"), rs.getLong("post_id"),
                        rs.getLong("author_id"), rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), keys.getKey().longValue());
    }

    private Post find(long postId, long ownId) {
        Post row = db.queryForObject("SELECT p.id, p.author_id, u.display_name, u.avatar_url, p.content, p.created_at, p.topics, p.location_name, p.latitude, p.longitude " +
                        "FROM posts p JOIN users u ON u.id = p.author_id WHERE p.id = ?",
                (rs, index) -> new Post(rs.getLong("id"), rs.getLong("author_id"),
                        rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime(), List.of(), 0, rs.getString("topics"),
                        rs.getString("location_name"), 0, false, (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude")), postId);
        return withMediaAndCount(row, ownId);
    }

    private Post withMediaAndCount(Post post, long ownId) {
        List<String> imageUrls = db.query("SELECT image_url FROM post_images WHERE post_id = ? ORDER BY sort_order",
                (rs, row) -> rs.getString(1), post.id());
        Integer count = db.queryForObject("SELECT COUNT(*) FROM post_comments WHERE post_id = ? " +
                "AND author_id NOT IN (SELECT blocked_id FROM blocks WHERE blocker_id = ?) " +
                "AND author_id NOT IN (SELECT blocker_id FROM blocks WHERE blocked_id = ?)",
                Integer.class, post.id(), ownId, ownId);
        Integer likes = db.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id = ?", Integer.class, post.id());
        Integer liked = db.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id = ? AND user_id = ?", Integer.class, post.id(), ownId);
        return new Post(post.id(), post.authorId(), post.authorName(), post.authorAvatarUrl(), post.content(), post.createdAt(),
                imageUrls, count == null ? 0 : count, post.topics(), post.locationName(), likes == null ? 0 : likes, liked != null && liked > 0, post.latitude(), post.longitude());
    }

    private void requireVisiblePost(long postId, long ownId) {
        List<Long> authors = db.query("SELECT author_id FROM posts WHERE id = ?",
                (rs, row) -> rs.getLong(1), postId);
        if (authors.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "动态不存在");
        long authorId = authors.get(0);
        Integer blocked = db.queryForObject("SELECT COUNT(*) FROM blocks WHERE " +
                        "(blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)",
                Integer.class, ownId, authorId, authorId, ownId);
        if (blocked != null && blocked > 0) throw new ApiException(HttpStatus.FORBIDDEN, "无法查看该动态");
    }

    public record NewPost(@Size(max = 2000) String content, @Size(max = 9) List<String> imageUrls,
                          @Size(max = 5) List<String> topics, @Size(max = 80) String locationName, Double latitude, Double longitude) { }
    public record NewComment(@NotBlank @Size(max = 500) String content) { }
    public record Post(long id, long authorId, String authorName, String authorAvatarUrl, String content, LocalDateTime createdAt,
                       List<String> imageUrls, int commentCount, String topics, String locationName,
                       int likeCount, boolean liked, Double latitude, Double longitude) { }
    public record Comment(long id, long postId, long authorId, String authorName, String authorAvatarUrl, String content,
                          LocalDateTime createdAt) { }
}
