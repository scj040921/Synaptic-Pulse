package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/map")
public class CampusMapController {
    private final CampusMapService maps;
    private final AuthService auth;
    private final UserService users;
    private final EventController events;
    private final PostController posts;
    public CampusMapController(CampusMapService maps, AuthService auth, UserService users, EventController events, PostController posts) {
        this.maps = maps; this.auth = auth; this.users = users; this.events = events; this.posts = posts;
    }
    @GetMapping("/places")
    public List<CampusMapService.Place> places(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String category, @RequestParam(required=false) Double lat, @RequestParam(required=false) Double lng, @RequestParam(required=false) Integer radius, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        return maps.list(ownId, users.get(ownId).campus(), q, category, lat, lng, radius);
    }
    @GetMapping("/overview")
    public Overview overview(@RequestParam(required=false) Double lat, @RequestParam(required=false) Double lng, @RequestParam(required=false) Integer radius, HttpServletRequest request) {
        long ownId = auth.requireUser(request);
        var places = maps.list(ownId, users.get(ownId).campus(), "", "", lat, lng, radius);
        // Reuse the activity visibility rules, including both directions of user blocking.
        var signals = events.list("", request).stream().filter(e -> e.latitude() != null && e.longitude() != null && e.startsAt().isAfter(LocalDateTime.now()))
                .filter(e -> radius == null || CampusMapService.distance(lat, lng, e.latitude(), e.longitude()) <= radius).toList();
        return new Overview(users.get(ownId).campus(), "WGS84", places, signals, posts.mapPosts(lat, lng, radius, request));
    }
    @PutMapping("/places/{id}/favorite")
    public void favorite(@PathVariable String id, HttpServletRequest request) { changeFavorite(id, request, true); }
    @DeleteMapping("/places/{id}/favorite")
    public void unfavorite(@PathVariable String id, HttpServletRequest request) { changeFavorite(id, request, false); }
    private void changeFavorite(String id, HttpServletRequest request, boolean enabled) {
        long ownId = auth.requireUser(request); maps.favorite(ownId, users.get(ownId).campus(), id, enabled);
    }
    public record Overview(String campus, String coordinateSystem, List<CampusMapService.Place> places, List<EventController.Event> events, List<PostController.Post> posts) { }
}
