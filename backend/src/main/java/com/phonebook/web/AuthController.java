package com.phonebook.web;

import com.phonebook.dto.Dtos;
import com.phonebook.model.User;
import com.phonebook.repo.ContactRepository;
import com.phonebook.repo.UserRepository;
import com.phonebook.service.AuthService;
import com.phonebook.service.SecurityService;
import com.phonebook.service.SequenceService;
import com.phonebook.service.Validators;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final UserRepository users;
    private final ContactRepository contacts;
    private final MongoTemplate mongoTemplate;
    private final SecurityService security;
    private final AuthService auth;
    private final SequenceService sequences;

    public AuthController(UserRepository users, ContactRepository contacts, MongoTemplate mongoTemplate,
                          SecurityService security, AuthService auth, SequenceService sequences) {
        this.users = users;
        this.contacts = contacts;
        this.mongoTemplate = mongoTemplate;
        this.security = security;
        this.auth = auth;
        this.sequences = sequences;
    }

    @PostMapping({"/auth/register"})
    public ResponseEntity<?> register(@RequestBody Map<String, Object> raw, HttpServletRequest http) {
        String username = str(raw.get("username"));
        String email = str(raw.get("email"));
        String password = str(raw.get("password"));

        List<Map<String, Object>> errors = new ArrayList<>();
        String u = username != null ? username.trim() : "";
        if (u.length() < 3 || u.length() > 100) {
            errors.add(Map.of("loc", List.of("body", "username"),
                    "msg", "Username must be between 3 and 100 characters.", "type", "value_error"));
        }
        if (email == null || email.trim().isEmpty() || !Validators.isValidEmail(email.trim())) {
            errors.add(Map.of("loc", List.of("body", "email"),
                    "msg", "Invalid email address.", "type", "value_error"));
        }
        if (password == null || password.length() < 8) {
            errors.add(Map.of("loc", List.of("body", "password"),
                    "msg", "Password must contain at least 8 characters.", "type", "value_error"));
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", errors));
        }

        String cleanUsername = username.trim();
        String cleanEmail = email.trim();
        if (users.existsByUsername(cleanUsername)) {
            throw new ApiException(HttpStatus.CONFLICT, "Username already exists.");
        }
        if (users.existsByEmail(cleanEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already exists.");
        }

        User user = new User();
        user.setId(sequences.next("users"));
        user.setUsername(cleanUsername);
        user.setEmail(cleanEmail);
        user.setPasswordHash(security.hashPassword(password));
        user.setCreatedAt(Instant.now());
        try {
            users.save(user);
        } catch (Exception e) {
            // race: re-check
            if (users.existsByUsername(cleanUsername)) throw new ApiException(HttpStatus.CONFLICT, "Username already exists.");
            if (users.existsByEmail(cleanEmail)) throw new ApiException(HttpStatus.CONFLICT, "Email already exists.");
            throw e;
        }

        if (users.count() == 1) {
            // adopt orphan contacts (userId == null) to first user
            mongoTemplate.updateMulti(new Query(Criteria.where("userId").is(null)),
                    new Update().set("userId", user.getId()), com.phonebook.model.Contact.class);
        }

        var cookie = auth.issueSession(user.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Set-Cookie", cookie.toString())
                .body(new Dtos.UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt()));
    }

    @PostMapping({"/auth/login"})
    public ResponseEntity<?> login(@RequestBody Map<String, Object> raw, HttpServletRequest http) {
        String identifier = str(raw.get("identifier"));
        String password = str(raw.get("password"));
        if (identifier == null) identifier = "";
        User user = users.findByUsernameOrEmail(identifier, identifier).orElse(null);
        // findByUsernameOrEmail with same value twice works for derived query? Spring Data interprets as username=?1 OR email=?2
        if (user == null || password == null || !security.verifyPassword(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username/email or password.");
        }
        var cookie = auth.issueSession(user.getId());
        return ResponseEntity.ok()
                .header("Set-Cookie", cookie.toString())
                .body(new Dtos.UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt()));
    }

    @GetMapping({"/auth/me"})
    public ResponseEntity<?> me(HttpServletRequest http) {
        var user = auth.currentUser(http).orElse(null);
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        return ResponseEntity.ok(new Dtos.UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt()));
    }

    @PostMapping({"/auth/logout"})
    public ResponseEntity<?> logout(HttpServletRequest http) {
        auth.revokeSession(http);
        return ResponseEntity.ok()
                .header("Set-Cookie", AuthService.clearCookie().toString())
                .body(new Dtos.MessageResponse("Logged out successfully."));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
