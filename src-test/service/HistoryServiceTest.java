package service;

import org.junit.jupiter.api.Test;
import storage.HistoryStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HistoryServiceTest {
    @Test
    void dirtySaveAndLoad() throws Exception {
        var tmp = Files.createTempFile("hist-svc", ".csv").toFile();
        tmp.deleteOnExit();
        AtomicReference<String> warn = new AtomicReference<>();
        HistoryService svc = new HistoryService(new HistoryStore(tmp), warn::set);
        svc.addRecord("拨号", "u1", "成功", "--", "--");
        assertTrue(svc.dirtyFlag().get());
        assertTrue(svc.saveIfDirty());
        assertFalse(svc.dirtyFlag().get());

        HistoryService loaded = new HistoryService(new HistoryStore(tmp), warn::set);
        loaded.load();
        assertEquals(1, loaded.records().size());
        assertEquals("拨号", loaded.records().get(0)[1]);
        assertNull(warn.get());
    }

    @Test
    void ensureLoadedBeforeAddDoesNotWipeDisk() throws Exception {
        var tmp = Files.createTempFile("hist-lazy", ".csv").toFile();
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(),
            "时间,操作,账号,结果,连接时长,流量总和\n"
                + "2020-01-01 00:00:00,拨号,old,成功,--,--\n",
            StandardCharsets.UTF_8);

        AtomicReference<String> warn = new AtomicReference<>();
        HistoryService svc = new HistoryService(new HistoryStore(tmp), warn::set);
        // No explicit load — first mutation must merge with disk
        svc.addRecord("断开", "new", "成功", "--", "--");
        assertTrue(svc.saveIfDirty());

        HistoryService reloaded = new HistoryService(new HistoryStore(tmp), warn::set);
        reloaded.ensureLoaded();
        assertEquals(2, reloaded.records().size());
        assertEquals("断开", reloaded.records().get(0)[1]);
        assertEquals("拨号", reloaded.records().get(1)[1]);
        assertNull(warn.get());
    }
}
