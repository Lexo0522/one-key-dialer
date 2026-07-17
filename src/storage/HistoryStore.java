package storage;

import util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class HistoryStore {
    private final File file;

    public HistoryStore(File file) {
        this.file = file;
    }

    public HistoryStore(String fileName) {
        this(new File(fileName));
    }

    public void load(List<String[]> historyRecords) throws IOException {
        historyRecords.clear();
        if (!file.exists()) return;

        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = r.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                    String lower = line.toLowerCase();
                    if (lower.startsWith("时间") || lower.startsWith("time")) {
                        continue;
                    }
                }
                String[] parts = parseCsvLine(line);
                if (parts.length >= 5) {
                    if (parts.length < 6) {
                        String[] padded = new String[6];
                        System.arraycopy(parts, 0, padded, 0, parts.length);
                        for (int i = parts.length; i < 6; i++) padded[i] = "--";
                        parts = padded;
                    } else if (parts.length > 6) {
                        String[] merged = new String[6];
                        System.arraycopy(parts, 0, merged, 0, 5);
                        StringBuilder rest = new StringBuilder(parts[5]);
                        for (int i = 6; i < parts.length; i++) {
                            rest.append(',').append(parts[i]);
                        }
                        merged[5] = rest.toString();
                        parts = merged;
                    }
                    historyRecords.add(parts);
                }
            }
        }
    }

    public void save(List<String[]> historyRecords) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String[] r : historyRecords) {
            sb.append(toCsvLine(r)).append('\n');
        }
        AtomicFiles.writeUtf8(file.toPath(), sb.toString());
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

    static String[] parseCsvLine(String line) {
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
