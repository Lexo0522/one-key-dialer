package model;

/**
 * Single source of truth for app version strings.
 * Keep in sync with {@code pom.xml} {@code <version>} and jpackage {@code --app-version}
 * (same numeric value without the leading {@code v}).
 */
public final class AppVersion {
    /** Display form used in window title / logs, e.g. {@code v1.1.0}. */
    public static final String DISPLAY = "v1.1.0";
    /** Numeric form for Maven / jpackage / User-Agent, e.g. {@code 1.1.0}. */
    public static final String NUMERIC = "1.1.0";
    /** HTTP User-Agent for outbound probes. */
    public static final String USER_AGENT = "PPoEDialer/" + NUMERIC;
    /** GitHub repo for update checks (owner/name). */
    public static final String GITHUB_REPO = "Lexo0522/one-key-dialer";
    public static final String GITHUB_URL = "https://github.com/" + GITHUB_REPO;
    public static final String RELEASES_API =
        "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

    private AppVersion() {
    }

    /** Strip optional leading {@code v}/{@code V} for numeric compare helpers. */
    public static String stripV(String version) {
        if (version == null) return "";
        String v = version.trim();
        if (v.length() >= 2 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) {
            return v.substring(1);
        }
        return v;
    }

    /**
     * Compare dotted numeric versions (e.g. 1.0.0 vs 1.1.0).
     * @return negative if a&lt;b, 0 if equal, positive if a&gt;b
     */
    public static int compareNumeric(String a, String b) {
        String[] pa = stripV(a).split("\\.");
        String[] pb = stripV(b).split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int ia = i < pa.length ? parsePart(pa[i]) : 0;
            int ib = i < pb.length ? parsePart(pb[i]) : 0;
            if (ia != ib) return Integer.compare(ia, ib);
        }
        return 0;
    }

    private static int parsePart(String part) {
        if (part == null || part.isEmpty()) return 0;
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
