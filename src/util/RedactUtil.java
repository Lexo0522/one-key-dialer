package util;

/**
 * Log / history / tooltip redaction helpers.
 */
public final class RedactUtil {
    private RedactUtil() {
    }

    /**
     * Mask a username/account for display: keep last {@code keepTail} chars.
     * Empty → empty; short → all stars.
     */
    public static String maskAccount(String username, int keepTail) {
        if (username == null || username.isEmpty()) return "";
        int keep = Math.max(0, keepTail);
        if (username.length() <= keep) {
            return stars(username.length());
        }
        int head = username.length() - keep;
        return stars(head) + username.substring(head);
    }

    public static String maskAccount(String username) {
        return maskAccount(username, 2);
    }

    /** Always full mask for secrets. */
    public static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) return "";
        return stars(Math.min(8, secret.length()));
    }

    /**
     * Best-effort scrub of password-like substrings in free-form log lines.
     * Does not claim cryptographic safety — reduces accidental paste of secrets.
     */
    public static String scrubLogLine(String message) {
        if (message == null || message.isEmpty()) return message;
        // password=... / pwd=... / pass=...
        String s = message.replaceAll("(?i)(password|passwd|pwd|pass)\\s*[=:]\\s*\\S+", "$1=***");
        // rasdial conn user PASS patterns rarely appear as plain text in our logs
        return s;
    }

    private static String stars(int n) {
        if (n <= 0) return "";
        char[] c = new char[n];
        java.util.Arrays.fill(c, '*');
        return new String(c);
    }
}
