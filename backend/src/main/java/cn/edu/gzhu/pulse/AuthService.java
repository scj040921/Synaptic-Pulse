package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate db) { this.db = db; }

    public String hashPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new ApiException(HttpStatus.BAD_REQUEST, "密码按 UTF-8 编码不能超过 72 字节");
        return passwords.encode(password);
    }

    public long checkCredentials(String username, String password) {
        List<Account> accounts = db.query(
                "SELECT id, password_hash FROM users WHERE username = ?",
                (rs, row) -> new Account(rs.getLong("id"), rs.getString("password_hash")),
                username.trim().toLowerCase(java.util.Locale.ROOT));
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        if (accounts.isEmpty() || !passwords.matches(password, accounts.get(0).hash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        return accounts.get(0).id();
    }

    public String createToken(long userId) {
        db.update("DELETE FROM sessions WHERE expires_at <= CURRENT_TIMESTAMP");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // 数据库只保留摘要；原始令牌仅在登录响应中返回给客户端。
        db.update("INSERT INTO sessions(token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                sha256(token), userId, Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
        return token;
    }

    public long requireUser(HttpServletRequest request) {
        String token = bearer(request);
        List<Long> ids = db.query("SELECT user_id FROM sessions WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP",
                (rs, row) -> rs.getLong(1), sha256(token));
        if (ids.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        return ids.get(0);
    }

    public void logout(HttpServletRequest request) {
        requireUser(request);
        db.update("DELETE FROM sessions WHERE token_hash = ?", sha256(bearer(request)));
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return header.substring(7);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record Account(long id, String hash) { }
}
