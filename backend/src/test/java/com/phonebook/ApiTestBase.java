package com.phonebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestBase {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanDatabase() {
        for (String col : mongoTemplate.getCollectionNames()) {
            if (col.startsWith("system.")) continue;
            mongoTemplate.getCollection(col).deleteMany(new org.bson.Document());
        }
    }

    protected Map<String, String> uniqueUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, String> user = new HashMap<>();
        user.put("username", prefix + "_" + suffix);
        user.put("email", prefix + "_" + suffix + "@example.com");
        user.put("password", "StrongPass123!");
        return user;
    }

    protected TestClient register(Map<String, String> user) throws Exception {
        MvcResult result = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("Register failed: " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString());
        }
        String cookie = sessionCookie(result.getResponse());
        return new TestClient(mvc, objectMapper, cookie);
    }

    protected static String sessionCookie(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            part = part.trim();
            if (part.startsWith("phonebook_session=")) return part.substring("phonebook_session=".length());
        }
        return null;
    }

    protected Map<String, Object> contactPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "Test Contact");
        payload.put("phone_number", "+14155550101");
        payload.put("email", "test-contact@example.com");
        payload.put("address", "123 Test Street");
        return payload;
    }

    protected static class TestClient {
        private final MockMvc mvc;
        private final ObjectMapper mapper;
        private String session;

        TestClient(MockMvc mvc, ObjectMapper mapper, String session) {
            this.mvc = mvc;
            this.mapper = mapper;
            this.session = session;
        }

        private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(
                org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
            if (session != null) builder.cookie(new jakarta.servlet.http.Cookie("phonebook_session", session));
            return builder;
        }

        public MvcResult post(String url, Object body) throws Exception {
            var builder = withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON));
            if (body != null) builder.content(mapper.writeValueAsString(body));
            MvcResult r = mvc.perform(builder).andReturn();
            updateSession(r);
            return r;
        }

        public MvcResult get(String url) throws Exception {
            MvcResult r = mvc.perform(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))).andReturn();
            updateSession(r);
            return r;
        }

        public MvcResult put(String url, Object body) throws Exception {
            var builder = withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).contentType(MediaType.APPLICATION_JSON));
            if (body != null) builder.content(mapper.writeValueAsString(body));
            MvcResult r = mvc.perform(builder).andReturn();
            updateSession(r);
            return r;
        }

        public MvcResult delete(String url) throws Exception {
            MvcResult r = mvc.perform(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url))).andReturn();
            updateSession(r);
            return r;
        }

        public MvcResult multipart(String url, org.springframework.mock.web.MockMultipartFile file) throws Exception {
            var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(url).file(file);
            if (session != null) builder.cookie(new jakarta.servlet.http.Cookie("phonebook_session", session));
            MvcResult r = mvc.perform(builder).andReturn();
            updateSession(r);
            return r;
        }

        private void updateSession(MvcResult r) {
            String header = r.getResponse().getHeader("Set-Cookie");
            if (header != null && header.contains("phonebook_session=")) {
                String c = ApiTestBase.sessionCookie(r.getResponse());
                if (c != null && !c.isEmpty()) this.session = c;
                else if (header.contains("Max-Age=0")) this.session = null;
            }
        }

        public JsonNode json(MvcResult r) throws Exception {
            return mapper.readTree(r.getResponse().getContentAsString());
        }
    }
}
