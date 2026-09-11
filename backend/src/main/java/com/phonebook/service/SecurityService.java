package com.phonebook.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Service;

@Service
public class SecurityService {
    private static final int SCRYPT_N = 16384;
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;
    private static final int DK_LEN = 64;

    private final SecureRandom random = new SecureRandom();

    public String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] digest = derive(password, salt);
        return "scrypt$" + base64UrlEncode(salt) + "$" + base64UrlEncode(digest);
    }

    public boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null) return false;
        String[] parts = storedHash.split("\\$", -1);
        if (parts.length != 3 || !"scrypt".equals(parts[0])) return false;
        try {
            byte[] salt = base64UrlDecode(parts[1]);
            byte[] expected = base64UrlDecode(parts[2]);
            byte[] actual = derive(password, salt);
            return MessageDigest.isEqual(actual, expected);
        } catch (Exception e) {
            return false;
        }
    }

    public String createSessionToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return base64UrlEncode(bytes);
    }

    public String hashSessionToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        return SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, DK_LEN);
    }

    static String base64UrlEncode(byte[] value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static byte[] base64UrlDecode(String value) {
        String padded = value.replace('-', '+').replace('_', '/');
        int rem = padded.length() % 4;
        if (rem == 2) padded += "==";
        else if (rem == 3) padded += "=";
        else if (rem == 1) throw new IllegalArgumentException("Invalid base64 length");
        // standard decoder also accepts padded url-safe after replacement
        return java.util.Base64.getDecoder().decode(padded);
    }
}
