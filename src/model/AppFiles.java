package model;

/**
 * On-disk file names (under {@link util.AppPaths} data dir).
 */
public final class AppFiles {
    public static final String SETTINGS = "pppoe_settings.ini";
    public static final String LOG = "pppoe_log.txt";
    public static final String ACCOUNTS = "pppoe_accounts.ini";
    public static final String HISTORY = "pppoe_history.csv";
    public static final String SETTINGS_BACKUP_SUFFIX = ".bak";
    public static final String RAS_CONNECTION = "pppoe_native_java";

    private AppFiles() {
    }
}
