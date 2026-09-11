package com.phonebook.service;

import com.phonebook.model.AuthSession;
import com.phonebook.model.User;
import com.phonebook.repo.AuthSessionRepository;
import com.phonebook.repo.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public static final String COOKIE_NAME = "phonebook_session";

    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final SecurityService security;

    public AuthService(AuthSessionRepository sessions, UserRepository users, SecurityService security) {
        this.sessions = sessions;
        this.users = users;
        this.security = security;
    }

    public Optional<User> currentUser(HttpServletRequest request) {
        String token = sessionToken(request);
        if (token == null) return Optional.empty();
        String hash = security.hashSessionToken(token);
        Optional<AuthSession> session = sessions.findByTokenHash(hash);
        if (session.isEmpty()) return Optional.empty();
        if (session.get().getExpiresAt() == null || session.get().getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return users.findById(session.get().getUserId());
    }

    public static String sessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    public ResponseCookie issueSession(Long userId) {
        String token = security.createSessionToken();
        AuthSession session = new AuthSession();
        session.setTokenHash(security.hashSessionToken(token));
        session.setUserId(userId);
        session.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        session.setCreatedAt(Instant.now());
        sessions.save(session);
        return sessionCookie(token, Duration.ofDays(7));
    }

    public void revokeSession(HttpServletRequest request) {
        String token = sessionToken(request);
        if (token != null) {
            sessions.deleteByTokenHash(security.hashSessionToken(token));
        }
    }

    public static ResponseCookie sessionCookie(String token, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(false)
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .sameSite("Lax")
                .secure(false)
                .path("/")
                .maxAge(0)
                .build();
    }
}
