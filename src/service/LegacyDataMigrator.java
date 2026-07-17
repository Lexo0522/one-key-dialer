package service;

import util.AppPaths;

import java.io.File;
import java.nio.file.Files;

/**
 * One-shot copy of legacy cwd config files into {@link AppPaths} data dir.
 */
public final class LegacyDataMigrator {
    private LegacyDataMigrator() {
    }

    public static void migrateIfNeeded(Class<?> appClass, String... fileNames) {
        if (appClass == null || fileNames == null || fileNames.length == 0) return;
        try {
            File dataDir = AppPaths.getDataDir(appClass);
            File cwd = new File(System.getProperty("user.dir")).getAbsoluteFile();
            if (dataDir.getAbsolutePath().equals(cwd.getAbsolutePath())) return;
            for (String n : fileNames) {
                if (n == null || n.isEmpty()) continue;
                File src = new File(cwd, n);
                File dst = new File(dataDir, n);
                if (src.exists() && !dst.exists()) {
                    Files.copy(src.toPath(), dst.toPath());
                }
            }
        } catch (Exception ignored) {
            // best-effort migration
        }
    }
}
