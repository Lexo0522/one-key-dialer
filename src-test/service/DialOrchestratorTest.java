package service;

import model.DialLifecycle;
import model.DialSnapshot;
import util.ConnectivityConfirm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DialOrchestratorTest {

    private DialOrchestrator orch;

    @AfterEach
    void tearDown() {
        if (orch != null) orch.shutdown();
    }

    @Test
    void credentialCachePreferedForBackgroundSnapshot() {
        DialLifecycle life = new DialLifecycle();
        FakeHost host = new FakeHost(life);
        orch = new DialOrchestrator(host);
        orch.setPostDialInternetConfirm(() -> true);
        orch.updateCredentials("user1", "secret".toCharArray());

        AtomicInteger dialCalls = new AtomicInteger();
        host.dialImpl = snap -> {
            dialCalls.incrementAndGet();
            assertEquals("user1", snap.username);
            assertEquals("secret", snap.passwordAsString());
            return new DialService.DialResult(0, "ok");
        };

        orch.dialSyncAuto();
        assertEquals(1, dialCalls.get());
        assertEquals(0, host.edtWaits.get());
        assertTrue(host.online.get());
        assertEquals(1, host.history.size());
        assertEquals(ConnectivityConfirm.HISTORY_STATUS_SUCCESS, host.history.get(0)[2]);
    }

    @Test
    void rasSuccessNoInternetHistory() {
        DialLifecycle life = new DialLifecycle();
        FakeHost host = new FakeHost(life);
        orch = new DialOrchestrator(host);
        orch.setPostDialInternetConfirm(() -> false);
        orch.updateCredentials("u", "p".toCharArray());
        host.dialImpl = snap -> new DialService.DialResult(0, "ok");

        orch.dialSyncAuto();
        assertFalse(host.online.get());
        assertEquals(ConnectivityConfirm.HISTORY_STATUS_RAS_NO_INTERNET, host.history.get(0)[2]);
        assertEquals(0, host.disconnectCalls.get());
    }

    @Test
    void rasSuccessNoInternetDisconnectPolicy() {
        DialLifecycle life = new DialLifecycle();
        FakeHost host = new FakeHost(life);
        orch = new DialOrchestrator(host);
        orch.setPostDialInternetConfirm(() -> false);
        orch.setDisconnectOnNoInternet(true);
        orch.updateCredentials("u", "p".toCharArray());
        host.dialImpl = snap -> new DialService.DialResult(0, "ok");

        orch.dialSyncAuto();
        assertFalse(host.online.get());
        assertEquals(1, host.disconnectCalls.get());
        assertTrue(host.history.get(0)[2].contains(ConnectivityConfirm.HISTORY_STATUS_RAS_NO_INTERNET));
        assertTrue(host.history.get(0)[2].contains("已断开"));
    }

    @Test
    void busySkipsSecondDial() {
        DialLifecycle life = new DialLifecycle();
        FakeHost host = new FakeHost(life);
        orch = new DialOrchestrator(host);
        orch.setPostDialInternetConfirm(() -> true);
        orch.updateCredentials("u", "p".toCharArray());
        AtomicInteger dialCalls = new AtomicInteger();
        host.dialImpl = snap -> {
            dialCalls.incrementAndGet();
            // stay "busy" is handled by lifecycle around call; second concurrent not in this unit
            return new DialService.DialResult(0, "ok");
        };
        assertTrue(life.tryBeginDial());
        orch.dialSyncAuto();
        assertEquals(0, dialCalls.get());
        life.end();
    }

    @Test
    void handleDialResultFailureMapsCode() {
        DialLifecycle life = new DialLifecycle();
        FakeHost host = new FakeHost(life);
        orch = new DialOrchestrator(host);
        orch.handleDialResult(new DialService.DialResult(691, ""), "拨号", false);
        assertEquals("失败:691", host.history.get(0)[2]);
        assertFalse(host.online.get());
    }

    /** Minimal host with injectable dial. */
    static final class FakeHost implements DialOrchestrator.Host {
        final DialLifecycle life;
        final AtomicBoolean online = new AtomicBoolean(false);
        final AtomicLong totalDial = new AtomicLong();
        final AtomicLong successDial = new AtomicLong();
        final AtomicInteger edtWaits = new AtomicInteger();
        final AtomicInteger disconnectCalls = new AtomicInteger();
        final List<String[]> history = new ArrayList<>();
        final AtomicReference<String> lastLog = new AtomicReference<>();
        java.util.function.Function<DialSnapshot, DialService.DialResult> dialImpl =
            s -> new DialService.DialResult(-1, "unset");

        final DialService dialService = new DialService(
            "pppoe_native_java",
            () -> "pppoe_native_java",
            v -> {
            },
            () -> {
            },
            m -> {
            },
            m -> {
            },
            m -> {
            }
        ) {
            @Override
            public DialResult dial(DialSnapshot snapshot) {
                return dialImpl.apply(snapshot);
            }

            @Override
            public int disconnect(String activeConnName) {
                disconnectCalls.incrementAndGet();
                return 0;
            }

            @Override
            public int disconnectSync(String activeConnName) {
                disconnectCalls.incrementAndGet();
                return 0;
            }
        };

        FakeHost(DialLifecycle life) {
            this.life = life;
        }

        @Override public DialLifecycle lifecycle() { return life; }
        @Override public DialService dialService() { return dialService; }
        @Override public String connectionName() { return "pppoe_native_java"; }
        @Override public String activeConnectionName() { return "pppoe_native_java"; }
        @Override public BooleanSupplier isOnline() { return online::get; }
        @Override public LongSupplier connectTimeMillis() { return () -> 0L; }
        @Override public LongSupplier sessionTrafficBytes() { return () -> 0L; }
        @Override public Supplier<String> currentAccountName() { return () -> "acc"; }
        @Override public AtomicLong totalDialCount() { return totalDial; }
        @Override public AtomicLong successDialCount() { return successDial; }
        @Override public boolean validateBeforeDialInteractive() { return true; }
        @Override public boolean validateBeforeDialQuiet() { return !online.get(); }
        @Override public DialSnapshot captureSnapshotFromUi() {
            return new DialSnapshot("pppoe_native_java", "ui-user", "ui-pass".toCharArray());
        }
        @Override public void saveCurrentAccount() { }
        @Override public void updateStatus(boolean on) { online.set(on); }
        @Override public void setDialControlsEnabled(boolean enabled) { }
        @Override public void logInfo(String message) { lastLog.set(message); }
        @Override public void logSuccess(String message) { lastLog.set(message); }
        @Override public void logWarning(String message) { lastLog.set(message); }
        @Override public void logError(String message) { lastLog.set(message); }
        @Override public void notifyUser(String title, String message) { }
        @Override public void addHistory(String operation, String account, String result,
                                         String duration, String traffic) {
            history.add(new String[]{operation, account, result, duration, traffic});
        }
        @Override public void saveSettingsAfterSuccess() { }
        @Override public boolean isEventDispatchThread() { return false; }
        @Override public void runOnEdtAndWait(Runnable action) {
            edtWaits.incrementAndGet();
            action.run();
        }
    }
}
