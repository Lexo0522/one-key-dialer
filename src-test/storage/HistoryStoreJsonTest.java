package storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreJsonTest {
    @TempDir
    Path dir;

    private File file() {
        return dir.resolve("history.json").toFile();
    }

    private List<String[]> sampleRows() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"2026-08-30 10:00:00", "拨号", "2023001", "成功", "--", "--"});
        rows.add(new String[]{"2026-08-30 10:05:00", "断开", "2023001", "成功", "00:05:00", "12.5 MB"});
        rows.add(new String[]{"2026-08-30 10:06:00", "拨号", "2023001", "失败:691", "--", "--"});
        return rows;
    }

    @Test
    void firstStartReturnsNull() throws Exception {
        HistoryStore store = new HistoryStore(file());
        assertNull(store.load());
    }

    @Test
    void saveAndReloadRoundTrip() throws Exception {
        HistoryStore store = new HistoryStore(file());
        store.save(sampleRows());

        List<String[]> loaded = store.load();
        assertNotNull(loaded);
        assertEquals(3, loaded.size());
        assertArrayEquals(new String[]{"2026-08-30 10:00:00", "拨号", "2023001", "成功", "--", "--"},
            loaded.get(0));
        assertArrayEquals(new String[]{"2026-08-30 10:05:00", "断开", "2023001", "成功", "00:05:00", "12.5 MB"},
            loaded.get(1));
        assertArrayEquals(new String[]{"2026-08-30 10:06:00", "拨号", "2023001", "失败:691", "--", "--"},
            loaded.get(2));
    }

    @Test
    void commasAndQuotesSurviveRoundTrip() throws Exception {
        HistoryStore store = new HistoryStore(file());
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"t", "拨号", "acc,with,comma", "成功", "--", "a \"quoted\" note"});
        store.save(rows);
        List<String[]> loaded = store.load();
        assertArrayEquals(rows.get(0), loaded.get(0));
    }

    @Test
    void invalidJsonIsRejected() throws Exception {
        Files.write(file().toPath(), "[]".getBytes(StandardCharsets.UTF_8));
        HistoryStore store = new HistoryStore(file());
        StorageException e = assertThrows(StorageException.class, store::load);
        assertEquals(StorageException.Kind.INVALID_JSON, e.kind());
    }

    @Test
    void unknownSchemaIsRejected() throws Exception {
        Files.write(file().toPath(),
            "{\"schemaVersion\": 2, \"data\": []}".getBytes(StandardCharsets.UTF_8));
        HistoryStore store = new HistoryStore(file());
        StorageException e = assertThrows(StorageException.class, store::load);
        assertEquals(StorageException.Kind.UNKNOWN_SCHEMA, e.kind());
    }

    @Test
    void legacyCsvIsIgnored() throws Exception {
        Files.write(dir.resolve("pppoe_history.csv"),
            "时间,操作,账号,结果,连接时长,流量总和\n".getBytes(StandardCharsets.UTF_8));
        HistoryStore store = new HistoryStore(file());
        assertNull(store.load());
    }

    @Test
    void shortRowsArePaddedWithEmptyStrings() throws Exception {
        HistoryStore store = new HistoryStore(file());
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"t", "拨号"});
        store.save(rows);
        List<String[]> loaded = store.load();
        assertEquals(6, loaded.get(0).length);
        assertEquals("", loaded.get(0)[5]);
    }

    @Test
    void nullFieldsNeverEscape() throws Exception {
        HistoryStore store = new HistoryStore(file());
        List<String[]> rows = new ArrayList<>();
        String[] row = new String[6];
        Arrays.fill(row, null);
        rows.add(row);
        store.save(rows);
        List<String[]> loaded = store.load();
        for (String cell : loaded.get(0)) {
            assertNotNull(cell);
        }
    }
}
