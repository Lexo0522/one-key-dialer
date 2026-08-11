package service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateDownloadServiceTest {
    private static final String ABC_SHA256 =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void readsExactAssetHashFromStrictManifest() throws Exception {
        String manifest = ABC_SHA256 + "  PPoEDialer-1.1.0-windows.zip\n"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  PPoEDialer-1.1.0-windows.msi\n";

        assertEquals(ABC_SHA256,
            UpdateDownloadService.expectedSha256(manifest, "PPoEDialer-1.1.0-windows.zip"));
    }

    @Test
    void rejectsMissingDuplicateAndMalformedManifestEntries() {
        String valid = ABC_SHA256 + "  PPoEDialer.zip\n";
        assertThrows(IOException.class,
            () -> UpdateDownloadService.expectedSha256(valid, "PPoEDialer.msi"));
        assertThrows(IOException.class,
            () -> UpdateDownloadService.expectedSha256(valid + valid, "PPoEDialer.zip"));
        assertThrows(IOException.class,
            () -> UpdateDownloadService.expectedSha256(ABC_SHA256 + " PPoEDialer.zip", "PPoEDialer.zip"));
    }

    @Test
    void calculatesLowercaseSha256() throws Exception {
        Path file = Files.createTempFile("ppoe-update-checksum-", ".txt");
        try {
            Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
            assertEquals(ABC_SHA256, UpdateDownloadService.sha256(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
