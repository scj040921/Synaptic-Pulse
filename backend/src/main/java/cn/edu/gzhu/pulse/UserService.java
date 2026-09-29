package cn.edu.gzhu.pulse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final JdbcTemplate db;

    public UserService(JdbcTemplate db) { this.db = db; }

    public UserView get(long id) {
        List<UserView> users = db.query("SELECT id, display_name, bio, campus FROM users WHERE id = ?",
                (rs, row) -> new UserView(rs.getLong("id"), rs.getString("display_name"),
                        rs.getString("bio"), rs.getString("campus"), interests(rs.getLong("id"))), id);
        if (users.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        return users.get(0);
    }

    public List<String> interests(long id) {
        return db.query("SELECT interest FROM user_interests WHERE user_id = ? ORDER BY interest",
                (rs, row) -> rs.getString(1), id);
    }

    @Transactional
    public UserView update(long id, String displayName, String bio, List<String> interests) {
        String name = displayName == null ? "" : displayName.trim();
        String about = bio == null ? "" : bio.trim();
        if (name.isEmpty() || name.length() > 40 || about.length() > 300) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "昵称或简介长度不符合要求");
        }
        Set<String> normalized = new LinkedHashSet<>();
        if (interests != null) {
            for (String raw : interests) {
                String interest = raw == null ? "" : raw.trim();
                if (interest.isEmpty() || interest.length() > 30) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "兴趣标签需为 1 到 30 个字符");
                }
                normalized.add(interest);
            }
        }
        if (normalized.size() > 10) throw new ApiException(HttpStatus.BAD_REQUEST, "最多设置 10 个兴趣标签");
        db.update("UPDATE users SET display_name = ?, bio = ? WHERE id = ?", name, about, id);
        db.update("DELETE FROM user_interests WHERE user_id = ?", id);
        for (String interest : normalized) {
            db.update("INSERT INTO user_interests(user_id, interest) VALUES (?, ?)", id, interest);
        }
        return get(id);
    }

    public List<UserView> candidates(long ownId) {
        List<Long> ids = db.query("SELECT id FROM users WHERE id <> ? AND campus = " +
                        "(SELECT campus FROM users WHERE id = ?) AND id NOT IN " +
                        "(SELECT blocked_id FROM blocks WHERE blocker_id = ?) AND id NOT IN " +
                        "(SELECT blocker_id FROM blocks WHERE blocked_id = ?) ORDER BY id DESC LIMIT 100",
                (rs, row) -> rs.getLong(1), ownId, ownId, ownId, ownId);
        List<UserView> result = new ArrayList<>();
        for (Long id : ids) result.add(get(id));
        return result;
    }

    public record UserView(long id, String displayName, String bio, String campus, List<String> interests) { }
}
