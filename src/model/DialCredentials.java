package model;

import java.util.Arrays;

/**
 * Short-lived dial credentials captured on the EDT right before a dial attempt.
 * Password lives as {@code char[]}; {@link #clear()} must be called when the dial
 * finishes, fails, or is cancelled. Nothing else may cache it.
 * <p>
 * {@link #passwordAsString()} exists only for the {@code rasdial} argv boundary —
 * that String may be visible in local process listings until the child exits.
 */
public final class DialCredentials {
    private final String username;
    private char[] password;

    public DialCredentials(String username, char[] password) {
        this.username = username != null ? username.trim() : "";
        this.password = password != null ? Arrays.copyOf(password, password.length) : new char[0];
    }

    public String username() {
        return username;
    }

    /** Defensive copy for the RAS boundary; caller must clear the copy. */
    public char[] copyPassword() {
        return Arrays.copyOf(password, password.length);
    }

    /** Plain password for rasdial argv only. */
    public String passwordAsString() {
        return new String(password).trim();
    }

    public boolean hasUsername() {
        return !username.isEmpty();
    }

    public boolean hasPassword() {
        return !PasswordChars.isBlank(password);
    }

    /** Content comparison without exposing the stored array. */
    public boolean passwordEquals(char[] other) {
        char[] trimmed = PasswordChars.trimmedCopy(password);
        char[] trimmedOther = PasswordChars.trimmedCopy(other);
        try {
            return PasswordChars.equals(trimmed, trimmedOther);
        } finally {
            PasswordChars.clear(trimmed);
            PasswordChars.clear(trimmedOther);
        }
    }

    /** Zero out the in-process password. The instance must not be reused afterwards. */
    public void clear() {
        PasswordChars.clear(password);
        password = new char[0];
    }
}
