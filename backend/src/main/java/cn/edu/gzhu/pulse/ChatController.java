package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final UserService users;

    public ChatController(JdbcTemplate db, AuthService auth, UserService users) {
        this.db = db;
        this.auth = auth;
        this.users = users;
    }

    @GetMapping("/chats")
    public List<Conversation> chats(HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        List<Long> peerIds = db.query("SELECT DISTINCT CASE WHEN sender_id = ? THEN receiver_id ELSE sender_id END peer_id " +
                        "FROM messages WHERE sender_id = ? OR receiver_id = ?",
                (rs, row) -> rs.getLong(1), ownId, ownId, ownId);
        List<Conversation> result = new ArrayList<>();
        for (long peerId : peerIds) {
            if (isBlocked(ownId, peerId)) continue;
            List<Message> latest = messageQuery(ownId, peerId, 1);
            if (!latest.isEmpty()) result.add(new Conversation(users.get(peerId), latest.get(0)));
        }
        result.sort((a, b) -> Long.compare(b.lastMessage().id(), a.lastMessage().id()));
        return result;
    }

    @GetMapping("/chats/{peerId}/messages")
    public List<Message> messages(@PathVariable long peerId, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        checkPeer(ownId, peerId);
        List<Message> result = messageQuery(ownId, peerId, 50);
        java.util.Collections.reverse(result);
        return result;
    }

    @PostMapping("/chats/{peerId}/messages")
    public Message send(@PathVariable long peerId, @Valid @RequestBody NewMessage input,
                        HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        checkPeer(ownId, peerId);
        String content = input.content().trim();
        if (content.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "消息不能为空");
        KeyHolder keys = new GeneratedKeyHolder();
        db.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO messages(sender_id, receiver_id, content) VALUES (?, ?, ?)",
                    new String[] { "ID" });
            ps.setLong(1, ownId);
            ps.setLong(2, peerId);
            ps.setString(3, content);
            return ps;
        }, keys);
        return db.queryForObject("SELECT id, sender_id, receiver_id, content, created_at FROM messages WHERE id = ?",
                (rs, row) -> new Message(rs.getLong("id"), rs.getLong("sender_id"),
                        rs.getLong("receiver_id"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), keys.getKey().longValue());
    }

    @PostMapping("/blocks/{peerId}")
    public void block(@PathVariable long peerId, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        if (ownId == peerId) throw new ApiException(HttpStatus.BAD_REQUEST, "不能屏蔽自己");
        users.get(peerId);
        db.update("MERGE INTO blocks(blocker_id, blocked_id) KEY(blocker_id, blocked_id) VALUES (?, ?)", ownId, peerId);
    }

    @DeleteMapping("/blocks/{peerId}")
    public void unblock(@PathVariable long peerId, HttpServletRequest request) {
        db.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", auth.requireUser(request), peerId);
    }

    private List<Message> messageQuery(long ownId, long peerId, int limit) {
        return db.query("SELECT id, sender_id, receiver_id, content, created_at FROM messages " +
                        "WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?) " +
                        "ORDER BY id DESC LIMIT ?",
                (rs, row) -> new Message(rs.getLong("id"), rs.getLong("sender_id"),
                        rs.getLong("receiver_id"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                ownId, peerId, peerId, ownId, limit);
    }

    private void checkPeer(long ownId, long peerId) {
        if (ownId == peerId) throw new ApiException(HttpStatus.BAD_REQUEST, "不能给自己发消息");
        if (!users.get(ownId).campus().equals(users.get(peerId).campus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅支持同校用户聊天");
        }
        if (isBlocked(ownId, peerId)) throw new ApiException(HttpStatus.FORBIDDEN, "当前无法与该用户聊天");
    }

    private boolean isBlocked(long ownId, long peerId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM blocks WHERE " +
                "(blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)",
                Integer.class, ownId, peerId, peerId, ownId);
        return count != null && count > 0;
    }

    public record NewMessage(@NotBlank @Size(max = 1000) String content) { }
    public record Message(long id, long senderId, long receiverId, String content, LocalDateTime createdAt) { }
    public record Conversation(UserService.UserView peer, Message lastMessage) { }
}
