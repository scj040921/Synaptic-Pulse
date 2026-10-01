package cn.edu.gzhu.pulse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageStorageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path root;

    public ImageStorageService(@Value("${app.upload-dir}") String uploadDirectory) {
        this.root = Paths.get(uploadDirectory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "单张图片不能超过 5 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            String extension = extension(bytes);
            if (extension == null) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 JPG、PNG、WebP 或 GIF 图片");
            }
            Files.createDirectories(root);
            String filename = UUID.randomUUID() + extension;
            Path destination = root.resolve(filename).normalize();
            if (!destination.startsWith(root)) throw new ApiException(HttpStatus.BAD_REQUEST, "图片路径无效");
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return "/media/" + filename;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败");
        }
    }

    private String extension(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return ".jpg";
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) return ".png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P') return ".webp";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') return ".gif";
        return null;
    }
}
