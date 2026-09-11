package com.phonebook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record RegisterRequest(String username, String email, String password) {}
    public record LoginRequest(String identifier, String password) {}
    public record UserResponse(long id, String username, String email, Instant createdAt) {}
    public record TagRequest(String name) {}
    public record TagResponse(long id, String name) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ContactRequest(
            String name,
            @JsonProperty("phone_number") String phoneNumber,
            String email,
            String address,
            @JsonProperty("tag_ids") List<Long> tagIds) {}

    public record ContactResponse(
            long id,
            String name,
            @JsonProperty("phone_number") String phoneNumber,
            String email,
            String address,
            Instant createdAt,
            List<TagResponse> tags) {}

    public record ContactPage(
            List<ContactResponse> items,
            int page,
            int limit,
            long total,
            @JsonProperty("total_pages") int totalPages) {}

    public record ErrorResponse(String detail) {}
    public record MessageResponse(String message) {}

    public record ValidationError(String loc0, String loc1, String msg, String type) {}

    public record ImportRowError(int row, String error) {}
    public record ImportSummary(
            @JsonProperty("total_rows") int totalRows,
            int imported,
            @JsonProperty("skipped_duplicates") int skippedDuplicates,
            @JsonProperty("invalid_rows") int invalidRows,
            @JsonProperty("invalid_names") int invalidNames,
            @JsonProperty("invalid_phone_numbers") int invalidPhoneNumbers,
            @JsonProperty("invalid_emails") int invalidEmails,
            @JsonProperty("invalid_addresses") int invalidAddresses,
            @JsonProperty("row_errors") List<ImportRowError> rowErrors) {}
}
