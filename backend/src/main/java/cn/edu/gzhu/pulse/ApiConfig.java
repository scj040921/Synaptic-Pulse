package cn.edu.gzhu.pulse;

import java.util.Arrays;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiConfig implements WebMvcConfigurer {
    private final String[] origins;
    private final String uploadDirectory;

    public ApiConfig(@Value("${app.cors-origins}") String origins,
                     @Value("${app.upload-dir}") String uploadDirectory) {
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);
        this.uploadDirectory = uploadDirectory;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("Authorization", "Content-Type");
        registry.addMapping("/media/**").allowedOrigins(origins).allowedMethods("GET");
    }

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        Path directory = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        String location = directory.toUri().toString();
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/media/**").addResourceLocations(location);
    }
}
