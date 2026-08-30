package service;

import model.DialCredentials;
import model.DialLifecycle;
import model.SessionTraffic;
import util.ConnectivityConfirm;
import util.FormatUtil;
import util.ProbeOutcome;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Single-threaded RAS dial/disconnect queue. Owns precheck gating, the connection
 * lifecycle, post-dial connectivity confirmation, redial orchestration, history,
 * dial statistics, and notification order. Credentials are one-shot
 * {@link DialCredentials} — captured per attempt and zeroed when the attempt ends;
 * nothing here caches passwords.
 * <p>
 * Automated dial/disconnect must gate on {@link DialLifecycle#isBusy()} / CAS.
 */
public final class DialOrchestrator {
    public static final String OP_USER_DIAL = "拨号";
    public static final String OP_AUTO_DIAL = "自动拨号";
    public static final String OP_USER_DISCONNECT = "断开";
    public static final String OP_SCHEDULE_DISCONNECT = "定时断开";

    private static final String PHASE_DIALING = "dialing";
    private static final String PHASE_DISCONNECTING = "disconnecting";

    private final DialPort port;
    private final DialView view;
    private final DialEnvironment env;
    private final DialLifecycle lifecycle;
    private final SessionTraffic stats;

    private final Object dialExecutorLock = new Object();
    private volatile ExecutorService dialExecutor;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** Package-visible hook so tests can replace the live connectivity probe. */
    volatile BooleanSupplier postDialConnectivityOverride;

    void setPostDialConnectivity(BooleanSupplier override) {
        this.postDialConnectivityOverride = override;
    }

    public DialOrchestrator(DialPort port, DialView view, DialEnvironment env,
                            DialLifecycle lifecycle, SessionTraffic stats) {
        this.port = port;
        this.view = view;
        this.env = env;
        this.lifecycle = lifecycle;
        this.stats = stats;
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

    // ---------- user entries (EDT) ----------

    /** User dial from the main button / tray. Must run on the EDT. */
    public void dialAsyncUser() {
        if (lifecycle.isBusy()) {
            view.log(DialView.Level.WARNING, "正在处理连接操作...");
            return;
        }
        if (!view.validateDialInput(true)) {
            return;
        }
        DialCredentials credentials = view.captureDialCredentials();
        dialExecutor().execute(() -> runDial(credentials, OP_USER_DIAL, true, true));
    }

    /** User disconnect from the main button / tray. Must run on the EDT. */
    public void disconnectAsyncUser() {
        if (lifecycle.isBusy()) {
            view.log(DialView.Level.WARNING, "正在处理连接操作...");
            return;
        }
        view.log(DialView.Level.INFO, "正在断开网络...");
        dialExecutor().execute(this::runDisconnectUser);
    }

    // ---------- automated entries ----------

    /**
     * Auto / schedule dial — queued on the RasDial executor. Fire-and-forget:
     * callers run on the shared scheduler (monitor/schedule ticks) and must never
     * block on a dial that can take up to two minutes. Reconnect/schedule loops
     * gate their next attempt on {@link DialLifecycle#isBusy()}.
     */
    public void dialAuto() {
        enqueueDialWork(() -> {
            if (shuttingDown.get()) return;
            if (env.isOnline()) {
                view.log(DialView.Level.INFO, "当前已连接，无需重复拨号");
                return;
            }
            if (!lifecycle.tryBeginDial()) return;
            DialCredentials credentials = null;
            try {
                credentials = captureForBackground();
                if (credentials == null) return;
                stats.totalDialCount().incrementAndGet();
                DialPort.DialResult result = port.connect(credentials);
                handleDialResult(result, OP_AUTO_DIAL, false);
            } catch (Exception e) {
                view.log(DialView.Level.ERROR, "自动拨号异常: " + e.getMessage()
                    + "\n" + util.Throwables.stackTrace(e));
            } finally {
                if (credentials != null) credentials.clear();
                lifecycle.end();
            }
        });
    }

    public void disconnectScheduled() {
        enqueueDialWork(this::runDisconnectScheduled);
    }

    /**
     * Online account switch: disconnect, wait for its completion, then redial with
     * the freshly selected account. No fixed delay. Redial is skipped when the
     * disconnect fails, another operation is busy, or the app is shutting down.
     */
    public void redialAfterDisconnect() {
        if (lifecycle.isBusy()) {
            view.log(DialView.Level.WARNING, "正在处理连接操作，暂无法切换账号");
            return;
        }
        dialExecutor().execute(() -> {
            if (shuttingDown.get()) return;
            if (!lifecycle.tryBeginDisconnect()) {
                view.log(DialView.Level.WARNING, "正在处理连接操作，暂无法切换账号");
                return;
            }
            String phase = PHASE_DISCONNECTING;
            view.onDialPhase(phase);
            int code;
            try {
                code = port.disconnect();
            } catch (Exception e) {
                view.log(DialView.Level.ERROR, "断开异常: " + e.getMessage()
                    + "\n" + util.Throwables.stackTrace(e));
                code = -1;
            } finally {
                lifecycle.end();
                view.onDialPhase(null);
            }
            if (code != 0) {
                view.log(DialView.Level.WARNING,
                    "断开失败（退出码 " + code + "），已取消账号切换重拨");
                return;
            }
            if (shuttingDown.get()) return;
            view.onConnectionState(false);
            view.log(DialView.Level.INFO, "已断开旧连接，正在使用新账号重拨...");

            if (!lifecycle.tryBeginDial()) return;
            DialCredentials credentials = null;
            try {
                view.onDialPhase(PHASE_DIALING);
                credentials = captureForBackground();
                if (credentials == null) return;
                stats.totalDialCount().incrementAndGet();
                DialPort.DialResult result = port.connect(credentials);
                handleDialResult(result, OP_AUTO_DIAL, false);
            } catch (Exception e) {
                view.log(DialView.Level.ERROR, "切换重拨异常: " + e.getMessage());
            } finally {
                if (credentials != null) credentials.clear();
                lifecycle.end();
                view.onDialPhase(null);
            }
        });
    }

    public void shutdown() {
        shuttingDown.set(true);
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
    }

    // ---------- internals ----------

    /**
     * Queue dial/disconnect work on the RasDial executor without waiting. Blocking
     * on completion would pin a shared scheduler thread for the whole dial timeout;
     * nothing here needs the result synchronously.
     */
    private void enqueueDialWork(Runnable work) {
        if (Thread.currentThread().getName().startsWith("RasDial")) {
            work.run();
            return;
        }
        if (shuttingDown.get()) return;
        try {
            dialExecutor().execute(work);
        } catch (RejectedExecutionException e) {
            view.log(DialView.Level.WARNING, "拨号队列已关闭，忽略本次自动操作");
        }
    }

    /**
     * Quiet precheck + credential capture for background dials. Always marshals to
     * the EDT so UI reads stay on the right thread. The returned credentials are
     * owned by the caller and must be cleared.
     */
    private DialCredentials captureForBackground() {
        final DialCredentials[] box = new DialCredentials[1];
        Runnable capture = () -> {
            if (!view.validateDialInput(false)) return;
            box[0] = view.captureDialCredentials();
        };
        try {
            if (view.onEventDispatchThread()) {
                capture.run();
            } else {
                view.runOnEdtAndWait(capture);
            }
        } catch (Exception e) {
            view.log(DialView.Level.ERROR, "拨号凭据获取失败: " + e.getMessage());
            return null;
        }
        return box[0];
    }

    private void runDial(DialCredentials credentials, String operation,
                         boolean saveAfterSuccess, boolean toggleButtons) {
        if (shuttingDown.get()) {
            credentials.clear();
            return;
        }
        if (!lifecycle.tryBeginDial()) {
            credentials.clear();
            view.log(DialView.Level.WARNING, "正在处理连接操作...");
            return;
        }
        stats.totalDialCount().incrementAndGet();
        if (toggleButtons) {
            view.onDialPhase(PHASE_DIALING);
        }
        try {
            DialPort.DialResult result = port.connect(credentials);
            handleDialResult(result, operation, saveAfterSuccess);
        } catch (Exception e) {
            view.log(DialView.Level.ERROR, "拨号异常: " + e.getMessage()
                + "\n" + util.Throwables.stackTrace(e));
        } finally {
            credentials.clear();
            lifecycle.end();
            if (toggleButtons) {
                view.onDialPhase(null);
            }
        }
    }

    private void runDisconnectUser() {
        if (!lifecycle.tryBeginDisconnect()) return;
        view.onDialPhase(PHASE_DISCONNECTING);
        int code = -1;
        try {
            code = port.disconnect();
            String duration = "--";
            String traffic = "--";
            long conn = env.connectTimeMillis();
            if (conn > 0) {
                long sec = (System.currentTimeMillis() - conn) / 1000;
                duration = String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
                traffic = FormatUtil.formatBytes(env.sessionTrafficBytes());
            }
            view.onConnectionState(false);
            env.addHistory(OP_USER_DISCONNECT, env.currentAccountName(),
                code == 0 ? model.DialOutcome.SUCCESS.text() : model.DialOutcome.DONE.text(),
                duration, traffic);
            if (code == 0) {
                view.log(DialView.Level.SUCCESS, "网络已断开");
            } else {
                view.log(DialView.Level.WARNING, "断开命令执行完成");
            }
            view.notifyUser("已断开", "网络连接已断开");
        } catch (Exception e) {
            view.log(DialView.Level.ERROR, "断开异常: " + e.getMessage()
                + "\n" + util.Throwables.stackTrace(e));
        } finally {
            lifecycle.end();
            view.onDialPhase(null);
        }
    }

    private void runDisconnectScheduled() {
        if (shuttingDown.get()) return;
        if (!lifecycle.tryBeginDisconnect()) {
            view.log(DialView.Level.WARNING, "定时断开跳过：当前有其它连接操作");
            return;
        }
        try {
            int code = port.disconnect();
            String traffic = FormatUtil.formatBytes(env.sessionTrafficBytes());
            if (code == 0) {
                view.onConnectionState(false);
                env.addHistory(OP_SCHEDULE_DISCONNECT, env.currentAccountName(),
                    model.DialOutcome.SUCCESS.text(), "--", traffic);
            } else {
                view.log(DialView.Level.WARNING, "定时断开命令执行失败，退出码: " + code);
                env.addHistory(OP_SCHEDULE_DISCONNECT, env.currentAccountName(),
                    model.DialOutcome.FAILURE.text(), "--", traffic);
            }
        } catch (Exception e) {
            view.log(DialView.Level.WARNING, "定时断开失败: " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + "\n" + util.Throwables.stackTrace(e));
        } finally {
            lifecycle.end();
        }
    }

    /** Notification order: status → counters → log → notify → history → persist. */
    private void handleDialResult(DialPort.DialResult result, String operation,
                                  boolean saveAfterSuccess) {
        if (result.isSuccess()) {
            view.log(DialView.Level.INFO, "RAS 已连接，正在确认外网连通性...");
            boolean netOk;
            ProbeOutcome outcome;
            ConnectivityConfirm.Config cfg = env.probeConfig();
            long t0 = System.nanoTime();
            BooleanSupplier override = postDialConnectivityOverride;
            if (override != null) {
                try {
                    netOk = override.getAsBoolean();
                } catch (Exception e) {
                    netOk = false;
                    view.log(DialView.Level.WARNING, "外网探测异常: " + e.getMessage());
                }
                outcome = new ProbeOutcome(netOk, (System.nanoTime() - t0) / 1_000_000L,
                    cfg.mode, cfg.host, cfg.httpUrl, cfg.attempts, "post-dial",
                    System.currentTimeMillis());
            } else {
                try {
                    netOk = ConnectivityConfirm.confirm(cfg);
                    outcome = new ProbeOutcome(netOk, (System.nanoTime() - t0) / 1_000_000L,
                        cfg.mode, cfg.host, cfg.httpUrl, cfg.attempts, "post-dial",
                        System.currentTimeMillis());
                } catch (Exception e) {
                    netOk = false;
                    outcome = new ProbeOutcome(false, (System.nanoTime() - t0) / 1_000_000L,
                        cfg.mode, cfg.host, cfg.httpUrl, cfg.attempts, "post-dial",
                        System.currentTimeMillis());
                    view.log(DialView.Level.WARNING, "外网探测异常: " + e.getMessage());
                }
            }
            view.log(DialView.Level.INFO, "外网探测: " + outcome.shortLine());
            env.recordProbeOutcome(outcome);

            if (netOk) {
                view.onConnectionState(true);
                stats.successDialCount().incrementAndGet();
                view.log(DialView.Level.SUCCESS, "拨号成功！");
                view.notifyUser("连接成功", "已连接到校园网");
                env.addHistory(operation, env.currentAccountName(),
                    model.DialOutcome.SUCCESS.text(), "--", "--");
                if (saveAfterSuccess) env.persistAfterSuccess();
            } else {
                view.onConnectionState(false);
                view.log(DialView.Level.WARNING, "RAS 已连接但外网不可达（"
                    + model.DialOutcome.RAS_NO_INTERNET.text()
                    + "; " + outcome.shortLine() + "）");
                boolean disconnected = false;
                if (env.disconnectOnNoInternet()) {
                    try {
                        int code = port.disconnect();
                        if (code == 0) {
                            disconnected = true;
                            view.log(DialView.Level.WARNING, "已按策略断开无外网的 PPP 连接");
                            view.notifyUser("已拨通但无外网",
                                "外网不可达，已断开宽带（可在设置中关闭该策略）");
                        } else {
                            view.log(DialView.Level.WARNING,
                                "策略断开失败，退出码: " + code + "（PPP 可能仍保持）");
                            view.notifyUser("已拨通但无外网", "外网不可达；自动断开失败，请手动断开");
                        }
                    } catch (Exception e) {
                        view.log(DialView.Level.WARNING, "策略断开异常: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                        view.notifyUser("已拨通但无外网", "外网不可达；自动断开异常，请手动断开");
                    }
                } else {
                    view.notifyUser("已拨通但无外网", "宽带已连接，暂无法访问外网，将按自动重连策略重试");
                }
                env.addHistory(operation, env.currentAccountName(),
                    model.DialOutcome.RAS_NO_INTERNET.text() + (disconnected ? "/已断开" : ""),
                    "--", "--");
            }
        } else {
            view.onConnectionState(false);
            String detail = WindowsRasModule.describeFailure(new DialPort.DialResult(
                result.code, result.output));
            view.log(DialView.Level.ERROR, "拨号失败！错误代码: " + result.code);
            view.log(DialView.Level.WARNING, "  " + detail);
            view.notifyUser("连接失败", detail);
            env.addHistory(operation, env.currentAccountName(),
                model.DialOutcome.FAILURE.text() + ":" + result.code, "--", "--");
        }
    }
}
