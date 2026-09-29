package cn.edu.gzhu.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/training")
public class TrainingController {
    private final AuthService auth;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public TrainingController(AuthService auth, ObjectMapper json,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.model}") String model,
            @Value("${app.ai.api-key}") String apiKey) {
        this.auth = auth;
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey;
    }

    @PostMapping("/reply")
    public CoachReply reply(@Valid @RequestBody CoachRequest input, HttpServletRequest request) {
        auth.requireUser(request);
        if (!input.consent()) throw new ApiException(HttpStatus.BAD_REQUEST, "请先同意将本次练习内容发送给模型服务");
        if (apiKey.isBlank() || model.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 尚未配置，请设置 AI_API_KEY 和 AI_MODEL");
        }
        try {
            // 只发送本次练习文本；不上传账号、兴趣、聊天记录或位置。
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "你是校园社交练习伙伴。场景：" + input.scenario() +
                                    "。请以友善的同龄人身份给出一句自然回应，再用一小句给出具体沟通建议。不要要求真实姓名、电话或位置。"),
                            Map.of("role", "user", "content", input.message())),
                    "temperature", 0.7));
            HttpRequest call = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "模型服务暂时不可用");
            }
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "模型没有返回有效内容");
            }
            return new CoachReply(content.asText());
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "连接模型服务失败");
        }
    }

    public record CoachRequest(@NotBlank @Size(max = 60) String scenario,
                               @NotBlank @Size(max = 1000) String message,
                               boolean consent) { }
    public record CoachReply(String reply) { }
}
