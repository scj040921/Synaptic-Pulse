package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;

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
            Integer unread = db.queryForObject("SELECT COUNT(*) FROM messages WHERE sender_id = ? AND receiver_id = ? " +
                            "AND id > COALESCE((SELECT last_read_id FROM chat_reads WHERE user_id = ? AND peer_id = ?), 0)",
                    Integer.class, peerId, ownId, ownId, peerId);
            if (!latest.isEmpty()) result.add(new Conversation(users.get(peerId), latest.get(0), unread == null ? 0 : unread));
        }
        result.sort((a, b) -> Long.compare(b.lastMessage().id(), a.lastMessage().id()));
        return result;
    }

    @GetMapping("/chats/{peerId}/messages")
    public List<Message> messages(@PathVariable long peerId, @RequestParam(defaultValue = "0") long before,
                                  @RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        checkPeer(ownId, peerId);
        if (q.length() > 80 || before < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "查询参数无效");
        List<Message> result = messageQuery(ownId, peerId, 50, before, q);
        java.util.Collections.reverse(result);
        return result;
    }

    @PostMapping("/chats/{peerId}/read")
    public void read(@PathVariable long peerId, @RequestBody ReadRequest input, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        checkPeer(ownId, peerId);
        Long last = db.queryForObject("SELECT COALESCE(MAX(id), 0) FROM messages WHERE sender_id = ? AND receiver_id = ? AND id <= ?",
                Long.class, peerId, ownId, input.lastReadId());
        try { db.update("INSERT INTO chat_reads(user_id, peer_id, last_read_id) VALUES (?, ?, 0)", ownId, peerId); }
        catch (org.springframework.dao.DuplicateKeyException ignored) { }
        db.update("UPDATE chat_reads SET last_read_id = GREATEST(last_read_id, ?) WHERE user_id = ? AND peer_id = ?",
                last == null ? 0 : last, ownId, peerId);
    }

    @GetMapping("/blocks")
    public List<UserService.UserView> blocks(HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        return db.query("SELECT blocked_id FROM blocks WHERE blocker_id = ?", (rs, row) -> users.get(rs.getLong(1)), ownId);
    }

    @PostMapping("/chats/{peerId}/messages")
    public Message send(@PathVariable long peerId, @Valid @RequestBody NewMessage input,
                        HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        checkPeer(ownId, peerId);
        if (input.clientId() != null) {
            List<Message> previous = byClientKey(ownId, input.clientId());
            if (!previous.isEmpty()) {
                if (previous.get(0).receiverId() != peerId) throw new ApiException(HttpStatus.BAD_REQUEST, "消息标识重复");
                return previous.get(0);
            }
        }
        String type = input.type() == null ? "text" : input.type();
        String content = input.content() == null ? "" : input.content().trim();
        String imageUrl = input.imageUrl();
        if (type.equals("text")) {
            if (content.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "消息不能为空");
            imageUrl = null;
        } else if (type.equals("sticker")) {
            if (imageUrl != null && !imageUrl.isBlank()) {
                Integer owned = db.queryForObject("SELECT COUNT(*) FROM image_assets WHERE uploader_id = ? AND image_url = ?",
                        Integer.class, ownId, imageUrl);
                if (owned == null || owned != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择自己上传的表情包");
                content = "";
            } else {
                if (!List.of("😀", "🥹", "😍", "😎", "😭", "👍", "🎉", "❤️").contains(content)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的表情包");
                }
                imageUrl = null;
            }
        } else if (type.equals("image")) {
            Integer owned = db.queryForObject("SELECT COUNT(*) FROM image_assets WHERE uploader_id = ? AND image_url = ?",
                    Integer.class, ownId, imageUrl);
            if (owned == null || owned != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择自己上传的图片");
            content = "";
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的消息类型");
        }
        String savedContent = content;
        String savedImageUrl = imageUrl;
        KeyHolder keys = new GeneratedKeyHolder();
        try { db.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO messages(sender_id, receiver_id, content, type, image_url, client_key) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[] { "ID" });
            ps.setLong(1, ownId);
            ps.setLong(2, peerId);
            ps.setString(3, savedContent);
            ps.setString(4, type);
            ps.setString(5, savedImageUrl);
            ps.setString(6, input.clientId());
            return ps;
        }, keys); } catch (org.springframework.dao.DuplicateKeyException ex) {
            List<Message> previous = byClientKey(ownId, input.clientId());
            if (previous.isEmpty() || previous.get(0).receiverId() != peerId) throw ex;
            return previous.get(0);
        }
        return db.queryForObject("SELECT id, sender_id, receiver_id, content, type, image_url, created_at FROM messages WHERE id = ?",
                (rs, row) -> new Message(rs.getLong("id"), rs.getLong("sender_id"),
                        rs.getLong("receiver_id"), rs.getString("content"), rs.getString("type"), rs.getString("image_url"),
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
        return messageQuery(ownId, peerId, limit, 0, "");
    }

    private List<Message> byClientKey(long ownId, String clientId) {
        return db.query("SELECT id, sender_id, receiver_id, content, type, image_url, created_at FROM messages WHERE sender_id = ? AND client_key = ?",
                (rs, row) -> new Message(rs.getLong("id"), rs.getLong("sender_id"), rs.getLong("receiver_id"),
                        rs.getString("content"), rs.getString("type"), rs.getString("image_url"), rs.getTimestamp("created_at").toLocalDateTime()), ownId, clientId);
    }

    private List<Message> messageQuery(long ownId, long peerId, int limit, long before, String q) {
        String search = "%" + q.trim().toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return db.query("SELECT id, sender_id, receiver_id, content, type, image_url, created_at FROM messages " +
                        "WHERE ((sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)) " +
                        "AND (? = 0 OR id < ?) AND LOWER(content) LIKE ? ESCAPE '!' " +
                        "ORDER BY id DESC LIMIT ?",
                (rs, row) -> new Message(rs.getLong("id"), rs.getLong("sender_id"),
                        rs.getLong("receiver_id"), rs.getString("content"), rs.getString("type"), rs.getString("image_url"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                ownId, peerId, peerId, ownId, before, before, search, limit);
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

    public record NewMessage(@Size(max = 1000) String content, String type, @Size(max = 300) String imageUrl,
                             @Size(max = 64) String clientId) { }
    public record ReadRequest(long lastReadId) { }
    public record Message(long id, long senderId, long receiverId, String content, String type,
                          String imageUrl, LocalDateTime createdAt) { }
    public record Conversation(UserService.UserView peer, Message lastMessage, int unreadCount) { }
}
