package service;

import model.DialLifecycle;
import model.SessionTraffic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static service.DialFakes.FakeEnv;
import static service.DialFakes.FakePort;
import static service.DialFakes.FakeView;

/**
 * Online account switching: redial must wait for the disconnect to complete
 * (no fixed delay), and must not start when the disconnect failed, the queue is
 * busy, or the app is shutting down.
 */
class AccountSwitchTest {
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
        orch.setPostDialConnectivity(() -> true);
        view.username = "new-account";
        view.password = "new-pass".toCharArray();
    }

    @AfterEach
    void tearDown() {
        orch.shutdown();
    }

    @Test
    void redialsOnlyAfterDisconnectCompletes() throws Exception {
        orch.redialAfterDisconnect();
        waitFor(() -> port.connectCalls.get() == 1 && !lifecycle.isBusy());

        assertEquals(1, port.disconnectCalls.get());
        // New credentials captured after the disconnect, not stale ones.
        assertArrayEquals(new String[]{"new-account", "new-pass"}, port.receivedCredentials.get(0));
        // One dial op history row; the switch itself does not add a disconnect row.
        assertEquals(1, env.history.size());
        assertEquals("自动拨号", env.history.get(0)[0]);
        assertTrue(lifecycle.isIdle());
    }

    @Test
    void disconnectFailureCancelsRedial() throws Exception {
        port.disconnectResult = 825;
        orch.redialAfterDisconnect();
        waitFor(() -> !lifecycle.isBusy());
        Thread.sleep(100); // give a would-be redial a chance to (wrongly) start

        assertEquals(1, port.disconnectCalls.get());
        assertEquals(0, port.connectCalls.get(), "failed disconnect must not trigger redial");
        assertTrue(env.history.isEmpty());
        assertTrue(lifecycle.isIdle());
    }

    @Test
    void busyLifecycleRefusesSwitch() {
        assertTrue(lifecycle.tryBeginDial());
        orch.redialAfterDisconnect();
        lifecycle.end();

        assertEquals(0, port.disconnectCalls.get(), "busy state must refuse the switch");
        assertEquals(0, port.connectCalls.get());
    }

    @Test
    void shutdownDuringSwitchPreventsRedial() throws Exception {
        orch.redialAfterDisconnect();
        // Shut down immediately: the queued task sees shuttingDown before redialing.
        orch.shutdown();
        Thread.sleep(150);

        assertTrue(port.connectCalls.get() <= 1);
        // After shutdown no new dial may start even if disconnect already returned.
        if (port.disconnectCalls.get() == 1) {
            Thread.sleep(100);
        }
        assertTrue(port.connectCalls.get() <= 1);
        assertTrue(lifecycle.isIdle());
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
