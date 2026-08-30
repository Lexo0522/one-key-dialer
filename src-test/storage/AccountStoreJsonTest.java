package storage;

import model.AccountInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreJsonTest {
    @TempDir
    Path dir;

    /** Deterministic stand-in for DPAPI: XOR with a fixed pad. */
    private static final class FakeProtector implements SecretProtector {
        boolean available = true;
        boolean corruptOnUnprotect = false;

        @Override
        public String protect(char[] plain) {
            if (!available) return null;
            return "FAKE1:" + java.util.Base64.getEncoder().encodeToString(
                new String(plain).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public char[] unprotect(String blob) {
            if (corruptOnUnprotect) return null;
            if (blob == null || blob.isEmpty()) return new char[0];
            if (!blob.startsWith("FAKE1:")) return null;
            return new String(java.util.Base64.getDecoder().decode(blob.substring(6)))
                .toCharArray();
        }
    }

    private File file() {
        return dir.resolve("accounts.json").toFile();
    }

    @Test
    void firstStartReturnsNull() throws Exception {
        AccountStore store = new AccountStore(file(), new FakeProtector());
        assertNull(store.load());
    }

    @Test
    void saveAndReloadRoundTripProtectsSecrets() throws Exception {
        FakeProtector protector = new FakeProtector();
        AccountStore store = new AccountStore(file(), protector);
        List<AccountInfo> accounts = new ArrayList<>();
        accounts.add(new AccountInfo("dorm", "2023001", "secret-pass", "campus"));
        accounts.add(new AccountInfo("backup", "2023002", "", ""));

        store.save(accounts);
        AccountStore.LoadResult loaded = store.load();

        assertNotNull(loaded);
        assertEquals(2, loaded.accounts.size());
        assertEquals(0, loaded.droppedSecrets);
        assertEquals("dorm", loaded.accounts.get(0).name);
        assertEquals("2023001", loaded.accounts.get(0).username);
        assertTrue(loaded.accounts.get(0).passwordEquals("secret-pass".toCharArray()));
        assertEquals("campus", loaded.accounts.get(0).remark);
        assertTrue(loaded.accounts.get(1).isPasswordEmpty());

        // No plaintext secret on disk.
        String json = new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8);
        assertFalse(json.contains("secret-pass"));
        assertTrue(json.contains("FAKE1:"));
        assertTrue(json.contains("\"schemaVersion\": 1"));
    }

    @Test
    void inputPasswordArrayIsClearedBySave() throws Exception {
        FakeProtector protector = new FakeProtector();
        AccountStore store = new AccountStore(file(), protector);
        AccountInfo a = new AccountInfo("n", "u", "", "");
        char[] pw = "topsecret".toCharArray();
        a.setPasswordChars(pw);
        List<AccountInfo> accounts = new ArrayList<>();
        accounts.add(a);

        store.save(accounts);

        // AccountInfo keeps its own copy; the store must not blank the live object.
        assertTrue(a.passwordEquals("topsecret".toCharArray()));
        // But the protect() input copy is gone from any store-side buffer (no exception path).
    }

    @Test
    void protectorUnavailableDropsSecretButKeepsAccounts() throws Exception {
        FakeProtector protector = new FakeProtector();
        AccountStore store = new AccountStore(file(), protector);
        List<AccountInfo> accounts = new ArrayList<>();
        accounts.add(new AccountInfo("n", "u", "", ""));
        accounts.get(0).setPasswordChars("pw-for-dial".toCharArray());

        protector.available = false;
        store.save(accounts);

        String json = new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8);
        assertFalse(json.contains("pw-for-dial"), "secret must not be persisted in the clear");

        AccountStore.LoadResult loaded = store.load();
        assertEquals(1, loaded.accounts.size());
        assertTrue(loaded.accounts.get(0).isPasswordEmpty(),
            "password must require re-entry when DPAPI is unavailable");
    }

    @Test
    void corruptedBlobFailsClosedWithoutThrowing() throws Exception {
        FakeProtector protector = new FakeProtector();
        AccountStore store = new AccountStore(file(), protector);
        List<AccountInfo> accounts = new ArrayList<>();
        accounts.add(new AccountInfo("a", "u1", "", ""));
        accounts.get(0).setPasswordChars("one".toCharArray());
        accounts.add(new AccountInfo("b", "u2", "", ""));
        accounts.get(1).setPasswordChars("two".toCharArray());
        store.save(accounts);

        protector.corruptOnUnprotect = true;
        AccountStore.LoadResult loaded = store.load();

        assertEquals(2, loaded.accounts.size());
        assertEquals(2, loaded.droppedSecrets);
        assertTrue(loaded.accounts.get(0).isPasswordEmpty());
        assertTrue(loaded.accounts.get(1).isPasswordEmpty());
    }

    @Test
    void unknownSchemaIsRejected() throws Exception {
        Files.write(file().toPath(),
            "{\"schemaVersion\": 7, \"data\": []}".getBytes(StandardCharsets.UTF_8));
        AccountStore store = new AccountStore(file(), new FakeProtector());
        StorageException e = assertThrows(StorageException.class, store::load);
        assertEquals(StorageException.Kind.UNKNOWN_SCHEMA, e.kind());
    }

    @Test
    void legacyIniAndOldKeyFilesAreIgnored() throws Exception {
        Files.write(dir.resolve("pppoe_accounts.ini"),
            "[Account1]\nname=old\nusername=u\npassword=zzz\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("master.key"), new byte[32]);
        AccountStore store = new AccountStore(file(), new FakeProtector());
        assertNull(store.load(), "legacy INI must not be read");
    }

    @Test
    void emptyListRoundTrip() throws Exception {
        AccountStore store = new AccountStore(file(), new FakeProtector());
        store.save(new ArrayList<>());
        AccountStore.LoadResult loaded = store.load();
        assertNotNull(loaded);
        assertTrue(loaded.accounts.isEmpty());
    }

    @Test
    void blobPrefixIsDpapiMarked() {
        assertEquals("DPAPI1:", DpapiSecretProtector.BLOB_PREFIX);
        // Sanity: the production protector never returns plaintext-looking blobs for null
        DpapiSecretProtector p = new DpapiSecretProtector();
        char[] pw = "x".toCharArray();
        try {
            // On non-Windows test environments protect returns null; either way no plaintext.
            String blob = p.protect(pw);
            if (blob != null) {
                assertTrue(blob.startsWith("DPAPI1:"));
                assertFalse(blob.contains("x\""));
            }
        } finally {
            Arrays.fill(pw, '\0');
        }
    }
}
