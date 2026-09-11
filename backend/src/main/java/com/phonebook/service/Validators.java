package com.phonebook.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class Validators {
    private Validators() {}

    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-zÀ-ÿ0-9][A-Za-zÀ-ÿ0-9\\s'-]{0,49}$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-zÀ-ÿ][A-Za-zÀ-ÿ\\s'-]{1,99}$");
    private static final Pattern PHONE_ALLOWED = Pattern.compile("^\\+?[0-9()\\s-]+$");
    private static final Pattern ADDRESS_ALLOWED = Pattern.compile("^[A-Za-zÀ-ÿ0-9\\s,.'#/-]+$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-zÀ-ÿ]");
    private static final Pattern SCIENTIFIC = Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)[eE][+-]?\\d+$");
    private static final Pattern EXCEL_PHONE = Pattern.compile("^=\"([^\"]*)\"$");

    public static class FieldError {
        public final Set<String> fields = new HashSet<>();
        public final List<String> messages = new ArrayList<>();
    }

    public static String validateTagName(String raw) {
        String cleaned = raw != null ? raw.trim() : "";
        if (cleaned.length() < 1 || cleaned.length() > 50 || !TAG_PATTERN.matcher(cleaned).matches()) {
            return "Tag name must be 1 to 50 characters and contain only letters, numbers, spaces, apostrophes or hyphens.";
        }
        return null;
    }

    public static String collectContactErrors(String name, String phone, String email, String address, Set<String> fields, boolean partial) {
        List<String> messages = new ArrayList<>();
        if (!partial || name != null) {
            if (name == null || name.trim().isEmpty() || !NAME_PATTERN.matcher(name.trim()).matches()) {
                fields.add("name");
                messages.add("Name must contain only letters, spaces, apostrophes or hyphens.");
            }
        }
        if (!partial || phone != null) {
            if (phone == null || phone.trim().isEmpty() || !PHONE_ALLOWED.matcher(phone.trim()).matches()) {
                fields.add("phone_number");
                messages.add("Phone number may contain digits, a leading +, spaces, hyphens, or parentheses.");
            } else {
                String digits = phone.replaceAll("\\D", "");
                if (digits.length() < 8 || digits.length() > 15) {
                    fields.add("phone_number");
                    messages.add("Phone number must contain between 8 and 15 digits.");
                }
            }
        }
        if (email != null && !email.trim().isEmpty()) {
            if (!isValidEmail(email.trim())) {
                fields.add("email");
                messages.add("Invalid email address.");
            }
        }
        if (address != null && !address.trim().isEmpty()) {
            String cleaned = address.trim();
            if (cleaned.length() < 5 || cleaned.length() > 255) {
                fields.add("address");
                messages.add("Address must be between 5 and 255 characters.");
            } else if (!HAS_LETTER.matcher(cleaned).find()) {
                fields.add("address");
                messages.add("Address must contain at least one letter.");
            } else if (!ADDRESS_ALLOWED.matcher(cleaned).matches()) {
                fields.add("address");
                messages.add("Address contains invalid characters.");
            }
        }
        return messages.isEmpty() ? null : String.join("; ", messages);
    }

    public static boolean isValidEmail(String email) {
        // Mirror .NET MailAddress basic check
        if (email == null || email.isBlank()) return false;
        if (email.contains(" ")) return false;
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) return false;
        String domain = email.substring(at + 1);
        if (!domain.contains(".")) return false;
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) return false;
        String local = email.substring(0, at);
        if (local.isEmpty() || local.startsWith(".") || local.endsWith(".")) return false;
        return true;
    }

    public static String normalizeHeader(String value) {
        String lower = value.trim().toLowerCase();
        String replaced = lower.replaceAll("[^a-z0-9]+", "_");
        return replaced.replaceAll("^_+|_+$", "");
    }

    public static String normalizePhone(String value) {
        String phone = value.trim();
        var m = EXCEL_PHONE.matcher(phone);
        if (m.matches()) phone = m.group(1).trim();
        else if (phone.startsWith("'")) phone = phone.substring(1).trim();
        if (SCIENTIFIC.matcher(phone).matches()) {
            throw new IllegalStateException("Phone number is in scientific notation and cannot be recovered safely.");
        }
        return phone;
    }
}
