package cn.edu.gzhu.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CampusMapService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public CampusMapService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    @PostConstruct
    public void importCatalog() throws IOException {
        try (var stream = new ClassPathResource("map/places.json").getInputStream()) {
            for (CatalogPlace p : json.readValue(stream, CatalogPlace[].class)) {
                // Preserve existing curated coordinates when importing missing catalog entries.
                db.update("INSERT INTO campus_places(id,campus,name,category,latitude,longitude,description,source_url) SELECT ?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM campus_places WHERE id=?)",
                        p.id(), p.campus(), p.name(), p.category(), p.latitude(), p.longitude(), p.description(), p.sourceUrl(), p.id());
            }
        }
    }
    public List<Place> list(long ownId, String campus, String query, String category, Double lat, Double lng, Integer radius) {
        validatePosition(lat, lng, radius);
        if (query.length() > 80 || category.length() > 20) throw new ApiException(HttpStatus.BAD_REQUEST, "地图筛选条件过长");
        String keyword = query.strip().toLowerCase(Locale.ROOT);
        return db.query("SELECT p.*, CASE WHEN f.place_id IS NULL THEN FALSE ELSE TRUE END AS favorite FROM campus_places p LEFT JOIN place_favorites f ON f.place_id=p.id AND f.user_id=? WHERE p.campus=?",
                (rs, row) -> new Place(rs.getString("id"), rs.getString("name"), rs.getString("category"), rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("description"), rs.getString("source_url"), rs.getBoolean("favorite"), lat == null ? null : (int) Math.round(distance(lat, lng, rs.getDouble("latitude"), rs.getDouble("longitude")))), ownId, campus)
                .stream().filter(p -> keyword.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(keyword) || p.description().toLowerCase(Locale.ROOT).contains(keyword))
                .filter(p -> category.isEmpty() || p.category().equals(category))
                .filter(p -> radius == null || distance(lat, lng, p.latitude(), p.longitude()) <= radius)
                .sorted(Comparator.comparing((Place p) -> p.distanceMeters() == null ? Integer.MAX_VALUE : p.distanceMeters()).thenComparing(Place::name)).toList();
    }
    public Place requirePlace(long ownId, String campus, String id) {
        return list(ownId, campus, "", "", null, null, null).stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "请选择有效的同校地图地点"));
    }
    public void favorite(long ownId, String campus, String id, boolean enabled) {
        requirePlace(ownId, campus, id);
        if (enabled) db.update("MERGE INTO place_favorites(user_id,place_id) KEY(user_id,place_id) VALUES(?,?)", ownId, id);
        else db.update("DELETE FROM place_favorites WHERE user_id=? AND place_id=?", ownId, id);
    }
    public static void validatePosition(Double lat, Double lng, Integer radius) {
        if ((lat == null) != (lng == null) || (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180))) throw new ApiException(HttpStatus.BAD_REQUEST, "请提供有效的 WGS84 经纬度");
        if (radius != null && (lat == null || radius < 50 || radius > 5000)) throw new ApiException(HttpStatus.BAD_REQUEST, "附近范围须为50～5000米，并提供中心位置");
    }
    // Coordinates are used for this query only, never persisted as user tracks.
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double a = Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(Math.toRadians(lng2 - lng1) / 2), 2);
        return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }
    public record CatalogPlace(String id, String name, String campus, String category, double latitude, double longitude, String description, String sourceUrl) { }
    public record Place(String id, String name, String category, double latitude, double longitude, String description, String sourceUrl, boolean favorite, Integer distanceMeters) { }
}
