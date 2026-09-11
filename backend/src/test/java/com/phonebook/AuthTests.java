package com.phonebook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class AuthTests extends ApiTestBase {

    @Test
    void rootEndpointReturnsRunningMessage() throws Exception {
        var result = mvc.perform(get("/")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("Phonebook API is running", body.get("message").asText());
    }

    @Test
    void registerValidUserCreatesAccountAndSessionCookie() throws Exception {
        var user = uniqueUser("testuser");
        var result = mvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(user))).andReturn();
        assertEquals(201, result.getResponse().getStatus());
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(user.get("email"), body.get("email").asText());
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("phonebook_session="));
    }

    @Test
    void registerDuplicateUsernameOrEmailReturnsConflict() throws Exception {
        var user = uniqueUser("testuser");
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(user))).andExpect(status().isCreated());

        Map<String, String> dupUser = new HashMap<>(user);
        dupUser.put("email", "different@example.com");
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dupUser))).andExpect(status().isConflict());

        Map<String, String> dupEmail = new HashMap<>(user);
        dupEmail.put("username", "different_username");
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dupEmail))).andExpect(status().isConflict());
    }

    @Test
    void registerInvalidDataReturnsUnprocessable() throws Exception {
        Map<String, String> bad = Map.of("username", "ab", "email", "not-an-email", "password", "short");
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void loginByUsernameAndEmailSucceeds() throws Exception {
        var user = uniqueUser("testuser");
        TestClient client = register(user);
        client.post("/auth/logout", null);
        for (String identifier : new String[]{user.get("username"), user.get("email")}) {
            var r = client.post("/auth/login", Map.of("identifier", identifier, "password", user.get("password")));
            assertEquals(200, r.getResponse().getStatus());
            JsonNode body = client.json(r);
            assertEquals(user.get("username"), body.get("username").asText());
            client.post("/auth/logout", null);
        }
    }

    @Test
    void loginInvalidCredentialsReturnsUnauthorized() throws Exception {
        var r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"missing-user\",\"password\":\"wrong-password\"}")).andReturn();
        assertEquals(401, r.getResponse().getStatus());
    }

    @Test
    void currentUserRequiresAuthentication() throws Exception {
        var anon = mvc.perform(get("/auth/me")).andReturn();
        assertEquals(401, anon.getResponse().getStatus());

        var user = uniqueUser("testuser");
        TestClient client = register(user);
        var me = client.get("/auth/me");
        assertEquals(200, me.getResponse().getStatus());
        assertEquals(user.get("email"), client.json(me).get("email").asText());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        var user = uniqueUser("testuser");
        TestClient client = register(user);
        assertEquals(200, client.get("/auth/me").getResponse().getStatus());
        assertEquals(200, client.post("/auth/logout", null).getResponse().getStatus());
        assertEquals(401, client.get("/auth/me").getResponse().getStatus());
    }

    @Test
    void passwordHashIsCompatibleWithPythonScrypt() {
        var security = new com.phonebook.service.SecurityService();
        String pythonHash = "scrypt$AAECAwQFBgcICQoLDA0ODw==$-TWpMCXcNA8tK2qNm9ZnzhACHcOUIsMX2JcTblKjZr-E0YSC3CSTf0Nt0CQ-ITS3ZVYTuhmr2CFnAN_6yGWWNA==";
        assertTrue(security.verifyPassword("StrongPass123!", pythonHash));
        assertFalse(security.verifyPassword("wrong-password", pythonHash));
        assertTrue(security.verifyPassword("round-trip-password", security.hashPassword("round-trip-password")));
    }
}
