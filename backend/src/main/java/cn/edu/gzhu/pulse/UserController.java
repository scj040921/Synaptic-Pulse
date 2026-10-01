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
    private final ProfileService profiles;

    public UserController(AuthService auth, UserService users, ProfileService profiles) {
        this.auth = auth;
        this.users = users;
        this.profiles = profiles;
    }

    @GetMapping("/users/{id}")
    public UserService.UserView get(@PathVariable long id, HttpServletRequest request) {
        auth.requireUser(request);
        return users.get(id);
    }

    @GetMapping("/profile/options")
    public ProfileService.Catalog options(HttpServletRequest request) { auth.requireUser(request); return profiles.catalog(); }

    @PutMapping("/me")
    public UserService.UserView update(@Valid @RequestBody UpdateProfile input, HttpServletRequest request) {
        return users.update(auth.requireUser(request), input.displayName(), input.bio(), input.interests(), input.portrait());
    }

    @PutMapping("/me/avatar")
    public UserService.UserView avatar(@Valid @RequestBody UpdateAvatar input, HttpServletRequest request) {
        return users.updateAvatar(auth.requireUser(request), input.imageUrl());
    }

    public record UpdateProfile(@NotBlank @Size(max = 40) String displayName,
                                @Size(max = 300) String bio,
                                @Size(max = 10) List<String> interests, ProfileService.Portrait portrait) { }
    public record UpdateAvatar(@NotBlank String imageUrl) { }
}
