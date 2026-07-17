package util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConnectivityConfirmTest {

    @Test
    void allFailuresReturnFalse() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = ConnectivityConfirm.confirm(
            host -> {
                calls.incrementAndGet();
                return false;
            },
            "x",
            3,
            0L,
            ms -> {
            });
        assertFalse(ok);
        assertEquals(3, calls.get());
    }

    @Test
    void successOnLaterAttempt() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = ConnectivityConfirm.confirm(
            host -> calls.incrementAndGet() >= 2,
            "x",
            3,
            0L,
            ms -> {
            });
        assertTrue(ok);
        assertEquals(2, calls.get());
    }

    @Test
    void successOnFirstAttemptDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = ConnectivityConfirm.confirm(
            host -> {
                calls.incrementAndGet();
                return true;
            },
            "x",
            5,
            0L,
            ms -> {
            });
        assertTrue(ok);
        assertEquals(1, calls.get());
    }

    @Test
    void interruptedSleepReturnsFalse() {
        boolean ok = ConnectivityConfirm.confirm(
            host -> false,
            "x",
            2,
            10L,
            ms -> {
                throw new InterruptedException("stop");
            });
        assertFalse(ok);
        assertTrue(Thread.interrupted());
    }

    @Test
    void historyStatusMapping() {
        assertEquals("成功", ConnectivityConfirm.historyStatus(true, true, 0));
        assertEquals(ConnectivityConfirm.HISTORY_STATUS_RAS_NO_INTERNET,
            ConnectivityConfirm.historyStatus(true, false, 0));
        assertEquals("失败:691", ConnectivityConfirm.historyStatus(false, false, 691));
    }

    @Test
    void autoModeUsesHttpWhenIcmpFails() {
        AtomicInteger icmp = new AtomicInteger();
        AtomicInteger http = new AtomicInteger();
        ConnectivityConfirm.Config cfg = ConnectivityConfirm.Config.from(
            ConnectivityConfirm.MODE_AUTO, "h", "http://x", 1, 0);
        boolean ok = ConnectivityConfirm.confirm(
            cfg,
            host -> {
                icmp.incrementAndGet();
                return false;
            },
            url -> {
                http.incrementAndGet();
                return true;
            },
            ms -> {
            });
        assertTrue(ok);
        assertEquals(1, icmp.get());
        assertEquals(1, http.get());
    }

    @Test
    void httpModeSkipsIcmp() {
        AtomicInteger icmp = new AtomicInteger();
        ConnectivityConfirm.Config cfg = ConnectivityConfirm.Config.from(
            ConnectivityConfirm.MODE_HTTP, "h", "http://x", 1, 0);
        boolean ok = ConnectivityConfirm.confirm(
            cfg,
            host -> {
                icmp.incrementAndGet();
                return true;
            },
            url -> false,
            ms -> {
            });
        assertFalse(ok);
        assertEquals(0, icmp.get());
    }

    @Test
    void normalizeModeDefaultsUnknown() {
        assertEquals(ConnectivityConfirm.MODE_AUTO, ConnectivityConfirm.normalizeMode("weird"));
        assertEquals(ConnectivityConfirm.MODE_ICMP, ConnectivityConfirm.normalizeMode("ICMP"));
    }
}
