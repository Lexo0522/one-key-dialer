package util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePermissionsTest {

    @Test
    void restrictKeepsOwnerAccessAndNeverThrows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("accounts.json");
        Files.write(file, "x".getBytes("UTF-8"));

        assertDoesNotThrow(() -> FilePermissions.restrictToOwner(file.toFile()));

        // owner must keep access after the restriction
        assertDoesNotThrow(() -> Files.write(file, "y".getBytes("UTF-8")));
        assertEquals("y", Files.readString(file));

        AclFileAttributeView view =
            Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view != null) {
            // NTFS: the rewritten ACL must contain at least the owner grant
            assertTrue(view.getAcl().size() >= 1, "acl must contain the owner grant");
        }
    }

    @Test
    void nullAndMissingFilesAreIgnored() {
        assertDoesNotThrow(() -> FilePermissions.restrictToOwner(null));
        assertDoesNotThrow(() ->
            FilePermissions.restrictToOwner(new java.io.File("Z:/definitely/missing.bin")));
    }
}
