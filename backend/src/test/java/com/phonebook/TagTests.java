package com.phonebook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TagTests extends ApiTestBase {

    private long createTag(TestClient client, String name) throws Exception {
        var r = client.post("/tags/", Map.of("name", name));
        assertEquals(201, r.getResponse().getStatus());
        return client.json(r).get("id").asLong();
    }

    private long createTaggedContact(TestClient client, String name, String phone, String email, List<Long> tagIds) throws Exception {
        var r = client.post("/contacts/", Map.of("name", name, "phone_number", phone, "email", email,
                "address", "123 Test Street", "tag_ids", tagIds));
        assertEquals(201, r.getResponse().getStatus());
        return client.json(r).get("id").asLong();
    }

    @Test
    void protectedTagEndpointsRequireAuthentication() throws Exception {
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/tags/")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/tags/")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"name\":\"Work\"}")).andReturn().getResponse().getStatus());
    }

    @Test
    void createListUpdateAndDeleteTags() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        var created = client.post("/tags/", Map.of("name", " Family "));
        assertEquals(201, created.getResponse().getStatus());
        JsonNode tag = client.json(created);
        assertEquals("Family", tag.get("name").asText());

        JsonNode listing = client.json(client.get("/tags/"));
        assertEquals(1, listing.size());
        assertEquals(tag.get("id").asLong(), listing.get(0).get("id").asLong());

        var updated = client.put("/tags/" + tag.get("id").asLong(), Map.of("name", "Personal"));
        assertEquals(200, updated.getResponse().getStatus());
        assertEquals("Personal", client.json(updated).get("name").asText());

        assertEquals(200, client.delete("/tags/" + tag.get("id").asLong()).getResponse().getStatus());
        assertEquals(0, client.json(client.get("/tags/")).size());
        assertEquals(404, client.delete("/tags/999999").getResponse().getStatus());
    }

    @Test
    void duplicateAndInvalidTagNamesAreRejected() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        assertEquals(201, client.post("/tags/", Map.of("name", "Work")).getResponse().getStatus());
        assertEquals(409, client.post("/tags/", Map.of("name", "work")).getResponse().getStatus());
        assertEquals(422, client.post("/tags/", Map.of("name", "")).getResponse().getStatus());
        assertEquals(422, client.post("/tags/", Map.of("name", "bad,name")).getResponse().getStatus());
    }

    @Test
    void assignMultipleTagsToContactsAndClearThem() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        long work = createTag(client, "Work");
        long family = createTag(client, "Family");

        var created = client.post("/contacts/", Map.of("name", "Tagged Contact", "phone_number", "+14155550101",
                "email", "tagged@example.com", "address", "123 Test Street", "tag_ids", List.of(work, family)));
        assertEquals(201, created.getResponse().getStatus());
        assertEquals(2, client.json(created).get("tags").size());

        long contactId = client.json(created).get("id").asLong();
        var updated = client.put("/contacts/" + contactId, Map.of("tag_ids", List.of(work)));
        assertEquals(200, updated.getResponse().getStatus());
        JsonNode updatedBody = client.json(updated);
        assertEquals(1, updatedBody.get("tags").size());
        assertEquals("Work", updatedBody.get("tags").get(0).get("name").asText());

        var cleared = client.put("/contacts/" + contactId, Map.of("tag_ids", List.of()));
        assertEquals(0, client.json(cleared).get("tags").size());
    }

    @Test
    void updateWithoutTagIdsLeavesTagsUnchanged() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        long work = createTag(client, "Work");
        var createdRes = client.post("/contacts/", Map.of("name", "Tagged Contact", "phone_number", "+14155550101",
                "email", "tagged@example.com", "address", "123 Test Street", "tag_ids", List.of(work)));
        JsonNode created = client.json(createdRes);

        var updated = client.put("/contacts/" + created.get("id").asLong(), Map.of("name", "Tagged Contact Updated"));
        assertEquals(200, updated.getResponse().getStatus());
        JsonNode body = client.json(updated);
        assertEquals("Tagged Contact Updated", body.get("name").asText());
        assertEquals(1, body.get("tags").size());
        assertEquals(work, body.get("tags").get(0).get("id").asLong());
    }

    @Test
    void filterContactsByTagsAndCombineWithTextSearch() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        long work = createTag(client, "Work");
        long family = createTag(client, "Family");
        createTaggedContact(client, "Alice Work", "+14155551001", "alice-work@example.com", List.of(work));
        createTaggedContact(client, "Alice Family", "+14155551002", "alice-family@example.com", List.of(family));
        createTaggedContact(client, "Bob Both", "+14155551003", "bob-both@example.com", List.of(work, family));

        JsonNode byWork = client.json(client.get("/contacts/?page=1&limit=10&tag_ids=" + work));
        JsonNode byBoth = client.json(client.get("/contacts/?page=1&limit=10&tag_ids=" + work + "&tag_ids=" + family));
        JsonNode combined = client.json(client.get("/contacts/?page=1&limit=10&search=Alice&tag_ids=" + work));
        JsonNode paged = client.json(client.get("/contacts/?page=1&limit=1&tag_ids=" + work));

        assertEquals(2, byWork.get("total").asInt());
        assertEquals(1, byBoth.get("total").asInt());
        assertEquals("Bob Both", byBoth.get("items").get(0).get("name").asText());
        assertEquals(1, combined.get("total").asInt());
        assertEquals("Alice Work", combined.get("items").get(0).get("name").asText());
        assertEquals(2, paged.get("total").asInt());
        assertEquals(1, paged.get("items").size());
        assertEquals(2, paged.get("total_pages").asInt());
    }

    @Test
    void usersCannotAccessEachOthersTagsOrAssignThem() throws Exception {
        TestClient first = register(uniqueUser("first"));
        long firstTag = createTag(first, "Secret");
        long firstContact = createTaggedContact(first, "First Person", "+14155552001", "first-person@example.com", List.of(firstTag));
        first.post("/auth/logout", null);

        TestClient second = register(uniqueUser("second"));
        assertEquals(0, second.json(second.get("/tags/")).size());
        assertEquals(404, second.put("/tags/" + firstTag, Map.of("name", "Stolen")).getResponse().getStatus());
        assertEquals(404, second.delete("/tags/" + firstTag).getResponse().getStatus());

        long ownTag = createTag(second, "Mine");
        assertEquals(400, second.post("/contacts/", Map.of("name", "Second Person", "phone_number", "+14155552002",
                "email", "second-person@example.com", "address", "123 Test Street", "tag_ids", List.of(firstTag))).getResponse().getStatus());

        long ownContact = createTaggedContact(second, "Second Person", "+14155552002", "second-person@example.com", List.of(ownTag));
        assertEquals(400, second.put("/contacts/" + ownContact, Map.of("tag_ids", List.of(firstTag))).getResponse().getStatus());

        assertEquals(0, second.json(second.get("/contacts/?page=1&limit=10&tag_ids=" + firstTag)).get("total").asInt());
        assertEquals(404, second.get("/contacts/" + firstContact).getResponse().getStatus());
    }
}
