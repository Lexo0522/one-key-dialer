package util;

import java.io.File;

/** Best-effort owner-only file permissions (Windows-friendly). */
public final class FilePermissions {
    private FilePermissions() {
    }

    public static void restrictToOwner(File file) {
        if (file == null) return;
        try {
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            file.setExecutable(false, false);
        } catch (Exception ignored) {
        }
    }
}
