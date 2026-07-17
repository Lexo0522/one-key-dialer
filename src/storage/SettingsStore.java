package storage;

import util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public class SettingsStore {
    private final File file;
    private final File backupFile;

    public SettingsStore(File file, String backupSuffix) {
        this.file = file;
        this.backupFile = new File(file.getPath() + backupSuffix);
    }

    public SettingsStore(String fileName, String backupSuffix) {
        this(new File(fileName), backupSuffix);
    }

    public Map<String, String> load() throws IOException {
        Map<String, String> settings = loadFrom(file);
        if (settings.isEmpty() && backupFile.exists()) {
            try {
                return loadFrom(backupFile);
            } catch (IOException ignored) {
                return settings;
            }
        }
        return settings;
    }

    private Map<String, String> loadFrom(File src) throws IOException {
        Map<String, String> settings = new LinkedHashMap<>();
        if (!src.exists()) return settings;

        try (BufferedReader r = Files.newBufferedReader(src.toPath(), StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = r.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                }
                String[] p = line.split("=", 2);
                if (p.length == 2) {
                    settings.put(p[0].trim(), p[1].trim().replace("\r", "").replace("\n", ""));
                }
            }
        }
        return settings;
    }

    public void save(Map<String, String> settings) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String v = entry.getValue() == null ? "" : entry.getValue().replace("\r", " ").replace("\n", " ");
            sb.append(entry.getKey()).append("=").append(v).append("\n");
        }
        // backup current good file before replace
        if (file.exists()) {
            try {
                Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
        AtomicFiles.writeUtf8(file.toPath(), sb.toString());
    }

    public File getFile() {
        return file;
    }
}
