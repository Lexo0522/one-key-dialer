package model;

/**
 * On-disk file names (under {@link util.AppPaths} data dir).
 * JSON documents carry a {@code schemaVersion}; legacy INI/CSV files in the same
 * directory are never read by this version.
 */
public final class AppFiles {
    public static final String SETTINGS = "settings.json";
    public static final String LOG = "pppoe_log.txt";
    public static final String ACCOUNTS = "accounts.json";
    public static final String HISTORY = "history.json";
    public static final String RAS_CONNECTION = "pppoe_native_java";

    private AppFiles() {
    }
}
