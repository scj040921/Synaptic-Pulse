package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
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
@RequestMapping("/api")
public class AuthController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final UserService users;

    public AuthController(JdbcTemplate db, AuthService auth, UserService users) {
        this.db = db;
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/auth/register")
    public Session register(@Valid @RequestBody Register input) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            db.update(connection -> {
                var ps = connection.prepareStatement(
                        "INSERT INTO users(username, password_hash, display_name) VALUES (?, ?, ?)",
                        new String[] { "ID" });
                ps.setString(1, input.username().toLowerCase(java.util.Locale.ROOT));
                ps.setString(2, auth.hashPassword(input.password()));
                ps.setString(3, input.displayName().trim());
                return ps;
            }, keys);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名已被使用");
        }
        long id = keys.getKey().longValue();
        return new Session(auth.createToken(id), users.get(id));
    }

    @PostMapping("/auth/login")
    public Session login(@Valid @RequestBody Login input) {
        long id = auth.checkCredentials(input.username(), input.password());
        return new Session(auth.createToken(id), users.get(id));
    }

    @PostMapping("/auth/logout")
    public Map<String, Boolean> logout(HttpServletRequest request) {
        auth.logout(request);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public UserService.UserView me(HttpServletRequest request) {
        return users.get(auth.requireUser(request));
    }

    public record Register(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,40}", message = "使用 3 到 40 位字母、数字或下划线") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 40) String displayName) { }
    public record Login(@NotBlank String username, @NotBlank String password) { }
    public record Session(String token, UserService.UserView user) { }
}
