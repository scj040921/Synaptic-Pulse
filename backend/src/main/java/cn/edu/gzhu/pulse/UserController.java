package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {
    private final AuthService auth;
    private final UserService users;

    public UserController(AuthService auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    @GetMapping("/users/{id}")
    public UserService.UserView get(@PathVariable long id, HttpServletRequest request) {
        auth.requireUser(request);
        return users.get(id);
    }

    @PutMapping("/me")
    public UserService.UserView update(@Valid @RequestBody UpdateProfile input, HttpServletRequest request) {
        return users.update(auth.requireUser(request), input.displayName(), input.bio(), input.interests());
    }

    public record UpdateProfile(@NotBlank @Size(max = 40) String displayName,
                                @Size(max = 300) String bio,
                                List<String> interests) { }
}
