package org.trigger.opspilot.auth;

import java.nio.charset.StandardCharsets;

/** Existing BCrypt boundary: never let two different inputs alias through truncation/replacement. */
final class PasswordPolicy {
    private PasswordPolicy() { }
    static boolean validBcryptInput(String value) {
        if (value == null || value.isBlank() || value.length() > 72 || value.indexOf('\0') >= 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
