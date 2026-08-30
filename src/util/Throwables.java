package util;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Compact throwable formatting for the in-app log (no external logging backend). */
public final class Throwables {
    private static final int MAX_CHARS = 4000;

    private Throwables() {
    }

    /** Full stack trace, truncated for the log pane; never null. */
    public static String stackTrace(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter(512);
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        if (s.length() > MAX_CHARS) {
            return s.substring(0, MAX_CHARS) + "\n…(截断)";
        }
        return s;
    }
}
