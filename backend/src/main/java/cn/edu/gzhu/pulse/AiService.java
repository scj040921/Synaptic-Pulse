package cn.edu.gzhu.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private final ObjectMapper json;
    private final String baseUrl, model, key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AiService(ObjectMapper json, @Value("${app.ai.base-url}") String baseUrl,
                     @Value("${app.ai.model}") String model, @Value("${app.ai.api-key}") String key) {
        this.json = json; this.baseUrl = baseUrl.replaceAll("/+$", ""); this.model = model; this.key = key;
    }
    public boolean configured() { return !model.isBlank() && !key.isBlank(); }
    public String complete(String instruction, List<Map<String, String>> history) {
        if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务尚未配置，可以先使用本地练习");
        try {
            var messages = new java.util.ArrayList<Map<String, String>>();
            messages.add(Map.of("role", "system", "content", instruction)); messages.addAll(history);
            String body = json.writeValueAsString(Map.of("model", model, "messages", messages, "temperature", 0.7));
            var call = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(call, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务暂时不可用，请稍后重试或使用本地练习");
            var content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 未返回有效内容");
            return content.asText().strip();
        } catch (ApiException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 请求已中断"); }
        catch (Exception ex) { throw new ApiException(HttpStatus.BAD_GATEWAY, "连接 AI 服务失败"); }
    }
}
