package service;

import model.AccountInfo;
import model.PasswordChars;
import storage.AccountStore;
import util.FilePermissions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Multi-account list, selection index, dirty flag, and store I/O.
 * Does not own the dial credential cache (that stays on {@link DialOrchestrator}).
 */
public final class AccountSession {
    public interface Logger {
        void info(String message);

        void error(String message);
    }

    private final List<AccountInfo> accounts = new ArrayList<>();
    private final AccountStore store;
    private final Logger logger;
    private volatile int currentIndex;
    private volatile boolean dirty;

    public AccountSession(AccountStore store, Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = logger != null ? logger : new Logger() {
            @Override public void info(String message) { }
            @Override public void error(String message) { }
        };
    }

    public List<AccountInfo> accounts() {
        return accounts;
    }

    public int currentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int index) {
        if (accounts.isEmpty()) {
            currentIndex = 0;
            return;
        }
        if (index < 0 || index >= accounts.size()) {
            currentIndex = 0;
        } else {
            currentIndex = index;
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean value) {
        dirty = value;
    }

    public AccountInfo currentOrNull() {
        if (currentIndex < 0 || currentIndex >= accounts.size()) return null;
        return accounts.get(currentIndex);
    }

    public String currentName() {
        AccountInfo a = currentOrNull();
        return a != null ? a.name : "未命名账号";
    }

    public void load() {
        try {
            store.load(accounts);
            if (store.consumeMigrationFlag()) {
                logger.info("检测到旧版账号格式，正在迁移加密...");
                save();
            }
        } catch (IOException e) {
            logger.error("加载账号失败（未覆盖原文件）: " + e.getMessage());
            if (accounts.isEmpty()) {
                accounts.add(new AccountInfo("默认账号", "", "", ""));
            }
        }
        ensureDefaultAccount();
        setCurrentIndex(currentIndex);
    }

    public void ensureDefaultAccount() {
        if (accounts.isEmpty()) {
            accounts.add(new AccountInfo("默认账号", "", "", ""));
            if (!store.getFile().exists()) {
                save();
            }
        }
    }

    public void save() {
        try {
            store.save(accounts);
            FilePermissions.restrictToOwner(store.getFile());
        } catch (IOException e) {
            logger.error("保存账号失败");
        }
    }

    /**
     * Apply current account fields into UI.
     * Password is handed as a defensive {@code char[]} copy; this method clears it after
     * the consumer returns (consumer should copy if it needs longer retention).
     */
    public void applyCurrentToUi(Consumer<String> name, Consumer<String> username,
                                 Consumer<char[]> passwordChars) {
        AccountInfo a = currentOrNull();
        if (a == null) return;
        if (name != null) name.accept(a.name != null ? a.name : "");
        if (username != null) username.accept(a.username != null ? a.username : "");
        if (passwordChars != null) {
            char[] copy = a.copyPasswordChars();
            try {
                passwordChars.accept(copy);
            } finally {
                PasswordChars.clear(copy);
            }
        }
    }

    /**
     * Pull UI values into the current account. Password array is cleared by this method.
     *
     * @return true if any field changed
     */
    public boolean pullFromUi(String name, String username, char[] passwordChars) {
        AccountInfo a = currentOrNull();
        if (a == null) {
            PasswordChars.clear(passwordChars);
            return false;
        }
        String newName = name != null ? name.trim() : "";
        String newUsername = username != null ? username.trim() : "";
        char[] trimmed = PasswordChars.trimmedCopy(passwordChars);
        try {
            boolean changed = !Objects.equals(a.name, newName)
                || !Objects.equals(a.username, newUsername)
                || !a.passwordEquals(trimmed);
            if (!changed) return false;
            a.name = newName;
            a.username = newUsername;
            a.setPasswordChars(trimmed);
            dirty = true;
            return true;
        } finally {
            PasswordChars.clear(trimmed);
            PasswordChars.clear(passwordChars);
        }
    }

    public void saveCurrentIfNeeded(String name, String username, char[] passwordChars) {
        boolean changed = pullFromUi(name, username, passwordChars);
        if (changed || dirty) {
            save();
            dirty = false;
        }
    }

    public void clampIndexAfterListChange() {
        if (currentIndex >= accounts.size()) {
            currentIndex = Math.max(0, accounts.size() - 1);
        }
    }

    public void clearPasswordsInMemory() {
        for (AccountInfo a : accounts) {
            if (a != null) a.clearPassword();
        }
    }
}
