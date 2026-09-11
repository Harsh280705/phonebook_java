package com.phonebook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ContactTests extends ApiTestBase {

    @Test
    void protectedContactEndpointsRequireAuthentication() throws Exception {
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/contacts/")).andReturn().getResponse().getStatus());
        var payload = contactPayload();
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/contacts/")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/contacts/1")).andReturn().getResponse().getStatus());
    }

    @Test
    void createGetAndListContacts() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        var created = client.post("/contacts/", contactPayload());
        assertEquals(201, created.getResponse().getStatus());
        JsonNode contact = client.json(created);

        var single = client.get("/contacts/" + contact.get("id").asLong());
        var listing = client.get("/contacts/?page=1&limit=10");
        assertEquals(200, single.getResponse().getStatus());
        assertEquals("+14155550101", client.json(single).get("phone_number").asText());
        assertEquals(200, listing.getResponse().getStatus());
        assertEquals(1, client.json(listing).get("total").asInt());
        assertEquals(contact.get("id").asLong(), client.json(listing).get("items").get(0).get("id").asLong());
    }

    @Test
    void createInvalidContactDataReturnsUnprocessable() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        Map<String, Object> invalid = contactPayload();
        invalid.put("phone_number", "not-a-phone");
        assertEquals(422, client.post("/contacts/", invalid).getResponse().getStatus());
    }

    @Test
    void duplicatePhoneAndEmailAreRejected() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        assertEquals(201, client.post("/contacts/", contactPayload()).getResponse().getStatus());

        Map<String, Object> dupPhone = contactPayload();
        dupPhone.put("email", "other@example.com");
        assertEquals(409, client.post("/contacts/", dupPhone).getResponse().getStatus());

        Map<String, Object> dupEmail = contactPayload();
        dupEmail.put("phone_number", "+14155550102");
        assertEquals(409, client.post("/contacts/", dupEmail).getResponse().getStatus());
    }

    @Test
    void updateContactChangesProvidedFields() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        JsonNode created = client.json(client.post("/contacts/", contactPayload()));
        var response = client.put("/contacts/" + created.get("id").asLong(),
                Map.of("name", "Updated Contact", "address", "456 Updated Avenue"));
        assertEquals(200, response.getResponse().getStatus());
        JsonNode body = client.json(response);
        assertEquals("Updated Contact", body.get("name").asText());
        assertEquals("456 Updated Avenue", body.get("address").asText());
    }

    @Test
    void deleteContactAndMissingContact() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        JsonNode created = client.json(client.post("/contacts/", contactPayload()));
        long id = created.get("id").asLong();
        assertEquals(200, client.delete("/contacts/" + id).getResponse().getStatus());
        assertEquals(404, client.get("/contacts/" + id).getResponse().getStatus());
        assertEquals(404, client.delete("/contacts/999999").getResponse().getStatus());
    }

    @Test
    void searchAndPagination() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        for (int i = 0; i < 12; i++) {
            char letter = (char) ('A' + i);
            Map<String, Object> p = new HashMap<>();
            p.put("name", "Search Person " + letter);
            p.put("phone_number", String.format("+1415555%04d", 1000 + i));
            p.put("email", "search" + i + "@example.com");
            p.put("address", "123 Search Street");
            assertEquals(201, client.post("/contacts/", p).getResponse().getStatus());
        }
        JsonNode page = client.json(client.get("/contacts/?page=2&limit=10"));
        JsonNode search = client.json(client.get("/contacts/?page=1&limit=10&search=Search Person A"));
        assertEquals(2, page.get("page").asInt());
        assertEquals(10, page.get("limit").asInt());
        assertEquals(12, page.get("total").asInt());
        assertEquals(2, page.get("items").size());
        assertEquals(1, search.get("total").asInt());
        assertEquals("Search Person A", search.get("items").get(0).get("name").asText());
    }

    @Test
    void usersCannotAccessEachOthersContacts() throws Exception {
        TestClient first = register(uniqueUser("first"));
        JsonNode created = first.json(first.post("/contacts/", contactPayload()));
        first.post("/auth/logout", null);

        Map<String, String> secondUser = Map.of("username", "second-user", "email", "second-user@example.com", "password", "StrongPass123!");
        // may already exist from other tests? DB is cleaned per test, so fine
        TestClient second = register(new java.util.HashMap<>(secondUser));
        JsonNode listing = second.json(second.get("/contacts/?page=1&limit=10"));
        assertEquals(404, second.get("/contacts/" + created.get("id").asLong()).getResponse().getStatus());
        assertEquals(404, second.put("/contacts/" + created.get("id").asLong(), Map.of("name", "Hijacked Contact")).getResponse().getStatus());
        assertEquals(404, second.delete("/contacts/" + created.get("id").asLong()).getResponse().getStatus());
        assertEquals(0, listing.get("total").asInt());
    }
}
