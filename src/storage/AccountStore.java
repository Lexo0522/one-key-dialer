package storage;

import model.AccountInfo;
import util.AtomicFiles;
import util.CryptoUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class AccountStore {
    private final File file;
    private boolean needsResaveAfterMigration = false;

    public AccountStore(File file) {
        this.file = file;
    }

    @Deprecated
    public AccountStore(String fileName, String ignoredSecretKey) {
        this(new File(fileName));
    }

    public boolean consumeMigrationFlag() {
        boolean v = needsResaveAfterMigration;
        needsResaveAfterMigration = false;
        return v;
    }

    public void load(List<AccountInfo> accounts) throws IOException {
        accounts.clear();
        needsResaveAfterMigration = false;
        if (!file.exists()) return;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            AccountInfo current = null;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                }
                line = line.trim();
                if (line.startsWith("[Account") && line.endsWith("]")) {
                    if (current != null) accounts.add(current);
                    current = new AccountInfo("", "", "", "");
                } else if (current != null) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        switch (key) {
                            case "name":
                                current.name = value;
                                break;
                            case "connection":
                                // legacy field: map to name if name empty
                                if (current.name == null || current.name.isEmpty()) {
                                    current.name = value;
                                    needsResaveAfterMigration = true;
                                }
                                break;
                            case "username":
                                current.username = value;
                                break;
                            case "password":
                                current.setPassword(decryptPasswordField(value));
                                break;
                            case "remark":
                                current.remark = value;
                                break;
                        }
                    }
                }
            }
            if (current != null) accounts.add(current);
        }
    }

    private String decryptPasswordField(String raw) throws IOException {
        if (raw == null || raw.isEmpty()) return "";
        char[] plain = null;
        try {
            if (!CryptoUtil.isAesFormat(raw)) {
                needsResaveAfterMigration = true;
            }
            plain = CryptoUtil.decryptToChars(raw);
            return plain.length == 0 ? "" : new String(plain);
        } catch (Exception e) {
            // Fail closed: do not treat ciphertext as password
            needsResaveAfterMigration = true;
            throw new IOException("密码解密失败，请重新输入该账号密码: " + e.getMessage(), e);
        } finally {
            if (plain != null) java.util.Arrays.fill(plain, '\0');
        }
    }

    public void save(List<AccountInfo> accounts) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < accounts.size(); i++) {
            AccountInfo a = accounts.get(i);
            sb.append("[Account").append(i + 1).append("]\n");
            sb.append("name=").append(sanitizeIni(a.name)).append('\n');
            sb.append("username=").append(sanitizeIni(a.username)).append('\n');
            String enc;
            char[] pw = a.copyPasswordChars();
            try {
                enc = CryptoUtil.encrypt(pw);
            } catch (Exception e) {
                throw new IOException("密码加密失败: " + e.getMessage(), e);
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
            sb.append("password=").append(enc).append('\n');
            sb.append("remark=").append(sanitizeIni(a.remark)).append('\n');
            sb.append('\n');
        }
        AtomicFiles.writeUtf8(file.toPath(), sb.toString());
    }

    private static String sanitizeIni(String value) {
        if (value == null) return "";
        return value.replace("\r", " ").replace("\n", " ");
    }

    /** Export without password (safe default). */
    public static String toCsvLineSafe(AccountInfo account) {
        return HistoryStore.toCsvLine(new String[]{
            account != null ? account.name : "",
            account != null ? account.username : "",
            account != null ? account.remark : ""
        });
    }

    /** Export including password — caller must warn user. */
    public static String toCsvLineWithPassword(AccountInfo account) {
        return HistoryStore.toCsvLine(new String[]{
            account != null ? account.name : "",
            account != null ? account.username : "",
            account != null ? account.getPassword() : "",
            account != null ? account.remark : ""
        });
    }

    public static List<AccountInfo> loadCsv(File csvFile) throws IOException {
        List<AccountInfo> imported = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csvFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            CsvLayout layout = CsvLayout.UNKNOWN;
            while ((line = br.readLine()) != null) {
                if (first) {
                    first = false;
                    if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                    CsvLayout headerLayout = detectCsvHeaderLayout(line);
                    if (headerLayout != CsvLayout.UNKNOWN) {
                        layout = headerLayout;
                        continue; // header row
                    }
                }
                String[] parts = parseCsvLine(line);
                if (parts.length < 2) continue;
                imported.add(accountFromCsvParts(parts, layout));
            }
        }
        return imported;
    }

    public enum CsvLayout {
        UNKNOWN,
        /** name,username,remark */
        SAFE_3,
        /** name,username,password,remark */
        WITH_PASSWORD_4
    }

    /**
     * Detect export header. Unknown lines are treated as data (legacy files without header).
     */
    public static CsvLayout detectCsvHeaderLayout(String line) {
        if (line == null) return CsvLayout.UNKNOWN;
        String lower = line.toLowerCase();
        boolean looksHeader = lower.contains("昵称") || lower.contains("name")
            || lower.contains("账号") || lower.contains("username");
        if (!looksHeader) return CsvLayout.UNKNOWN;
        if (lower.contains("密码") || lower.contains("password")) {
            return CsvLayout.WITH_PASSWORD_4;
        }
        // 3-col safe export or "name,user,remark" header without password
        return CsvLayout.SAFE_3;
    }

    public static AccountInfo accountFromCsvParts(String[] parts, CsvLayout layout) {
        if (parts == null || parts.length < 2) {
            return new AccountInfo("", "", "", "");
        }
        if (layout == CsvLayout.WITH_PASSWORD_4 || parts.length >= 4) {
            String remark = parts.length >= 4 ? parts[3] : "";
            String pass = parts.length >= 3 ? parts[2] : "";
            return new AccountInfo(parts[0], parts[1], pass, remark);
        }
        if (layout == CsvLayout.SAFE_3 || parts.length == 3) {
            // Explicit safe layout or ambiguous 3-col without header: treat col3 as remark (never password).
            return new AccountInfo(parts[0], parts[1], "", parts[2]);
        }
        return new AccountInfo(parts[0], parts[1], "", "");
    }

    private static String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    values.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    public File getFile() {
        return file;
    }
}
