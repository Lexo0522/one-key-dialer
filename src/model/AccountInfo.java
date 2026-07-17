package model;

import java.util.Arrays;

/**
 * Account row. Password stored as {@code char[]} and cleared when replaced/wiped.
 * Prefer {@link #setPasswordChars(char[])} / {@link #copyPasswordChars()} in-process;
 * {@link #getPassword()} still exists for crypto/CSV/Swing prefill.
 */
public class AccountInfo {
    public String name;
    public String username;
    public String remark;
    private char[] password;

    public AccountInfo(String name, String username, String password, String remark) {
        this.name = name;
        this.username = username;
        setPassword(password);
        this.remark = remark != null ? remark : "";
    }

    public AccountInfo(String name, String username, String password) {
        this(name, username, password, "");
    }

    /** Plain password for UI/crypto (caller should not log). */
    public String getPassword() {
        return password == null || password.length == 0 ? "" : new String(password);
    }

    public void setPassword(String value) {
        clearPassword();
        if (value == null || value.isEmpty()) {
            password = new char[0];
        } else {
            password = value.toCharArray();
        }
    }

    public void setPasswordChars(char[] value) {
        clearPassword();
        if (value == null || value.length == 0) {
            password = new char[0];
        } else {
            password = Arrays.copyOf(value, value.length);
        }
    }

    /** Defensive copy for dial snapshot / short-lived use. Caller must clear. */
    public char[] copyPasswordChars() {
        if (password == null || password.length == 0) return new char[0];
        return Arrays.copyOf(password, password.length);
    }

    public boolean isPasswordEmpty() {
        return PasswordChars.isBlank(password);
    }

    public boolean passwordEquals(String other) {
        char[] otherChars = other != null ? other.toCharArray() : new char[0];
        try {
            return passwordEquals(otherChars);
        } finally {
            PasswordChars.clear(otherChars);
        }
    }

    /** Compare without allocating a String for the stored password. */
    public boolean passwordEquals(char[] other) {
        char[] trimmedOther = PasswordChars.trimmedCopy(other);
        char[] trimmedMine = PasswordChars.trimmedCopy(password);
        try {
            return PasswordChars.equals(trimmedMine, trimmedOther);
        } finally {
            PasswordChars.clear(trimmedOther);
            PasswordChars.clear(trimmedMine);
        }
    }

    public void clearPassword() {
        if (password != null && password.length > 0) {
            Arrays.fill(password, '\0');
        }
        password = new char[0];
    }

    @Override
    public String toString() {
        return name + " (" + username + ")";
    }
}
