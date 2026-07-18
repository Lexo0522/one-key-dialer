package service;

import model.DialLifecycle;
import model.DialSnapshot;
import util.ConnectivityConfirm;
import util.FormatUtil;
import util.ProbeOutcome;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Single-threaded RAS dial/disconnect queue + post-dial connectivity handling.
 * Automated dial/disconnect must gate on {@link DialLifecycle#isBusy()} / CAS.
 */
public final class DialOrchestrator {
    public static final String OP_USER_DIAL = "拨号";
    public static final String OP_AUTO_DIAL = "自动拨号";
    public static final String OP_USER_DISCONNECT = "断开";
    public static final String OP_SCHEDULE_DISCONNECT = "定时断开";

    /**
     * UI / app callbacks. Background dial prefers {@link #updateCredentials} cache;
     * empty cache falls back to EDT snapshot via {@link Host#runOnEdtAndWait}.
     */
    public interface Host {
        DialLifecycle lifecycle();

        DialService dialService();

        String connectionName();

        String activeConnectionName();

        BooleanSupplier isOnline();

        LongSupplier connectTimeMillis();

        /** Session download+upload bytes for history traffic column. */
        LongSupplier sessionTrafficBytes();

        Supplier<String> currentAccountName();

        AtomicLong totalDialCount();

        AtomicLong successDialCount();

        boolean validateBeforeDialInteractive();

        boolean validateBeforeDialQuiet();

        DialSnapshot captureSnapshotFromUi();

        void saveCurrentAccount();

        void updateStatus(boolean online);

        void setDialControlsEnabled(boolean enabled);

        /**
         * Optional UI progress for the main dial button.
         * {@code "dialing"} / {@code "disconnecting"} while busy; {@code null} to clear.
         */
        default void setDialProgress(String phase) {
        }

        void logInfo(String message);

        void logSuccess(String message);

        void logWarning(String message);

        void logError(String message);

        void notifyUser(String title, String message);

        void addHistory(String operation, String account, String result, String duration, String traffic);

        void saveSettingsAfterSuccess();

        boolean isEventDispatchThread();

        void runOnEdtAndWait(Runnable action) throws Exception;
    }

    private final Host host;
    private final Object dialExecutorLock = new Object();
    private volatile ExecutorService dialExecutor;
    private volatile BooleanSupplier postDialInternetConfirm;
    private volatile Supplier<ConnectivityConfirm.Config> probeConfigSupplier =
        ConnectivityConfirm.Config::defaults;
    /** When true, drop PPP if probe fails after RAS success. Default false. */
    private volatile boolean disconnectOnNoInternet = false;
    private volatile Consumer<ProbeOutcome> onProbeOutcome;

    private final Object credentialLock = new Object();
    private volatile String cachedUsername = "";
    private char[] cachedPassword = new char[0];

    public DialOrchestrator(Host host) {
        this.host = host;
        this.postDialInternetConfirm = new DefaultPostDialConfirm();
    }

    /** RasDial pool created on first dial/disconnect (saves one idle thread at cold start). */
    private ExecutorService dialExecutor() {
        ExecutorService exec = dialExecutor;
        if (exec != null && !exec.isShutdown()) {
            return exec;
        }
        synchronized (dialExecutorLock) {
            exec = dialExecutor;
            if (exec == null || exec.isShutdown()) {
                ThreadFactory tf = r -> {
                    Thread t = new Thread(r, "RasDial");
                    t.setDaemon(true);
                    return t;
                };
                exec = Executors.newSingleThreadExecutor(tf);
                dialExecutor = exec;
            }
            return exec;
        }
    }

    /** Package / test hook to avoid live network probes. */
    void setPostDialInternetConfirm(BooleanSupplier confirm) {
        this.postDialInternetConfirm = confirm != null
            ? confirm
            : new DefaultPostDialConfirm();
    }

    /** Live settings-driven probe config (host / mode / http). */
    public void setProbeConfigSupplier(Supplier<ConnectivityConfirm.Config> supplier) {
        this.probeConfigSupplier = supplier != null ? supplier : ConnectivityConfirm.Config::defaults;
        // Keep production default unless tests overrode with a custom supplier
        if (postDialInternetConfirm instanceof DefaultPostDialConfirm || postDialInternetConfirm == null) {
            this.postDialInternetConfirm = new DefaultPostDialConfirm();
        }
    }

    /** Optional listener for last post-dial probe result (Diag / RuntimeSettings). */
    public void setOnProbeOutcome(Consumer<ProbeOutcome> listener) {
        this.onProbeOutcome = listener;
    }

    public void setDisconnectOnNoInternet(boolean enabled) {
        this.disconnectOnNoInternet = enabled;
    }

    public boolean isDisconnectOnNoInternet() {
        return disconnectOnNoInternet;
    }

    /** Call from EDT when account fields change. */
    public void updateCredentials(String username, char[] password) {
        synchronized (credentialLock) {
            cachedUsername = username != null ? username.trim() : "";
            Arrays.fill(cachedPassword, '\0');
            cachedPassword = password != null ? Arrays.copyOf(password, password.length) : new char[0];
        }
    }

    public void clearCredentials() {
        updateCredentials("", new char[0]);
    }

    public DialSnapshot snapshotFromCache() {
        synchronized (credentialLock) {
            return new DialSnapshot(host.connectionName(), cachedUsername, cachedPassword);
        }
    }

    public boolean hasCachedCredentials() {
        synchronized (credentialLock) {
            if (cachedUsername.isEmpty()) return false;
            for (char c : cachedPassword) {
                if (c != '\0' && !Character.isWhitespace(c)) return true;
            }
            return false;
        }
    }

    public void dialAsyncUser() {
        if (host.lifecycle().isBusy()) {
            host.logWarning("正在处理连接操作...");
            return;
        }
        if (!host.validateBeforeDialInteractive()) return;

        host.saveCurrentAccount();
        DialSnapshot snapshot = host.captureSnapshotFromUi();
        refreshCacheFromSnapshot(snapshot);
        host.totalDialCount().incrementAndGet();

        dialExecutor().execute(() -> runDial(snapshot, OP_USER_DIAL, true, true));
    }

    public void disconnectAsyncUser() {
        if (host.lifecycle().isBusy()) {
            host.logWarning("正在处理连接操作...");
            return;
        }
        host.logInfo("正在断开网络...");
        dialExecutor().execute(this::runDisconnectUser);
    }

    /** Auto / schedule dial — serialized on RasDial executor. */
    public void dialSyncAuto() {
        enqueueAndWait(() -> runDialSyncBody(OP_AUTO_DIAL));
    }

    public void disconnectSyncScheduled() {
        enqueueAndWait(this::runDisconnectScheduled);
    }

    private void enqueueAndWait(Runnable work) {
        if (Thread.currentThread().getName().startsWith("RasDial")) {
            work.run();
            return;
        }
        try {
            dialExecutor().submit(work).get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            host.logError("拨号队列执行失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void runDialSyncBody(String operation) {
        if (host.lifecycle().isBusy()) return;

        DialSnapshot snapshot = takeSnapshotForBackground();
        if (snapshot == null) return;
        if (!host.lifecycle().tryBeginDial()) {
            snapshot.clear();
            return;
        }
        host.totalDialCount().incrementAndGet();
        try {
            DialService.DialResult result = host.dialService().dial(snapshot);
            handleDialResult(result, operation, false);
        } catch (Exception e) {
            host.logError("自动拨号异常: " + e.getMessage());
        } finally {
            host.lifecycle().end();
        }
    }

    private DialSnapshot takeSnapshotForBackground() {
        if (hasCachedCredentials()) {
            if (host.isOnline().getAsBoolean()) {
                host.logInfo("当前已连接，无需重复拨号");
                return null;
            }
            return snapshotFromCache();
        }
        final DialSnapshot[] box = new DialSnapshot[1];
        try {
            if (host.isEventDispatchThread()) {
                if (!host.validateBeforeDialQuiet()) return null;
                box[0] = host.captureSnapshotFromUi();
                if (box[0] != null) refreshCacheFromSnapshot(box[0]);
            } else {
                host.runOnEdtAndWait(() -> {
                    if (!host.validateBeforeDialQuiet()) return;
                    box[0] = host.captureSnapshotFromUi();
                    if (box[0] != null) refreshCacheFromSnapshot(box[0]);
                });
            }
        } catch (Exception e) {
            host.logError("自动拨号快照失败: " + e.getMessage());
            return null;
        }
        return box[0];
    }

    private void refreshCacheFromSnapshot(DialSnapshot snapshot) {
        if (snapshot == null) return;
        char[] pw = snapshot.copyPasswordChars();
        try {
            updateCredentials(snapshot.username, pw);
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    private void runDial(DialSnapshot snapshot, String operation, boolean saveAfterSuccess, boolean toggleButtons) {
        if (!host.lifecycle().tryBeginDial()) {
            snapshot.clear();
            return;
        }
        if (toggleButtons) {
            host.setDialControlsEnabled(false);
            host.setDialProgress("dialing");
        }
        try {
            DialService.DialResult result = host.dialService().dial(snapshot);
            handleDialResult(result, operation, saveAfterSuccess);
        } catch (Exception e) {
            host.logError("拨号异常: " + e.getMessage());
        } finally {
            host.lifecycle().end();
            if (toggleButtons) {
                host.setDialProgress(null);
                host.setDialControlsEnabled(true);
            }
        }
    }

    private void runDisconnectUser() {
        if (!host.lifecycle().tryBeginDisconnect()) return;
        host.setDialControlsEnabled(false);
        host.setDialProgress("disconnecting");
        try {
            int code = host.dialService().disconnect(host.activeConnectionName());
            String duration = "--";
            String traffic = "--";
            long conn = host.connectTimeMillis().getAsLong();
            if (conn > 0) {
                long sec = (System.currentTimeMillis() - conn) / 1000;
                duration = String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
                traffic = FormatUtil.formatBytes(host.sessionTrafficBytes().getAsLong());
            }
            host.updateStatus(false);
            host.addHistory(OP_USER_DISCONNECT, host.currentAccountName().get(),
                code == 0 ? "成功" : "完成", duration, traffic);
            if (code == 0) {
                host.logSuccess("网络已断开");
            } else {
                host.logWarning("断开命令执行完成");
            }
            host.notifyUser("已断开", "网络连接已断开");
        } catch (Exception e) {
            host.logError("断开异常: " + e.getMessage());
        } finally {
            host.lifecycle().end();
            host.setDialProgress(null);
            host.setDialControlsEnabled(true);
        }
    }

    private void runDisconnectScheduled() {
        if (!host.lifecycle().tryBeginDisconnect()) {
            host.logWarning("定时断开跳过：当前有其它连接操作");
            return;
        }
        try {
            int code = host.dialService().disconnectSync(host.activeConnectionName());
            String traffic = FormatUtil.formatBytes(host.sessionTrafficBytes().getAsLong());
            if (code == 0) {
                host.updateStatus(false);
                host.addHistory(OP_SCHEDULE_DISCONNECT, host.currentAccountName().get(), "成功", "--", traffic);
            } else {
                host.logWarning("定时断开命令执行失败，退出码: " + code);
                host.addHistory(OP_SCHEDULE_DISCONNECT, host.currentAccountName().get(), "失败", "--", traffic);
            }
        } catch (Exception e) {
            host.logWarning("定时断开失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            host.lifecycle().end();
        }
    }

    void handleDialResult(DialService.DialResult result, String operation, boolean saveAfterSuccess) {
        if (result.isSuccess()) {
            host.logInfo("RAS 已连接，正在确认外网连通性...");
            ConnectivityConfirm.Config cfg = probeConfigSupplier != null
                ? probeConfigSupplier.get() : ConnectivityConfirm.Config.defaults();
            if (cfg == null) cfg = ConnectivityConfirm.Config.defaults();

            ProbeOutcome outcome;
            long t0 = System.nanoTime();
            boolean netOk;
            // Tests inject BooleanSupplier via setPostDialInternetConfirm — honor that first.
            BooleanSupplier injected = postDialInternetConfirm;
            if (injected != null && !(injected instanceof DefaultPostDialConfirm)) {
                try {
                    netOk = injected.getAsBoolean();
                } catch (Exception e) {
                    netOk = false;
                    host.logWarning("外网探测异常: " + e.getMessage());
                }
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                outcome = new ProbeOutcome(netOk, ms, cfg.mode, cfg.host, cfg.httpUrl,
                    cfg.attempts, "post-dial", System.currentTimeMillis());
            } else {
                try {
                    outcome = ConnectivityConfirm.confirmDetailed(cfg, "post-dial");
                } catch (Exception e) {
                    outcome = new ProbeOutcome(false, 0, cfg.mode, cfg.host, cfg.httpUrl,
                        cfg.attempts, "post-dial", System.currentTimeMillis());
                    host.logWarning("外网探测异常: " + e.getMessage());
                }
                netOk = outcome.ok;
            }
            host.logInfo("外网探测: " + outcome.shortLine());
            String status = ConnectivityConfirm.historyStatus(true, netOk, result.code);
            if (netOk) {
                host.updateStatus(true);
                host.successDialCount().incrementAndGet();
                host.logSuccess("拨号成功！");
                host.notifyUser("连接成功", "已连接到校园网");
                host.addHistory(operation, host.currentAccountName().get(), status, "--", "--");
                if (saveAfterSuccess) host.saveSettingsAfterSuccess();
            } else {
                host.updateStatus(false);
                host.logWarning("RAS 已连接但外网不可达（" + ConnectivityConfirm.HISTORY_STATUS_RAS_NO_INTERNET
                    + "; " + outcome.shortLine() + "）");
                if (disconnectOnNoInternet) {
                    try {
                        int code = host.dialService().disconnectSync(host.activeConnectionName());
                        if (code == 0) {
                            host.logWarning("已按策略断开无外网的 PPP 连接");
                            host.notifyUser("已拨通但无外网", "外网不可达，已断开宽带（可在设置 probe.disconnect.on.no.internet 关闭）");
                        } else {
                            host.logWarning("策略断开失败，退出码: " + code + "（PPP 可能仍保持）");
                            host.notifyUser("已拨通但无外网", "外网不可达；自动断开失败，请手动断开");
                        }
                    } catch (Exception e) {
                        host.logWarning("策略断开异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        host.notifyUser("已拨通但无外网", "外网不可达；自动断开异常，请手动断开");
                    }
                    host.addHistory(operation, host.currentAccountName().get(),
                        status + "/已断开", "--", "--");
                } else {
                    host.notifyUser("已拨通但无外网", "宽带已连接，暂无法访问外网，将按自动重连策略重试");
                    host.addHistory(operation, host.currentAccountName().get(), status, "--", "--");
                }
            }
            if (onProbeOutcome != null) {
                try {
                    onProbeOutcome.accept(outcome);
                } catch (Exception ignored) {
                }
            }
        } else {
            host.updateStatus(false);
            String detail = DialService.describeFailure(result);
            host.logError("拨号失败！错误代码: " + result.code);
            host.logWarning("  " + detail);
            host.notifyUser("连接失败", detail);
            host.addHistory(operation, host.currentAccountName().get(),
                ConnectivityConfirm.historyStatus(false, false, result.code), "--", "--");
        }
    }

    /** Marker for production default confirm supplier. */
    private final class DefaultPostDialConfirm implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            ConnectivityConfirm.Config cfg = probeConfigSupplier != null
                ? probeConfigSupplier.get() : ConnectivityConfirm.Config.defaults();
            return ConnectivityConfirm.confirm(cfg);
        }
    }

    public void shutdown() {
        ExecutorService exec;
        synchronized (dialExecutorLock) {
            exec = dialExecutor;
            dialExecutor = null;
        }
        if (exec != null) {
            exec.shutdownNow();
            try {
                exec.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        clearCredentials();
    }
}
