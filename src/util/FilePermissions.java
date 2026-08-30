package util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort owner-only file permissions. POSIX {@code setReadable} flags are
 * ignored on NTFS, so on Windows the ACL is rewritten: the owner keeps full
 * control and SYSTEM / Administrators keep access for maintenance; every other
 * principal (including inherited entries) is dropped. The NIO ACL view is tried
 * first, {@code icacls} (using locale-independent SIDs) as the fallback.
 */
public final class FilePermissions {
    /** Well-known SID: Administrators group (locale-independent). */
    private static final String SID_ADMINISTRATORS = "*S-1-5-32-544";
    /** Well-known SID: OWNER RIGHTS — resolves to the file owner at access time. */
    private static final String SID_OWNER_RIGHTS = "*S-1-3-4";

    private FilePermissions() {
    }

    public static void restrictToOwner(File file) {
        if (file == null || !file.isFile()) return;
        try {
            if (isWindows()) {
                restrictWindowsAcl(file.toPath());
            } else {
                restrictPosix(file);
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static void restrictPosix(File file) {
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    private static void restrictWindowsAcl(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view != null) {
            try {
                view.setAcl(buildAcl(path, view));
                return;
            } catch (Exception aclApiFailed) {
                // fall through to icacls
            }
        }
        runIcacls(path);
    }

    private static List<AclEntry> buildAcl(Path path, AclFileAttributeView view)
        throws IOException {
        UserPrincipal owner = view.getOwner();
        UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
        List<AclEntry> acl = new ArrayList<>();
        acl.add(fullControl(owner));
        try {
            acl.add(fullControl(lookup.lookupPrincipalByName("SYSTEM")));
        } catch (IOException ignored) {
        }
        try {
            acl.add(fullControl(lookup.lookupPrincipalByGroupName("Administrators")));
        } catch (IOException ignored) {
        }
        return acl;
    }

    private static AclEntry fullControl(UserPrincipal principal) {
        return AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    }

    private static void runIcacls(Path path) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(
                "icacls", path.toString(),
                "/inheritance:r",
                "/grant:r", SID_OWNER_RIGHTS + ":F",
                "/grant:r", "SYSTEM:F",
                "/grant:r", SID_ADMINISTRATORS + ":F")
                .redirectErrorStream(true)
                .start();
        } catch (IOException e) {
            throw e;
        }
        boolean finished;
        try {
            finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("icacls interrupted");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("icacls timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("icacls failed with exit code " + process.exitValue());
        }
    }
}
