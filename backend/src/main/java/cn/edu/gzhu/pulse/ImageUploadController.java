package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class ImageUploadController {
    private final AuthService auth;
    private final ImageStorageService storage;
    private final JdbcTemplate db;

    public ImageUploadController(AuthService auth, ImageStorageService storage, JdbcTemplate db) {
        this.auth = auth;
        this.storage = storage;
        this.db = db;
    }

    @PostMapping("/images")
    public UploadResult upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        long userId = auth.requireUser(request);
        String url = storage.store(file);
        db.update("INSERT INTO image_assets(uploader_id, image_url) VALUES (?, ?)", userId, url);
        return new UploadResult(url);
    }

    public record UploadResult(String url) { }
}
