package storage;

import model.AccountInfo;
import util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive CSV import/export for the account manager dialog.
 * This is a user-initiated file exchange feature, not app storage — the app never
 * reads its own storage from CSV.
 */
public final class AccountCsv {
    private AccountCsv() {
    }

    /** Export without password (safe default). */
    public static String toCsvLineSafe(AccountInfo account) {
        return toCsvLine(new String[]{
            account != null ? account.name : "",
            account != null ? account.username : "",
            account != null ? account.remark : ""
        });
    }

    /** Export including password — caller must warn the user first. */
    public static String toCsvLineWithPassword(AccountInfo account) {
        return toCsvLine(new String[]{
            account != null ? account.name : "",
            account != null ? account.username : "",
            account != null ? account.getPassword() : "",
            account != null ? account.remark : ""
        });
    }

    public static List<AccountInfo> load(File csvFile) throws IOException {
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

    public static void save(File csvFile, List<AccountInfo> accounts, boolean withPassword) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (withPassword) {
            sb.append(toCsvLine(new String[]{"昵称", "账号", "密码", "备注"})).append('\n');
            for (AccountInfo a : accounts) sb.append(toCsvLineWithPassword(a)).append('\n');
        } else {
            sb.append(toCsvLine(new String[]{"昵称", "账号", "备注"})).append('\n');
            for (AccountInfo a : accounts) sb.append(toCsvLineSafe(a)).append('\n');
        }
        AtomicFiles.writeUtf8(csvFile.toPath(), sb.toString());
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
            // Explicit safe layout or ambiguous 3-col without header: col3 is remark (never password).
            return new AccountInfo(parts[0], parts[1], "", parts[2]);
        }
        return new AccountInfo(parts[0], parts[1], "", "");
    }

    public static String toCsvLine(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeCsv(values[i]));
        }
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
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
}
