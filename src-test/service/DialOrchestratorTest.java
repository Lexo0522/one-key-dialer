package service;

import model.DialLifecycle;
import model.SessionTraffic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static service.DialFakes.FakeEnv;
import static service.DialFakes.FakePort;
import static service.DialFakes.FakeView;

/** Dial orchestration: precheck, lifecycle, no-internet policy, history/stats, exit races. */
class DialOrchestratorTest {
    private FakePort port;
    private FakeView view;
    private FakeEnv env;
    private DialLifecycle lifecycle;
    private SessionTraffic stats;
    private DialOrchestrator orch;

    @BeforeEach
    void setUp() {
        port = new FakePort();
        view = new FakeView();
        env = new FakeEnv();
        lifecycle = new DialLifecycle();
        stats = new SessionTraffic();
        orch = new DialOrchestrator(port, view, env, lifecycle, stats);
        // Never touch the real network from tests.
        orch.setPostDialConnectivity(() -> true);
    }

    @AfterEach
    void tearDown() {
        orch.shutdown();
    }

    @Test
    void userDialCapturesOneShotCredentialsAndClearsThem() throws Exception {
        orch.dialAsyncUser();
        waitFor(() -> !lifecycle.isIdle() || port.connectCalls.get() == 1);
        waitFor(() -> !lifecycle.isBusy());

        assertEquals(1, port.connectCalls.get());
        assertEquals(1, port.receivedCredentials.size());
        assertArrayEquals(new String[]{"user1", "pass1"}, port.receivedCredentials.get(0));
        // history + counters: exactly one history row, one attempt, one success
        assertEquals(1, env.history.size());
        assertEquals(1, stats.totalDialCount().get());
        assertEquals(1, stats.successDialCount().get());
        assertEquals("拨号", env.history.get(0)[0]);
        assertEquals(model.DialOutcome.SUCCESS.text(), env.history.get(0)[2]);
        // busy state released
        assertTrue(lifecycle.isIdle());
    }

    @Test
    void concurrentDialRequestsOnlyOneRuns() throws Exception {
        port.slowConnect = true;
        orch.dialAsyncUser();
        waitFor(lifecycle::isBusy); // first attempt in flight
        orch.dialAsyncUser();       // second EDT entry while busy
        waitFor(() -> !lifecycle.isBusy());

        assertEquals(1, port.connectCalls.get(), "second request must be refused while busy");
        assertEquals(1, stats.totalDialCount().get());
    }

    @Test
    void precheckFailureAbortsDial() {
        view.allowDial = false;
        orch.dialAsyncUser();
        assertEquals(0, port.connectCalls.get());
        assertEquals(0, stats.totalDialCount().get());
        assertTrue(env.history.isEmpty());
    }

    @Test
    void noInternetPolicyDisconnectsWhenEnabled() throws Exception {
        env.disconnectOnNoInternet = true;
        orch.setPostDialConnectivity(() -> false);

        orch.dialAsyncUser();
        waitFor(() -> env.history.size() == 1);

        assertEquals(1, port.disconnectCalls.get(), "policy must disconnect the PPP session");
        assertEquals(1, env.history.size());
        assertEquals(model.DialOutcome.RAS_NO_INTERNET.text() + "/已断开", env.history.get(0)[2]);
    }

    @Test
    void noInternetPolicyKeepsSessionWhenDisabled() throws Exception {
        env.disconnectOnNoInternet = false;
        orch.setPostDialConnectivity(() -> false);

        orch.dialAsyncUser();
        waitFor(() -> env.history.size() == 1);

        assertEquals(0, port.disconnectCalls.get());
        assertEquals(model.DialOutcome.RAS_NO_INTERNET.text(), env.history.get(0)[2]);
    }

    @Test
    void dialFailureWritesHistoryOnceAndNoSuccessCount() throws Exception {
        port.dialResults.add(new DialPort.DialResult(691, "ERROR 691"));
        orch.dialAsyncUser();
        waitFor(() -> env.history.size() == 1);

        assertEquals(1, stats.totalDialCount().get());
        assertEquals(0, stats.successDialCount().get());
        assertEquals(1, env.history.size());
        assertEquals(model.DialOutcome.FAILURE.text() + ":691", env.history.get(0)[2]);
    }

    @Test
    void autoDialSkipsWhenAlreadyOnline() throws Exception {
        env.online = true;
        orch.dialAuto();
        waitFor(() -> view.logs.stream().anyMatch(s -> s.contains("无需重复拨号")));
        assertEquals(0, port.connectCalls.get());
        assertEquals(0, stats.totalDialCount().get());
        assertTrue(env.history.isEmpty());
    }

    @Test
    void autoDialMarshalsCaptureThroughView() throws Exception {
        view.pretendOnEdt = false; // background caller
        orch.dialAuto();
        waitFor(() -> port.connectCalls.get() == 1);
        assertEquals(1, view.credentialCaptures.get());
    }

    @Test
    void userDisconnectWritesHistoryOnce() throws Exception {
        env.connectTimeMillis = System.currentTimeMillis() - 65_000;
        env.trafficBytes = 1024;
        orch.disconnectAsyncUser();
        waitFor(() -> env.history.size() == 1);

        assertEquals(1, port.disconnectCalls.get());
        assertEquals("断开", env.history.get(0)[0]);
        assertTrue(lifecycle.isIdle());
    }

    @Test
    void scheduledDisconnectRecordsFailureCode() throws Exception {
        port.disconnectResult = 825;
        orch.disconnectScheduled();
        waitFor(() -> env.history.size() == 1);

        assertEquals(1, env.history.size());
        assertEquals("定时断开", env.history.get(0)[0]);
        assertEquals(model.DialOutcome.FAILURE.text(), env.history.get(0)[2]);
    }

    @Test
    void shutdownPreventsNewDials() {
        orch.shutdown();
        orch.dialAuto();
        assertEquals(0, port.connectCalls.get());
    }

    @Test
    void connectThrowingStillClearsLifecycleAndCountsAttempt() throws Exception {
        port.connectFailure = new RuntimeException("boom");
        orch.dialAsyncUser();
        waitFor(() -> stats.totalDialCount().get() == 1 && !lifecycle.isBusy());
        assertTrue(lifecycle.isIdle());
        assertEquals(1, stats.totalDialCount().get());
        assertEquals(0, env.history.size(), "no result ⇒ no history row");
    }

    @Test
    void saveAfterSuccessOnlyForUserDial() throws Exception {
        orch.dialAsyncUser();
        waitFor(() -> env.history.size() == 1);
        assertEquals(1, env.persistCalls.get());

        orch.dialAuto();
        waitFor(() -> port.connectCalls.get() == 2 && env.history.size() == 2);
        assertEquals(1, env.persistCalls.get(), "auto dial must not persist settings");
    }

    @Test
    void busyViewPhaseTransitionsEndAtNull() throws Exception {
        orch.dialAsyncUser();
        waitFor(() -> !lifecycle.isBusy());
        // phase clear runs in the same finally block right after lifecycle.end();
        // poll for it explicitly instead of racing the two statements
        waitFor(() -> view.phaseChanges.get() >= 2);
        assertNull(view.lastPhase.get(), "phase must be cleared after the op");
        assertTrue(view.phaseChanges.get() >= 2, "dialing + clear");
    }

    @Test
    void shutdownWhileDialInFlightEndsIdle() throws Exception {
        final AtomicBoolean dialReturned = new AtomicBoolean(false);
        port.slowConnect = true;
        Thread t = new Thread(() -> {
            orch.dialAuto();
            dialReturned.set(true);
        });
        t.start();
        waitFor(lifecycle::isBusy);
        orch.shutdown();
        t.join(10_000);
        assertTrue(dialReturned.get(), "queued dial call must return after shutdown");
        assertTrue(lifecycle.isIdle(), "lifecycle must end idle");
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for condition");
            }
            Thread.sleep(20);
        }
    }
}
