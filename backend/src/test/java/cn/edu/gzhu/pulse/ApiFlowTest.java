package cn.edu.gzhu.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:pulse_test;DB_CLOSE_DELAY=-1", "spring.sql.init.mode=always"})
@AutoConfigureMockMvc
class ApiFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void registrationMatchingPostingMessagingAndBlocking() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());

        JsonNode alice = register("alice_test", "Alice");
        JsonNode bob = register("bob_test", "Bob");
        long bobId = bob.path("user").path("id").asLong();
        String aliceToken = alice.path("token").asText();
        String bobToken = bob.path("token").asText();

        mvc.perform(put("/api/me").header("Authorization", bearer(aliceToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"bio\":\"喜欢摄影\",\"interests\":[\"摄影\",\"编程\"]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/me").header("Authorization", bearer(bobToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\",\"bio\":\"也爱摄影\",\"interests\":[\"摄影\"]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/matches").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].score").value(50))
                .andExpect(jsonPath("$[0].commonInterests[0]").value("摄影"));

        mvc.perform(post("/api/posts").header("Authorization", bearer(aliceToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"周末一起拍照吗？\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/posts").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("周末一起拍照吗？"));

        mvc.perform(post("/api/chats/" + bobId + "/messages").header("Authorization", bearer(aliceToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"你好，聊聊摄影？\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chats").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].lastMessage.content").value("你好，聊聊摄影？"));

        mvc.perform(post("/api/blocks/" + bobId).header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chats/" + bobId + "/messages").header("Authorization", bearer(aliceToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"再次发送\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/matches").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void trainingRequiresConsentAndConfiguration() throws Exception {
        JsonNode account = register("coach_test", "Coach");
        String token = account.path("token").asText();
        mvc.perform(post("/api/training/reply").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"社团招新\",\"message\":\"你好\",\"consent\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/training/reply").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"社团招新\",\"message\":\"你好\",\"consent\":true}"))
                .andExpect(status().isServiceUnavailable());
    }

    private JsonNode register(String username, String name) throws Exception {
        String body = json.writeValueAsString(new AuthController.Register(username, "Passw0rd123", name));
        String response = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private String bearer(String token) { return "Bearer " + token; }
}
