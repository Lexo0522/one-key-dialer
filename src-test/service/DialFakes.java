package service;

import model.DialCredentials;
import util.ConnectivityConfirm;
import util.ProbeOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared in-memory fakes for dial orchestrator tests. No real RAS, no network.
 */
final class DialFakes {
    private DialFakes() {
    }

    static final class FakePort implements DialPort {
        final Queue<DialResult> dialResults = new ConcurrentLinkedQueue<>();
        final List<String[]> receivedCredentials = new CopyOnWriteArrayList<>();
        final AtomicInteger connectCalls = new AtomicInteger();
        final AtomicInteger disconnectCalls = new AtomicInteger();
        volatile int disconnectResult = 0;
        volatile RuntimeException connectFailure;
        volatile boolean slowConnect;
        String connectionName = "pppoe_native_java";

        @Override public String connectionName() {
            return connectionName;
        }

        @Override public DialResult connect(DialCredentials credentials) {
            connectCalls.incrementAndGet();
            if (connectFailure != null) {
                throw connectFailure;
            }
            if (slowConnect) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Record what arrived at the RAS boundary (orchestrator clears afterwards).
            receivedCredentials.add(new String[]{credentials.username(),
                new String(credentials.copyPassword())});
            DialResult r = dialResults.poll();
            return r != null ? r : new DialResult(0, "Command completed successfully.");
        }

        @Override public int disconnect() {
            disconnectCalls.incrementAndGet();
            return disconnectResult;
        }
    }

    static final class FakeView implements DialView {
        boolean pretendOnEdt = false;
        boolean allowDial = true;
        /** Modeled UI fields — every capture hands out a fresh one-shot instance. */
        volatile String username = "user1";
        volatile char[] password = "pass1".toCharArray();
        final List<String> logs = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();
        final AtomicReference<String> lastPhase = new AtomicReference<>(null);
        final AtomicInteger phaseChanges = new AtomicInteger();
        final List<Boolean> connectionStates = new ArrayList<>();
        final AtomicInteger credentialCaptures = new AtomicInteger();

        @Override public boolean onEventDispatchThread() {
            return pretendOnEdt;
        }

        @Override public void runOnEdt(Runnable action) {
            action.run();
        }

        @Override public void runOnEdtAndWait(Runnable action) {
            action.run();
        }

        @Override public DialCredentials captureDialCredentials() {
            credentialCaptures.incrementAndGet();
            return new DialCredentials(username, password);
        }

        @Override public boolean validateDialInput(boolean interactive) {
            return allowDial;
        }

        @Override public void onDialPhase(String phase) {
            lastPhase.set(phase);
            phaseChanges.incrementAndGet();
        }

        @Override public void onConnectionState(boolean online) {
            connectionStates.add(online);
        }

        @Override public void notifyUser(String title, String message) {
            notifications.add(title + ": " + message);
        }

        @Override public void log(Level level, String message) {
            logs.add(level + ": " + message);
        }
    }

    static final class FakeEnv implements DialEnvironment {
        boolean online = false;
        long connectTimeMillis = 0L;
        long trafficBytes = 0L;
        String accountName = "账号A";
        ConnectivityConfirm.Config probeConfig = ConnectivityConfirm.Config.defaults();
        boolean disconnectOnNoInternet = false;
        final List<String[]> history = new CopyOnWriteArrayList<>();
        final AtomicInteger persistCalls = new AtomicInteger();

        @Override public boolean isOnline() {
            return online;
        }

        @Override public long connectTimeMillis() {
            return connectTimeMillis;
        }

        @Override public long sessionTrafficBytes() {
            return trafficBytes;
        }

        @Override public String currentAccountName() {
            return accountName;
        }

        @Override public ConnectivityConfirm.Config probeConfig() {
            return probeConfig;
        }

        @Override public boolean disconnectOnNoInternet() {
            return disconnectOnNoInternet;
        }

        @Override public void addHistory(String operation, String account, String result,
                                         String duration, String traffic) {
            history.add(new String[]{operation, account, result, duration, traffic});
        }

        @Override public void persistAfterSuccess() {
            persistCalls.incrementAndGet();
        }

        @Override public void recordProbeOutcome(ProbeOutcome outcome) {
            // captured via history/logs in these tests
        }
    }
}
