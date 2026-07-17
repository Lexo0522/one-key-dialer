package service;

import storage.HistoryStore;

import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-memory dial history + dirty flag + HistoryStore persistence.
 * Disk load is deferred until first mutation / UI bind / export (see {@link #ensureLoaded()}).
 */
public class HistoryService {
    private static final int MAX_HISTORY_RECORDS = 1000;
    private static final DateTimeFormatter FMT_HISTORY_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HistoryStore store;
    private final Consumer<String> onWarn;
    private final List<String[]> records = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /** True after a load attempt completed (success or failure). */
    private final AtomicBoolean diskLoadAttempted = new AtomicBoolean(false);

    private DefaultTableModel tableModel;
    private Supplier<Boolean> uiActive = () -> true;

    public HistoryService(HistoryStore store, Consumer<String> onWarn) {
        this.store = store;
        this.onWarn = onWarn != null ? onWarn : msg -> {
        };
    }

    public AtomicBoolean dirtyFlag() {
        return dirty;
    }

    public List<String[]> records() {
        return records;
    }

    public void bindTable(DefaultTableModel model, Supplier<Boolean> uiActive) {
        this.tableModel = model;
        this.uiActive = uiActive != null ? uiActive : () -> true;
    }

    /**
     * Load history from disk once. Safe to call repeatedly.
     * Must run before first save/export when memory may be empty so CSV is not wiped.
     */
    public void ensureLoaded() {
        if (diskLoadAttempted.compareAndSet(false, true)) {
            loadFromDisk();
        }
    }

    /** Force (re)load from disk; marks load as attempted. Used by tests / explicit refresh. */
    public void load() {
        diskLoadAttempted.set(true);
        loadFromDisk();
    }

    private void loadFromDisk() {
        try {
            synchronized (records) {
                store.load(records);
                while (records.size() > MAX_HISTORY_RECORDS) {
                    records.remove(records.size() - 1);
                }
            }
        } catch (IOException e) {
            onWarn.accept("加载历史记录失败: " + e.getMessage());
        }
    }

    public void bindTableFromMemory() {
        ensureLoaded();
        if (tableModel == null) return;
        tableModel.setRowCount(0);
        synchronized (records) {
            for (String[] record : records) {
                tableModel.addRow(record);
            }
        }
    }

    public void save() {
        ensureLoaded();
        try {
            synchronized (records) {
                store.save(records);
            }
        } catch (IOException e) {
            onWarn.accept("保存历史记录失败: " + e.getMessage());
        }
    }

    /** Save only if dirty; returns true if a save was attempted. */
    public boolean saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
            return true;
        }
        return false;
    }

    public void addRecord(String operation, String account, String result, String duration, String totalTraffic) {
        ensureLoaded();
        String time = LocalDateTime.now().format(FMT_HISTORY_TIME);
        String[] record = {time, operation, account, result, duration, totalTraffic};
        records.add(0, record);
        while (records.size() > MAX_HISTORY_RECORDS) {
            records.remove(records.size() - 1);
        }
        SwingUtilities.invokeLater(() -> {
            if (tableModel != null && Boolean.TRUE.equals(uiActive.get())) {
                tableModel.insertRow(0, record);
                while (tableModel.getRowCount() > MAX_HISTORY_RECORDS) {
                    tableModel.removeRow(tableModel.getRowCount() - 1);
                }
            }
        });
        dirty.set(true);
    }

    public void clear() {
        ensureLoaded();
        records.clear();
        if (tableModel != null) tableModel.setRowCount(0);
        dirty.set(true);
        saveIfDirty();
    }

    public void exportTo(File file) throws IOException {
        ensureLoaded();
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
            w.println("时间,操作,账号,结果,连接时长,流量总和");
            synchronized (records) {
                for (String[] r : records) {
                    w.println(HistoryStore.toCsvLine(r));
                }
            }
        }
    }
}
