package storage;

import model.AccountInfo;
import model.PasswordChars;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JSON account list. Secrets are protected by the injected {@link SecretProtector};
 * when protection is unavailable the secret is simply not written, never stored in
 * the clear. Legacy INI files next to this document are ignored.
 */
public final class AccountStore {
    public static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final SecretProtector protector;

    public AccountStore(File file, SecretProtector protector) {
        this.file = file.toPath();
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    /** Load outcome: account rows plus how many protected secrets could not be restored. */
    public static final class LoadResult {
        public final List<AccountInfo> accounts;
        /** Corrupted blobs, or blobs when the protector could not restore them. */
        public final int droppedSecrets;

        LoadResult(List<AccountInfo> accounts, int droppedSecrets) {
            this.accounts = accounts;
            this.droppedSecrets = droppedSecrets;
        }
    }

    /** @return stored accounts, or null when the file does not exist yet. */
    public LoadResult load() throws StorageException {
        Document doc = JsonFiles.read(file, SCHEMA_VERSION, Document.class);
        if (doc == null) {
            return null;
        }
        List<AccountInfo> out = new ArrayList<>();
        int dropped = 0;
        if (doc.data == null) return new LoadResult(out, 0);
        for (AccountRecord r : doc.data) {
            if (r == null) continue;
            AccountInfo a = new AccountInfo(nullToEmpty(r.name), nullToEmpty(r.username),
                "", nullToEmpty(r.remark));
            char[] plain = protector.unprotect(nullToEmpty(r.passwordProtected));
            if (plain == null) {
                // Corrupted blob or protector unavailable: fail closed — no secret in memory.
                dropped++;
            } else {
                try {
                    a.setPasswordChars(plain);
                } finally {
                    PasswordChars.clear(plain);
                }
            }
            out.add(a);
        }
        return new LoadResult(out, dropped);
    }

    public void save(List<AccountInfo> accounts) throws IOException {
        List<AccountRecord> rows = new ArrayList<>(accounts.size());
        for (AccountInfo a : accounts) {
            if (a == null) continue;
            char[] pw = a.copyPasswordChars();
            String blob;
            try {
                blob = pw.length == 0 ? "" : protector.protect(pw);
            } finally {
                PasswordChars.clear(pw);
            }
            rows.add(new AccountRecord(a.name, a.username, blob != null ? blob : "", a.remark));
        }
        JsonFiles.write(file, SCHEMA_VERSION, new Document(rows));
    }

    public File getFile() {
        return file.toFile();
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static final class AccountRecord {
        // non-final: populated reflectively by Gson on load
        private String name;
        private String username;
        private String passwordProtected;
        private String remark;

        AccountRecord(String name, String username, String passwordProtected, String remark) {
            this.name = name;
            this.username = username;
            this.passwordProtected = passwordProtected;
            this.remark = remark;
        }
    }

    private static final class Document {
        private List<AccountRecord> data;

        Document() {
        }

        Document(List<AccountRecord> data) {
            this.data = data;
        }
    }
}
