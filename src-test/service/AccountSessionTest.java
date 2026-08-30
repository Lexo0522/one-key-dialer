package service;

import model.AccountInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.AccountStore;
import storage.SecretProtector;
import storage.StorageException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountSessionTest {
    @TempDir
    Path dir;

    private static final class FakeProtector implements SecretProtector {
        boolean available = true;

        @Override
        public String protect(char[] plain) {
            if (!available) return null;
            return "FAKE1:" + java.util.Base64.getEncoder().encodeToString(
                new String(plain).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public char[] unprotect(String blob) {
            // Unavailable DPAPI also cannot restore previously protected blobs.
            if (!available) return null;
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
    void firstLoadCreatesDefaultAccount() {
        AccountSession session = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        session.load();
        assertEquals(1, session.accounts().size());
        assertEquals("默认账号", session.currentOrNull().name);
        assertEquals(0, session.currentIndex());
    }

    @Test
    void saveAndReloadRestoresAccountsAndPasswords() {
        AccountSession session = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        session.load();
        session.accounts().clear();
        session.accounts().add(new AccountInfo("dorm", "2023001", "pw-one", "a"));
        session.accounts().add(new AccountInfo("lib", "2023002", "pw-two", "b"));
        session.setCurrentIndex(1);
        assertTrue(session.save());

        AccountSession reloaded = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        reloaded.load();
        assertEquals(2, reloaded.accounts().size());
        assertTrue(reloaded.accounts().get(0).passwordEquals("pw-one".toCharArray()));
        assertTrue(reloaded.accounts().get(1).passwordEquals("pw-two".toCharArray()));
    }

    @Test
    void protectorUnavailableLeavesPasswordEmptyAfterReload() {
        FakeProtector protector = new FakeProtector();
        AccountSession session = new AccountSession(new AccountStore(file(), protector), null);
        session.load();
        session.accounts().clear();
        session.accounts().add(new AccountInfo("n", "u", "", ""));
        session.accounts().get(0).setPasswordChars("secret".toCharArray());
        assertTrue(session.save());

        protector.available = false;
        AccountSession reloaded = new AccountSession(new AccountStore(file(), protector), null);
        reloaded.load();
        assertTrue(reloaded.accounts().get(0).isPasswordEmpty(),
            "dial must require re-entry when DPAPI is unavailable");
    }

    @Test
    void corruptedStorageFallsBackToDefaultWithoutOverwriting() throws Exception {
        AccountSession session = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        session.load();
        session.accounts().clear();
        session.accounts().add(new AccountInfo("kept", "u", "p", ""));
        assertTrue(session.save());

        Files.write(file().toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        List<String> errors = new ArrayList<>();
        AccountSession failing = new AccountSession(new AccountStore(file(), new FakeProtector()),
            new AccountSession.Logger() {
                @Override public void info(String m) { }
                @Override public void error(String m) { errors.add(m); }
            });
        failing.load();
        assertTrue(errors.get(0).contains("加载账号失败"));
        assertEquals(1, failing.accounts().size());
        assertEquals("默认账号", failing.currentOrNull().name);
        // Original bytes untouched.
        assertTrue(new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8)
            .startsWith("{broken"));
    }

    @Test
    void pullFromUiClearsInputArrayAndMarksDirty() {
        AccountSession session = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        session.load();
        char[] pw = "newpass".toCharArray();
        assertTrue(session.pullFromUi("n2", "u2", pw));
        assertTrue(session.isDirty());
        assertEquals("n2", session.currentOrNull().name);
        assertTrue(session.currentOrNull().passwordEquals("newpass".toCharArray()));
        assertTrue(model.PasswordChars.isBlank(pw), "input array must be zeroed");
    }

    @Test
    void clampIndexAfterListChange() {
        AccountSession session = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        session.load();
        session.accounts().clear();
        session.accounts().add(new AccountInfo("a", "", "", ""));
        session.accounts().add(new AccountInfo("b", "", "", ""));
        session.setCurrentIndex(1);
        session.accounts().remove(1);
        session.clampIndexAfterListChange();
        assertEquals(0, session.currentIndex());
    }

    @Test
    void clearPasswordsInMemoryZeroesEverything() {
        AccountSession session = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        session.load();
        session.accounts().clear();
        session.accounts().add(new AccountInfo("a", "u", "", ""));
        session.accounts().get(0).setPasswordChars("pw".toCharArray());
        session.clearPasswordsInMemory();
        assertTrue(session.accounts().get(0).isPasswordEmpty());
    }

    @Test
    void applyCurrentToUiClearsItsCopy() {
        AccountSession session = new AccountSession(new AccountStore(file(), new FakeProtector()), null);
        session.load();
        session.accounts().clear();
        session.accounts().add(new AccountInfo("n", "u", "keep", ""));
        final boolean[] matchedInside = {false};
        final char[][] holder = new char[1][];
        session.applyCurrentToUi(x -> { }, y -> { }, chars -> {
            matchedInside[0] = Arrays.equals("keep".toCharArray(), chars);
            holder[0] = chars;
        });
        // Inside the consumer the password was handed over in the clear.
        assertTrue(matchedInside[0]);
        // The copy handed to the UI is cleared after the consumer returns.
        assertTrue(model.PasswordChars.isBlank(holder[0]));
    }
}
