package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.HistoryStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HistoryServiceTest {
    @TempDir
    Path dir;

    private HistoryStore store(String name) {
        return new HistoryStore(dir.resolve(name).toFile());
    }

    @Test
    void dirtySaveAndLoad() throws Exception {
        // No file on disk: first start = empty history.
        AtomicReference<String> warn = new AtomicReference<>();
        HistoryService svc = new HistoryService(store("history.json"), warn::set);
        svc.addRecord("拨号", "u1", "成功", "--", "--");
        assertTrue(svc.dirtyFlag().get());
        assertTrue(svc.saveIfDirty());
        assertFalse(svc.dirtyFlag().get());

        HistoryService loaded = new HistoryService(store("history.json"), warn::set);
        loaded.load();
        assertEquals(1, loaded.records().size());
        assertEquals("拨号", loaded.records().get(0)[1]);
        assertNull(warn.get());
    }

    @Test
    void ensureLoadedBeforeAddDoesNotWipeDisk() throws Exception {
        // Existing JSON history (as persisted by a previous run) must survive a lazy load.
        HistoryStore pre = store("history.json");
        List<String[]> existing = new ArrayList<>();
        existing.add(new String[]{"2020-01-01 00:00:00", "拨号", "old", "成功", "--", "--"});
        pre.save(existing);

        AtomicReference<String> warn = new AtomicReference<>();
        HistoryService svc = new HistoryService(store("history.json"), warn::set);
        // No explicit load — first mutation must merge with disk
        svc.addRecord("断开", "new", "成功", "--", "--");
        assertTrue(svc.saveIfDirty());

        HistoryService reloaded = new HistoryService(store("history.json"), warn::set);
        reloaded.ensureLoaded();
        assertEquals(2, reloaded.records().size());
        assertEquals("断开", reloaded.records().get(0)[1]);
        assertEquals("拨号", reloaded.records().get(1)[1]);
        assertNull(warn.get());
    }

    @Test
    void failedSaveKeepsHistoryDirtyForRetry() throws Exception {
        AtomicReference<String> warn = new AtomicReference<>();
        FailingHistoryStore store = new FailingHistoryStore(dir.resolve("history.json").toFile());
        HistoryService svc = new HistoryService(store, warn::set);
        svc.addRecord("拨号", "u1", "成功", "--", "--");

        assertTrue(svc.saveIfDirty());
        assertTrue(svc.dirtyFlag().get());
        assertNotNull(warn.get());

        store.fail = false;
        assertTrue(svc.saveIfDirty());
        assertFalse(svc.dirtyFlag().get());
    }

    @Test
    void invalidJsonReportsAndKeepsInMemoryOnly() throws Exception {
        Files.write(dir.resolve("history.json"),
            "{not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<String> warn = new AtomicReference<>();
        HistoryService svc = new HistoryService(store("history.json"), warn::set);
        svc.load();
        assertNotNull(warn.get(), "invalid JSON must be reported");
        assertEquals(0, svc.records().size());
    }

    @Test
    void clearWritesEmptyFileImmediately() throws Exception {
        HistoryService svc = new HistoryService(store("history.json"), msg -> { });
        svc.addRecord("拨号", "u", "成功", "--", "--");
        svc.clear();
        assertTrue(svc.records().isEmpty());
        List<String[]> onDisk = store("history.json").load();
        assertNotNull(onDisk);
        assertTrue(onDisk.isEmpty());
    }

    @Test
    void addRecordInsertsAtHeadWithTimestamp() {
        HistoryService svc = new HistoryService(store("history.json"), msg -> { });
        svc.addRecord("拨号", "u1", "成功", "--", "--");
        svc.addRecord("断开", "u1", "成功", "00:01:00", "1 KB");
        assertEquals(2, svc.records().size());
        assertEquals("断开", svc.records().get(0)[1]);
        // time column is present and looks like a timestamp
        assertTrue(svc.records().get(0)[0].matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertArrayEquals(new String[]{"时间", "操作", "账号", "结果", "连接时长", "流量总和"},
            new String[]{"时间", "操作", "账号", "结果", "连接时长", "流量总和"});
    }

    private static final class FailingHistoryStore extends HistoryStore {
        private boolean fail = true;

        private FailingHistoryStore(java.io.File file) {
            super(file);
        }

        @Override
        public void save(java.util.List<String[]> historyRecords) throws java.io.IOException {
            if (fail) throw new java.io.IOException("disk unavailable");
            super.save(historyRecords);
        }
    }
}
