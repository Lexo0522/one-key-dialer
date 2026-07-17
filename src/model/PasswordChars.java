package model;

import java.util.Arrays;

/**
 * Char-array helpers for password empty/equality checks without allocating {@link String}.
 */
public final class PasswordChars {
    private PasswordChars() {
    }

    /** True if null, empty, or only whitespace / NUL. */
    public static boolean isBlank(char[] chars) {
        if (chars == null || chars.length == 0) return true;
        for (char c : chars) {
            if (!Character.isWhitespace(c) && c != '\0') return false;
        }
        return true;
    }

    /** Constant-time-ish content equality (same length and chars). */
    public static boolean equals(char[] a, char[] b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Arrays.equals(a, b);
    }

    /**
     * Defensive copy without leading/trailing whitespace or trailing NULs.
     * Caller must {@link Arrays#fill(char[], char)} the result when done.
     */
    public static char[] trimmedCopy(char[] chars) {
        if (chars == null || chars.length == 0) return new char[0];
        int start = 0;
        while (start < chars.length && (Character.isWhitespace(chars[start]) || chars[start] == '\0')) {
            start++;
        }
        int end = chars.length;
        while (end > start && (Character.isWhitespace(chars[end - 1]) || chars[end - 1] == '\0')) {
            end--;
        }
        if (start >= end) return new char[0];
        return Arrays.copyOfRange(chars, start, end);
    }

    public static void clear(char[] chars) {
        if (chars != null && chars.length > 0) {
            Arrays.fill(chars, '\0');
        }
    }
}
