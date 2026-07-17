package util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves writable application data directory.
 * Prefer: jar/exe directory (if writable) → user.dir (dev) → %APPDATA%\PPoEDialer.
 */
public final class AppPaths {
    private static final String APP_DATA_FOLDER = "PPoEDialer";
    private static volatile File dataDir;

    private AppPaths() {
    }

    public static synchronized File getDataDir(Class<?> appClass) {
        if (dataDir != null) return dataDir;

        File besideApp = resolveBesideApp(appClass);
        // When running from exploded classes (…/bin), keep data in project/user.dir instead
        if (besideApp != null && !"bin".equalsIgnoreCase(besideApp.getName()) && isWritableDir(besideApp)) {
            dataDir = besideApp;
            return dataDir;
        }

        File userDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        if (isWritableDir(userDir)) {
            dataDir = userDir;
            return dataDir;
        }

        String appData = System.getenv("APPDATA");
        File fallback = appData != null
            ? new File(appData, APP_DATA_FOLDER)
            : new File(System.getProperty("user.home"), APP_DATA_FOLDER);
        if (!fallback.exists()) {
            //noinspection ResultOfMethodCallIgnored
            fallback.mkdirs();
        }
        dataDir = fallback;
        return dataDir;
    }

    public static File file(Class<?> appClass, String name) {
        return new File(getDataDir(appClass), name);
    }

    public static File masterKeyFile(Class<?> appClass) {
        // Always keep master key under AppData when possible (not next to shared install)
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            File dir = new File(appData, APP_DATA_FOLDER);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            return new File(dir, "master.key");
        }
        return file(appClass, "master.key");
    }

    private static File resolveBesideApp(Class<?> appClass) {
        try {
            String processCmd = ProcessHandle.current().info().command().orElse("");
            if (!processCmd.isEmpty()) {
                File processFile = new File(processCmd).getAbsoluteFile();
                String name = processFile.getName().toLowerCase();
                // jpackage image: PPoEDialer.exe ; ignore java.exe/javaw.exe parents (JDK bin)
                if (name.endsWith(".exe") && !name.equals("java.exe") && !name.equals("javaw.exe")) {
                    File parent = processFile.getParentFile();
                    if (parent != null && parent.exists()) return parent;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            File codeSource = new File(appClass.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getAbsoluteFile();
            if (codeSource.isFile() && codeSource.getName().toLowerCase().endsWith(".jar")) {
                File parent = codeSource.getParentFile();
                if (parent != null) return parent;
            }
            if (codeSource.isDirectory()) return codeSource;
        } catch (Exception ignored) {
        }

        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    private static boolean isWritableDir(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            Path probe = Files.createTempFile(dir.toPath(), ".write_probe_", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
