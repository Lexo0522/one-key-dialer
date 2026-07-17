package model;

import java.util.Arrays;

/**
 * Immutable-enough dial credentials captured on the EDT before background work.
 * <p>
 * In-process storage is {@code char[]}. {@link #passwordAsString()} exists only for
 * {@code ProcessBuilder} rasdial argv — that String (and the child process image) may
 * be visible in local process listings until rasdial exits. This is a Windows boundary,
 * not something Java can fully eliminate.
 */
public final class DialSnapshot {
    public final String connectionName;
    public final String username;
    private final char[] password;

    public DialSnapshot(String connectionName, String username, char[] password) {
        this.connectionName = connectionName != null ? connectionName : "";
        this.username = username != null ? username.trim() : "";
        this.password = password != null ? Arrays.copyOf(password, password.length) : new char[0];
    }

    /**
     * Plain password for rasdial argv only. Prefer {@link #copyPasswordChars()} in-process.
     * Visible to process-list tools while the child runs.
     */
    public String passwordAsString() {
        return new String(password).trim();
    }

    /** Defensive copy for credential cache. Caller must clear. */
    public char[] copyPasswordChars() {
        return Arrays.copyOf(password, password.length);
    }

    public void clear() {
        Arrays.fill(password, '\0');
    }
}
