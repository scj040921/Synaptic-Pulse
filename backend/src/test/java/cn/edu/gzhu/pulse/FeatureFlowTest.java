package cn.edu.gzhu.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:feature_test;DB_CLOSE_DELAY=-1", "app.upload-dir=./target/test-uploads", "app.ai.api-key=", "app.ai.model="})
@AutoConfigureMockMvc
class FeatureFlowTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    private JsonNode account(String name) throws Exception {
        return call(post("/api/auth/register"), null, Map.of("username",name,"password","PreviewPass2026!","displayName",name),200);
    }
    private String token(JsonNode account) { return account.path("token").asText(); }
    private long id(JsonNode account) { return account.path("user").path("id").asLong(); }
    private JsonNode call(MockHttpServletRequestBuilder request, String token, Object data, int status) throws Exception {
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(data!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data));
        String response=mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return response.isBlank()?json.createObjectNode():json.readTree(response);
    }
    private String image(String token) throws Exception {
        byte[] bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var file=new MockMultipartFile("file","pixel.png","image/png",bytes);
        var result=mvc.perform(multipart("/api/uploads/images").file(file).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("url").asText();
    }
    @Test void imageOwnershipSearchLikesAndDeletion() throws Exception {
        var owner=account("media_owner");var reader=account("media_reader");String a=token(owner),b=token(reader);String url=image(a);
        call(put("/api/me/avatar"),a,Map.of("imageUrl",url),200);
        call(put("/api/me/avatar"),b,Map.of("imageUrl",url),400);
        call(post("/api/posts"),b,Map.of("content","not mine","imageUrls",List.of(url)),400);
        var post=call(post("/api/posts"),a,Map.of("content","一起学摄影","imageUrls",List.of(url),"topics",List.of("摄影"),"locationName","广州大学图书馆"),200);
        long postId=post.path("id").asLong();
        assertEquals(1,call(get("/api/posts").param("q","图书馆"),b,null,200).size());
        assertEquals(0,call(get("/api/posts").param("q","%"),b,null,200).size());
        assertEquals(1,call(put("/api/posts/"+postId+"/like"),b,null,200).path("likeCount").asInt());
        assertEquals(1,call(put("/api/posts/"+postId+"/like"),b,null,200).path("likeCount").asInt());
        call(delete("/api/posts/"+postId),b,null,403);
        call(delete("/api/posts/"+postId),a,null,200);
        assertEquals(0,call(get("/api/posts").param("q","摄影"),b,null,200).size());
        call(post("/api/posts"),a,Map.of("content","too many","imageUrls",java.util.Collections.nCopies(10,url)),400);
        mvc.perform(multipart("/api/uploads/images").file(new MockMultipartFile("file","bad.png","image/png","invalid".getBytes())).header("Authorization","Bearer "+a)).andExpect(status().isUnsupportedMediaType());
    }
    @Test void unreadHistorySearchAndIdempotentRetry() throws Exception {
        var a=account("history_sender");var b=account("history_reader");String path="/api/chats/"+id(b)+"/messages";
        long first=0;
        for(int i=0;i<55;i++) {
            var message=call(post(path),token(a),Map.of("content",i==7?"needle":"message "+i,"clientId","history-"+i),200);
            if(i==0) first=message.path("id").asLong();
        }
        assertEquals(first,call(post(path),token(a),Map.of("content","message 0","clientId","history-0"),200).path("id").asLong());
        var latest=call(get("/api/chats/"+id(a)+"/messages"),token(b),null,200); assertEquals(50,latest.size());
        assertEquals(5,call(get("/api/chats/"+id(a)+"/messages").param("before",latest.get(0).path("id").asText()),token(b),null,200).size());
        assertEquals(1,call(get("/api/chats/"+id(a)+"/messages").param("q","needle"),token(b),null,200).size());
        assertEquals(55,call(get("/api/chats"),token(b),null,200).get(0).path("unreadCount").asInt());
        call(post("/api/chats/"+id(a)+"/read"),token(b),Map.of("lastReadId",latest.get(49).path("id").asLong()),200);
        call(post("/api/chats/"+id(a)+"/read"),token(b),Map.of("lastReadId",first),200);
        assertEquals(0,call(get("/api/chats"),token(b),null,200).get(0).path("unreadCount").asInt());
        String url=image(token(a)); call(post(path),token(b),Map.of("type","image","imageUrl",url),400);
        call(post(path),token(a),Map.of("type","sticker","imageUrl",url),200);
    }
    @Test void concurrentEventSignupCannotOverbook() throws Exception {
        var host=account("event_host");var a=account("event_a");var b=account("event_b");
        var event=call(post("/api/events"),token(host),Map.of("title","摄影同行","content","一起拍校园","locationName","图书馆","startsAt",LocalDateTime.now().plusDays(1).withNano(0).toString(),"capacity",1),200);
        String path="/api/events/"+event.path("id").asLong()+"/signup";
        var executor=Executors.newFixedThreadPool(2);
        try {
            var results=executor.invokeAll(List.of(
                    ()->mvc.perform(put(path).header("Authorization","Bearer "+token(a))).andReturn().getResponse().getStatus(),
                    ()->mvc.perform(put(path).header("Authorization","Bearer "+token(b))).andReturn().getResponse().getStatus()));
            var statuses=List.of(results.get(0).get(),results.get(1).get()); assertTrue(statuses.contains(200));assertTrue(statuses.contains(409));
            assertEquals(1,call(get("/api/events"),token(host),null,200).get(0).path("participants").asInt());
        } finally {executor.shutdownNow();}
    }
    @Test void practiceProgressCountsSessionsAndInvalidInputStays400() throws Exception {
        var a=account("practice_user");String t=token(a);
        var data=Map.of("scenario","社团招新","message","你好，我想了解社团活动？","level","进阶","mode","offline","practiceId","one-practice");
        assertEquals("offline",call(post("/api/practice/respond"),t,data,200).path("source").asText());
        call(post("/api/practice/respond"),t,data,200);
        assertEquals(1,call(get("/api/me/stats"),t,null,200).path("practiceCount").asInt());
        call(post("/api/practice/respond"),t,Map.of("scenario","社团招新","message","你好","mode","ai","consent",false),400);
        call(post("/api/practice/respond"),t,Map.of("scenario","社团招新","message","你好","mode","ai","consent",true),503);
        call(post("/api/practice/respond"),t,Map.of("scenario","社团招新","message","你好","mode","ai","consent",true,"history",java.util.Arrays.asList((Object)null)),400);
        var followup=call(post("/api/practice/respond"),t,Map.of("scenario","社团招新","message","周六下午有空吗？","mode","offline","practiceId","one-practice","history",List.of(Map.of("role","user","content","你好"))),200);
        assertTrue(followup.path("reply").asText().contains("候选时间"));
        assertEquals("offline",call(post("/api/posts/assist"),t,Map.of("prompt","一起拍摄影作品","mode","offline"),200).path("source").asText());
        call(post("/api/posts/assist"),t,Map.of("prompt","一起拍摄影作品","mode","ai","consent",false),400);
        call(post("/api/posts/assist"),t,Map.of("prompt","一起拍摄影作品","mode","invalid"),400);
        mvc.perform(post("/api/events").header("Authorization","Bearer "+t).contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\"invalid\"}")).andExpect(status().isBadRequest());
        call(post("/api/auth/register"),null,Map.of("username","long_password","displayName","test","password","汉".repeat(30)),400);
    }
}
