package com.phonebook.web;

import com.phonebook.dto.Dtos;
import com.phonebook.model.Contact;
import com.phonebook.model.Tag;
import com.phonebook.model.User;
import com.phonebook.repo.ContactRepository;
import com.phonebook.repo.TagRepository;
import com.phonebook.service.AuthService;
import com.phonebook.service.SequenceService;
import com.phonebook.service.Validators;
import jakarta.servlet.http.HttpServletRequest;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ContactController {
    private final ContactRepository contacts;
    private final TagRepository tags;
    private final AuthService auth;
    private final SequenceService sequences;
    private final MongoTemplate mongoTemplate;

    public ContactController(ContactRepository contacts, TagRepository tags, AuthService auth,
                             SequenceService sequences, MongoTemplate mongoTemplate) {
        this.contacts = contacts;
        this.tags = tags;
        this.auth = auth;
        this.sequences = sequences;
        this.mongoTemplate = mongoTemplate;
    }

    private User requireUser(HttpServletRequest http) {
        return auth.currentUser(http).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required."));
    }

    // ---------- list ----------
    @GetMapping({"/contacts", "/contacts/"})
    public Dtos.ContactPage list(
            HttpServletRequest http,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(name = "tag_ids", required = false) List<Long> tagIds) {
        User user = requireUser(http);
        if (page < 1 || limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Page must be positive and limit must be between 1 and 100.");
        }
        Criteria criteria = Criteria.where("userId").is(user.getId());
        if (search != null && !search.trim().isEmpty()) {
            String s = Pattern.quote(search.trim());
            Pattern rx = Pattern.compile(".*" + s + ".*", Pattern.CASE_INSENSITIVE);
            criteria = new Criteria().andOperator(criteria, new Criteria().orOperator(
                    Criteria.where("name").regex(rx),
                    Criteria.where("phoneNumber").regex(rx),
                    Criteria.where("email").regex(rx)));
        }
        List<Long> distinctTags = tagIds != null ? tagIds.stream().distinct().toList() : List.of();
        if (!distinctTags.isEmpty()) {
            criteria = new Criteria().andOperator(criteria, Criteria.where("tagIds").all(distinctTags));
        }
        Query countQuery = new Query(criteria);
        long total = mongoTemplate.count(countQuery, Contact.class);

        // Fetch all matching, sort by name asc in memory (to match .NET OrderBy name, case-sensitive ordinal?)
        Query query = new Query(criteria);
        List<Contact> all = mongoTemplate.find(query, Contact.class);
        all.sort(Comparator.comparing(c -> c.getName() != null ? c.getName() : "", String::compareTo));

        int totalPages = Math.max((int) ((total + limit - 1) / limit), 1);
        int from = Math.min((page - 1) * limit, all.size());
        int to = Math.min(from + limit, all.size());
        List<Contact> slice = all.subList(from, to);

        List<Dtos.ContactResponse> items = toResponses(slice);
        return new Dtos.ContactPage(items, page, limit, total, totalPages);
    }

    // ---------- create ----------
    @PostMapping({"/contacts", "/contacts/"})
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw, HttpServletRequest http) {
        User user = requireUser(http);
        String name = asString(raw.get("name"));
        String phone = asString(raw.get("phone_number"));
        if (phone == null) phone = asString(raw.get("phoneNumber"));
        String email = asString(raw.get("email"));
        String address = asString(raw.get("address"));
        List<Long> tagIds = asLongList(raw.get("tag_ids"));
        if (tagIds == null) tagIds = asLongList(raw.get("tagIds"));

        Set<String> fields = new HashSet<>();
        String err = Validators.collectContactErrors(name, phone, email, address, fields, false);
        if (err != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, err);

        String tagErr = ensureOwnedTags(tagIds, user.getId());
        if (tagErr != null) throw new ApiException(HttpStatus.BAD_REQUEST, tagErr);

        String cleanPhone = phone.trim();
        String cleanEmail = (email == null || email.trim().isEmpty()) ? null : email.trim();
        if (contacts.existsByPhoneNumber(cleanPhone)) {
            throw new ApiException(HttpStatus.CONFLICT, "This phone number already exists.");
        }
        if (cleanEmail != null && contacts.existsByEmail(cleanEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "This email address already exists.");
        }

        Contact contact = new Contact();
        contact.setId(sequences.next("contacts"));
        contact.setUserId(user.getId());
        contact.setName(name.trim());
        contact.setPhoneNumber(cleanPhone);
        contact.setEmail(cleanEmail);
        contact.setAddress(address == null || address.trim().isEmpty() ? null : address.trim());
        contact.setCreatedAt(Instant.now());
        contact.setTagIds(tagIds != null ? distinct(tagIds) : new ArrayList<>());
        try {
            contacts.save(contact);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.CONFLICT, "A contact with this phone number or email already exists.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(contact));
    }

    // ---------- export (must be before /contacts/{id}) ----------
    @GetMapping("/contacts/export")
    public ResponseEntity<?> exportCsv(HttpServletRequest http) {
        User user = requireUser(http);
        List<Contact> all = contacts.findByUserIdOrderByNameAsc(user.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("Name,Phone number,Email,Address\n");
        for (Contact c : all) {
            sb.append(csvField(c.getName())).append(",");
            sb.append(csvField("=\"" + c.getPhoneNumber() + "\"")).append(",");
            sb.append(csvField(c.getEmail() != null ? c.getEmail() : "")).append(",");
            sb.append(csvField(c.getAddress() != null ? c.getAddress() : "")).append("\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=phonebook.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(bytes);
    }

    // ---------- import ----------
    @PostMapping("/contacts/import")
    public ResponseEntity<?> importCsv(@RequestParam(value = "file", required = false) MultipartFile file, HttpServletRequest http) {
        User user = requireUser(http);
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                || !file.getOriginalFilename().toLowerCase().endsWith(".csv")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please upload a CSV file.");
        }
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The CSV file is empty.");
        }
        String text;
        try {
            byte[] bytes = file.getBytes();
            // strict UTF-8 check
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The CSV file must use UTF-8 encoding.");
        }
        if (text.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The CSV file is empty.");
        }

        List<String> headers;
        List<CSVRecord> records;
        try {
            CSVParser parser = CSVParser.parse(text, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreEmptyLines(false).withTrim(false));
            headers = parser.getHeaderNames();
            records = parser.getRecords();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The CSV file is empty.");
        }
        if (headers == null || headers.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The CSV file is empty.");
        }

        Set<String> normalized = headers.stream().map(Validators::normalizeHeader).collect(Collectors.toSet());
        Set<String> nameAliases = Set.of("name", "full_name");
        Set<String> firstAliases = Set.of("first_name", "firstname");
        Set<String> lastAliases = Set.of("last_name", "lastname");
        Set<String> phoneAliases = Set.of("phone", "phone_number", "mobile", "mobile_number");
        boolean hasName = normalized.stream().anyMatch(nameAliases::contains)
                || normalized.stream().anyMatch(firstAliases::contains)
                || normalized.stream().anyMatch(lastAliases::contains);
        boolean hasPhone = normalized.stream().anyMatch(phoneAliases::contains);
        if (!hasName || !hasPhone) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV must include a name or first/last name column and a phone column.");
        }

        Set<String> seenPhones = contacts.findAll().stream().map(Contact::getPhoneNumber).collect(Collectors.toSet());
        Set<String> seenEmails = contacts.findAll().stream()
                .filter(c -> c.getEmail() != null).map(Contact::getEmail).collect(Collectors.toSet());

        int totalRows = 0, imported = 0, skipped = 0, invalidRows = 0;
        int invalidNames = 0, invalidPhones = 0, invalidEmails = 0, invalidAddresses = 0;
        List<Dtos.ImportRowError> rowErrors = new ArrayList<>();
        List<Contact> toSave = new ArrayList<>();

        int rowNumber = 1;
        for (CSVRecord record : records) {
            rowNumber++;
            totalRows++;
            String name = csvValue(record, headers, nameAliases);
            if (name == null) {
                String first = csvValue(record, headers, firstAliases);
                String last = csvValue(record, headers, lastAliases);
                List<String> parts = new ArrayList<>();
                if (first != null && !first.isBlank()) parts.add(first.trim());
                if (last != null && !last.isBlank()) parts.add(last.trim());
                name = parts.isEmpty() ? null : String.join(" ", parts);
            }
            String phone = csvValue(record, headers, phoneAliases);
            if (phone == null) phone = "";
            String email = csvValue(record, headers, Set.of("email", "email_address"));
            String address = csvValue(record, headers, Set.of("address", "street_address"));
            if (address == null) {
                String city = csvValue(record, headers, Set.of("city", "town"));
                String country = csvValue(record, headers, Set.of("country", "country_name"));
                List<String> parts = new ArrayList<>();
                if (city != null && !city.isBlank()) parts.add(city.trim());
                if (country != null && !country.isBlank()) parts.add(country.trim());
                address = parts.isEmpty() ? null : String.join(" ", parts);
            }

            Set<String> fields = new HashSet<>();
            String error;
            try {
                phone = Validators.normalizePhone(phone == null ? "" : phone);
                error = Validators.collectContactErrors(name, phone, email, address, fields, false);
            } catch (IllegalStateException ex) {
                fields.add("phone_number");
                error = ex.getMessage();
            }
            if (error != null) {
                invalidRows++;
                if (fields.contains("name")) invalidNames++;
                if (fields.contains("phone_number")) invalidPhones++;
                if (fields.contains("email")) invalidEmails++;
                if (fields.contains("address")) invalidAddresses++;
                rowErrors.add(new Dtos.ImportRowError(rowNumber, error));
                continue;
            }
            String normEmail = (email == null || email.trim().isEmpty()) ? null : email.trim();
            if (seenPhones.contains(phone) || (normEmail != null && seenEmails.contains(normEmail))) {
                skipped++;
                continue;
            }
            Contact c = new Contact();
            c.setId(sequences.next("contacts"));
            c.setUserId(user.getId());
            c.setName(name.trim());
            c.setPhoneNumber(phone);
            c.setEmail(normEmail);
            c.setAddress(address == null || address.trim().isEmpty() ? null : address.trim());
            c.setCreatedAt(Instant.now());
            c.setTagIds(new ArrayList<>());
            toSave.add(c);
            seenPhones.add(phone);
            if (normEmail != null) seenEmails.add(normEmail);
            imported++;
        }
        if (!toSave.isEmpty()) contacts.saveAll(toSave);

        var summary = new Dtos.ImportSummary(totalRows, imported, skipped, invalidRows,
                invalidNames, invalidPhones, invalidEmails, invalidAddresses, rowErrors);
        return ResponseEntity.ok(summary);
    }

    // ---------- get / update / delete ----------
    @GetMapping("/contacts/{id}")
    public ResponseEntity<?> getOne(@PathVariable long id, HttpServletRequest http) {
        User user = requireUser(http);
        Contact contact = contacts.findByIdAndUserId(id, user.getId()).orElse(null);
        if (contact == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Dtos.ErrorResponse("Contact not found."));
        return ResponseEntity.ok(toResponse(contact));
    }

    @PutMapping("/contacts/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> raw, HttpServletRequest http) {
        User user = requireUser(http);
        Contact contact = contacts.findByIdAndUserId(id, user.getId()).orElse(null);
        if (contact == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Dtos.ErrorResponse("Contact not found."));

        boolean hasName = raw.containsKey("name");
        boolean hasPhone = raw.containsKey("phone_number") || raw.containsKey("phoneNumber");
        boolean hasEmail = raw.containsKey("email");
        boolean hasAddress = raw.containsKey("address");
        boolean hasTags = raw.containsKey("tag_ids") || raw.containsKey("tagIds");

        String name = hasName ? asString(raw.get("name")) : null;
        String phone = null;
        if (raw.containsKey("phone_number")) phone = asString(raw.get("phone_number"));
        else if (raw.containsKey("phoneNumber")) phone = asString(raw.get("phoneNumber"));
        else if (hasPhone) phone = null;
        String email = hasEmail ? asString(raw.get("email")) : null;
        String address = hasAddress ? asString(raw.get("address")) : null;
        List<Long> tagIds = null;
        if (raw.containsKey("tag_ids")) tagIds = asLongList(raw.get("tag_ids"));
        else if (raw.containsKey("tagIds")) tagIds = asLongList(raw.get("tagIds"));

        // validation: if none of the 4 contact fields present at all -> allow (tag-only update)
        if (hasName || hasPhone || hasEmail || hasAddress) {
            Set<String> fields = new HashSet<>();
            String vName = hasName ? name : null;
            String vPhone = hasPhone ? phone : null;
            String vEmail = hasEmail ? email : null;
            String vAddress = hasAddress ? address : null;
            // partial=true but only validate present keys: pass null for absent so validator skips
            // For present-but-null (explicit JSON null), treat as absent (skip) to mirror .NET (null = no change)
            String err = Validators.collectContactErrors(vName, vPhone, vEmail, vAddress, fields, true);
            // collectContactErrors with partial=true skips nulls, so explicit null = skip. Good.
            if (err != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, err);
        }

        if (tagIds != null) {
            String tagErr = ensureOwnedTags(tagIds, user.getId());
            if (tagErr != null) throw new ApiException(HttpStatus.BAD_REQUEST, tagErr);
        }

        if (hasPhone && phone != null) {
            String cleanPhone = phone.trim();
            var existing = contacts.findByPhoneNumber(cleanPhone).orElse(null);
            if (existing != null && !existing.getId().equals(contact.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "This phone number already exists.");
            }
        }
        if (hasEmail && email != null) {
            String cleanEmail = email.trim().isEmpty() ? null : email.trim();
            if (cleanEmail != null) {
                var existing = contacts.findByEmail(cleanEmail).orElse(null);
                if (existing != null && !existing.getId().equals(contact.getId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "This email address already exists.");
                }
            }
        }

        if (hasName && name != null) contact.setName(name.trim());
        if (hasPhone && phone != null) contact.setPhoneNumber(phone.trim());
        if (hasEmail && email != null) contact.setEmail(email.trim().isEmpty() ? null : email.trim());
        if (hasAddress && address != null) contact.setAddress(address.trim().isEmpty() ? null : address.trim());
        if (tagIds != null) contact.setTagIds(distinct(tagIds));
        try {
            contacts.save(contact);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.CONFLICT, "A contact with this phone number or email already exists.");
        }
        return ResponseEntity.ok(toResponse(contact));
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, HttpServletRequest http) {
        User user = requireUser(http);
        Contact contact = contacts.findByIdAndUserId(id, user.getId()).orElse(null);
        if (contact == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Dtos.ErrorResponse("Contact not found."));
        contacts.delete(contact);
        return ResponseEntity.ok(new Dtos.MessageResponse("Contact deleted successfully."));
    }

    // ---------- helpers ----------
    private String ensureOwnedTags(List<Long> tagIds, Long userId) {
        if (tagIds == null) return null;
        List<Long> unique = distinct(tagIds);
        if (unique.stream().anyMatch(t -> t <= 0)) return "One or more tags were not found.";
        if (unique.isEmpty()) return null;
        long owned = tags.countByUserIdAndIdIn(userId, unique);
        return owned == unique.size() ? null : "One or more tags were not found.";
    }

    private Dtos.ContactResponse toResponse(Contact c) {
        List<Dtos.TagResponse> tagResponses = new ArrayList<>();
        if (c.getTagIds() != null && !c.getTagIds().isEmpty()) {
            List<Tag> found = tags.findByIdIn(c.getTagIds());
            Map<Long, Tag> byId = new HashMap<>();
            for (Tag t : found) byId.put(t.getId(), t);
            for (Long tid : c.getTagIds()) {
                Tag t = byId.get(tid);
                if (t != null) tagResponses.add(new Dtos.TagResponse(t.getId(), t.getName()));
            }
            tagResponses.sort(Comparator.comparing(Dtos.TagResponse::name));
        }
        return new Dtos.ContactResponse(c.getId(), c.getName(), c.getPhoneNumber(), c.getEmail(),
                c.getAddress(), c.getCreatedAt(), tagResponses);
    }

    private List<Dtos.ContactResponse> toResponses(List<Contact> list) {
        Set<Long> allTagIds = new HashSet<>();
        for (Contact c : list) if (c.getTagIds() != null) allTagIds.addAll(c.getTagIds());
        Map<Long, Tag> byId = new HashMap<>();
        if (!allTagIds.isEmpty()) {
            for (Tag t : tags.findByIdIn(new ArrayList<>(allTagIds))) byId.put(t.getId(), t);
        }
        List<Dtos.ContactResponse> out = new ArrayList<>();
        for (Contact c : list) {
            List<Dtos.TagResponse> tr = new ArrayList<>();
            if (c.getTagIds() != null) {
                for (Long tid : c.getTagIds()) {
                    Tag t = byId.get(tid);
                    if (t != null) tr.add(new Dtos.TagResponse(t.getId(), t.getName()));
                }
                tr.sort(Comparator.comparing(Dtos.TagResponse::name));
            }
            out.add(new Dtos.ContactResponse(c.getId(), c.getName(), c.getPhoneNumber(), c.getEmail(),
                    c.getAddress(), c.getCreatedAt(), tr));
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<Long> asLongList(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list) {
            List<Long> out = new ArrayList<>();
            for (Object e : list) {
                if (e instanceof Number n) out.add(n.longValue());
                else {
                    try { out.add(Long.parseLong(String.valueOf(e))); }
                    catch (NumberFormatException ex) { out.add(-1L); }
                }
            }
            return out;
        }
        return null;
    }

    private static List<Long> distinct(List<Long> in) {
        return in.stream().distinct().toList();
    }

    private static String csvField(String value) {
        if (value == null) return "";
        boolean needQuote = value.contains(",") || value.contains("\"") || value.contains("\n");
        if (!needQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String csvValue(CSVRecord record, List<String> headers, Set<String> aliases) {
        for (int i = 0; i < headers.size(); i++) {
            if (aliases.contains(Validators.normalizeHeader(headers.get(i)))) {
                String v = i < record.size() ? record.get(i) : null;
                if (v != null) {
                    v = v.trim();
                    return v.isEmpty() ? null : v;
                }
                return null;
            }
        }
        return null;
    }
}
