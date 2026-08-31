package service;

import model.AccountInfo;
import model.PasswordChars;
import storage.AccountStore;
import storage.StorageException;
import util.FilePermissions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Multi-account list, selection index, and persistence. Owns nothing about dialing:
 * credentials are handed out as one-shot copies consumed by the orchestrator.
 * Secrets at rest are DPAPI-protected via {@link AccountStore}; when protection is
 * unavailable passwords are not persisted and must be re-entered before dialing.
 */
public final class AccountSession {
    public interface Logger {
        void info(String message);

        void error(String message);
    }

    /**
     * The list is exposed to the Swing account manager for in-place edits. Keep
     * its monitor as the boundary for persistence snapshots so a background save
     * cannot observe a partial edit or fail with ConcurrentModificationException.
     */
    private final List<AccountInfo> accounts = Collections.synchronizedList(new ArrayList<>());
    private final AccountStore store;
    private final Logger logger;
    /** Nullable — interactive EDT saves run here; shutdown paths stay synchronous. */
    private final Executor asyncSaver;
    private final Object saveLock = new Object();
    private long saveGeneration;
    private volatile int currentIndex;
    private volatile boolean dirty;

    public AccountSession(AccountStore store, Logger logger) {
        this(store, logger, null);
    }

    public AccountSession(AccountStore store, Logger logger, Executor asyncSaver) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = logger != null ? logger : new Logger() {
            @Override public void info(String message) { }
            @Override public void error(String message) { }
        };
        this.asyncSaver = asyncSaver;
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
        AccountStore.LoadResult result;
        try {
            result = store.load();
        } catch (StorageException e) {
            logger.error("加载账号失败（使用空账号列表，未覆盖原文件）: " + e.getMessage());
            accounts.clear();
            ensureDefaultAccount();
            setCurrentIndex(currentIndex);
            return;
        }
        accounts.clear();
        if (result != null) {
            accounts.addAll(result.accounts);
            if (result.droppedSecrets > 0) {
                logger.error("有 " + result.droppedSecrets
                    + " 个账号密码无法解密（DPAPI 不可用或密钥损坏），拨号前请重新输入");
            }
        }
        ensureDefaultAccount();
        setCurrentIndex(currentIndex);
    }

    public void ensureDefaultAccount() {
        if (accounts.isEmpty()) {
            accounts.add(new AccountInfo("默认账号", "", "", ""));
        }
    }

    public boolean save() {
        List<AccountInfo> snapshot = copyForPersistence();
        synchronized (saveLock) {
            // A synchronous save (shutdown / account switch) supersedes queued
            // background snapshots that may otherwise finish later and overwrite it.
            saveGeneration++;
            return saveSnapshot(snapshot);
        }
    }

    /**
     * Persist off the calling (typically EDT) thread; {@link #isDirty()} stays true
     * until the write lands, so a shutdown-path {@link #save()} still persists.
     */
    public boolean saveInBackground() {
        Executor saver = asyncSaver;
        if (saver == null) return save();
        List<AccountInfo> snapshot = copyForPersistence();
        final long generation;
        synchronized (saveLock) {
            generation = ++saveGeneration;
        }
        dirty = true;
        try {
            saver.execute(() -> {
                try {
                    synchronized (saveLock) {
                        // Only the newest queued snapshot may write. This also
                        // makes an out-of-order executor harmless.
                        if (generation != saveGeneration) return;
                        if (saveSnapshot(snapshot)) {
                            dirty = false;
                        }
                    }
                } finally {
                    clearPasswords(snapshot);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            clearPasswords(snapshot);
            return save();
        }
    }

    /** Copy live account rows while holding the synchronized-list monitor. */
    private List<AccountInfo> copyForPersistence() {
        List<AccountInfo> snapshot = new ArrayList<>();
        synchronized (accounts) {
            for (AccountInfo account : accounts) {
                if (account == null) continue;
                AccountInfo copy = new AccountInfo(account.name, account.username, "", account.remark);
                char[] password = account.copyPasswordChars();
                try {
                    copy.setPasswordChars(password);
                } finally {
                    PasswordChars.clear(password);
                }
                snapshot.add(copy);
            }
        }
        return snapshot;
    }

    private boolean saveSnapshot(List<AccountInfo> snapshot) {
        try {
            store.save(snapshot);
            FilePermissions.restrictToOwner(store.getFile());
            return true;
        } catch (IOException e) {
            logger.error("保存账号失败: " + e.getMessage());
            return false;
        } finally {
            clearPasswords(snapshot);
        }
    }

    private static void clearPasswords(List<AccountInfo> snapshot) {
        if (snapshot == null) return;
        for (AccountInfo account : snapshot) {
            if (account != null) account.clearPassword();
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
            if (save()) {
                dirty = false;
            }
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
