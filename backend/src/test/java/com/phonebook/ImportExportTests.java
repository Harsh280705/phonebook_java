package com.phonebook;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;

public class ImportExportTests extends ApiTestBase {

    private MockMultipartFile csvFile(String content, String filename) {
        return new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void exportAndImportRequireAuthentication() throws Exception {
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/contacts/export")).andReturn().getResponse().getStatus());
        MockMultipartFile file = csvFile("name,phone_number\nA,+14155550101\n", "contacts.csv");
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/contacts/import").file(file)).andReturn().getResponse().getStatus());
    }

    @Test
    void exportReturnsOnlyAuthenticatedUsersContacts() throws Exception {
        TestClient first = register(uniqueUser("first"));
        JsonNode created = first.json(first.post("/contacts/", contactPayload()));
        first.post("/auth/logout", null);

        TestClient second = register(Map.of("username", "export_other_user", "email", "export_other_user@example.com", "password", "StrongPass123!"));
        Map<String, Object> other = contactPayload();
        other.put("name", "Other User Contact");
        other.put("phone_number", "+14155550102");
        other.put("email", "other@example.com");
        assertEquals(201, second.post("/contacts/", other).getResponse().getStatus());

        var response = second.get("/contacts/export");
        assertEquals(200, response.getResponse().getStatus());
        String contentType = response.getResponse().getContentType();
        assertTrue(contentType != null && contentType.startsWith("text/csv"));
        String csv = response.getResponse().getContentAsString();
        assertTrue(csv.contains("Name,Phone number,Email,Address"));
        assertTrue(csv.contains("Other User Contact"));
        assertTrue(csv.contains("+14155550102"));
        assertFalse(csv.contains(created.get("name").asText()));
    }

    @Test
    void importValidCsvPersistsContacts() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        String content = "name,phone_number,email,address\n"
                + "Imported Alpha,+14155550121,alpha@example.com,123 Import Street\n"
                + "Imported Beta,+14155550122,beta@example.com,456 Import Avenue\n";
        var response = client.multipart("/contacts/import", csvFile(content, "contacts.csv"));
        assertEquals(200, response.getResponse().getStatus());
        JsonNode summary = client.json(response);
        JsonNode contacts = client.json(client.get("/contacts/?page=1&limit=10&search=Imported"));
        assertEquals(2, summary.get("total_rows").asInt());
        assertEquals(2, summary.get("imported").asInt());
        assertEquals(2, contacts.get("total").asInt());
        Set<String> emails = new HashSet<>();
        contacts.get("items").forEach(item -> emails.add(item.get("email").asText()));
        assertEquals(Set.of("alpha@example.com", "beta@example.com"), emails);
    }

    @Test
    void importExternalColumnsAndExcelPhoneText() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        String content = "First Name,Last Name,Email,Phone,City,Country\n"
                + "External,Contact,external@example.com,=\"+14155550131\",Mumbai,India\n";
        var response = client.multipart("/contacts/import", csvFile(content, "contacts.csv"));
        assertEquals(200, response.getResponse().getStatus());
        JsonNode summary = client.json(response);
        JsonNode contacts = client.json(client.get("/contacts/?page=1&limit=10&search=External"));
        assertEquals(1, summary.get("imported").asInt());
        assertEquals("+14155550131", contacts.get("items").get(0).get("phone_number").asText());
        assertEquals("Mumbai India", contacts.get("items").get(0).get("address").asText());
    }

    @Test
    void importInvalidRowsAndScientificNotation() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        String content = "name,phone_number,email,address\n"
                + "Invalid Phone,9.11235E+11,valid@example.com,123 Invalid Street\n"
                + "Invalid Email,+14155550132,not-an-email,123 Invalid Street\n"
                + ",+14155550133,missing@example.com,123 Invalid Street\n";
        var response = client.multipart("/contacts/import", csvFile(content, "contacts.csv"));
        assertEquals(200, response.getResponse().getStatus());
        JsonNode summary = client.json(response);
        assertEquals(3, summary.get("total_rows").asInt());
        assertEquals(0, summary.get("imported").asInt());
        assertEquals(3, summary.get("invalid_rows").asInt());
        assertEquals(1, summary.get("invalid_phone_numbers").asInt());
        assertEquals(1, summary.get("invalid_emails").asInt());
        assertEquals(1, summary.get("invalid_names").asInt());
    }

    @Test
    void importDuplicatesAreSkipped() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        String content = "name,phone_number,email,address\n"
                + "First Import,+14155550141,first@example.com,123 Duplicate Street\n"
                + "Duplicate Phone,+14155550141,second@example.com,456 Duplicate Street\n"
                + "Duplicate Email,+14155550142,first@example.com,789 Duplicate Street\n";
        JsonNode first = client.json(client.multipart("/contacts/import", csvFile(content, "contacts.csv")));
        JsonNode second = client.json(client.multipart("/contacts/import", csvFile(content, "contacts.csv")));
        assertEquals(1, first.get("imported").asInt());
        assertEquals(2, first.get("skipped_duplicates").asInt());
        assertEquals(0, second.get("imported").asInt());
        assertEquals(3, second.get("skipped_duplicates").asInt());
    }

    @Test
    void importRejectsMissingHeaders() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        var response = client.multipart("/contacts/import", csvFile("email,address\na@example.com,123 Street\n", "contacts.csv"));
        assertEquals(400, response.getResponse().getStatus());
        JsonNode body = client.json(response);
        assertTrue(body.get("detail").asText().toLowerCase().contains("name"));
    }

    @Test
    void importRejectsNonCsvAndEmptyFiles() throws Exception {
        TestClient client = register(uniqueUser("testuser"));
        assertEquals(400, client.multipart("/contacts/import", csvFile("name,phone\nTest,+14155550151\n", "contacts.txt")).getResponse().getStatus());
        assertEquals(400, client.multipart("/contacts/import", csvFile("", "empty.csv")).getResponse().getStatus());
    }
}
