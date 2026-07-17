package service;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Periodic connectivity watch + auto dial on {@link BackgroundExecutor}.
 * Automated dial must gate on {@link model.DialLifecycle#isBusy()}, not only isDialing.
 * {@link #stop()} never joins worker threads (EDT-safe).
 */
public class AutoReconnectService {
    private final BooleanSupplier isBusy;
    private final BooleanSupplier checkNetworkStatus;
    private final Runnable performDialSync;
    private final Runnable onNetworkRecovered;
    private final Runnable onNetworkLost;
    private final Consumer<String> infoLogger;
    private final Consumer<String> warnLogger;
    private final Consumer<String> errorLogger;
    private final BackgroundExecutor executor;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicInteger intervalSeconds = new AtomicInteger(30);
    private final AtomicBoolean needImmediateDial = new AtomicBoolean(false);
    private volatile boolean lastOnline = false;
    private volatile boolean hasSample = false;
    private volatile ScheduledFuture<?> tickFuture;

    public AutoReconnectService(BooleanSupplier isBusy,
                                BooleanSupplier checkNetworkStatus,
                                Runnable performDialSync,
                                Runnable onNetworkRecovered,
                                Runnable onNetworkLost,
                                Consumer<String> infoLogger,
                                Consumer<String> warnLogger,
                                Consumer<String> errorLogger) {
        this(isBusy, checkNetworkStatus, performDialSync, onNetworkRecovered, onNetworkLost,
            infoLogger, warnLogger, errorLogger, null);
    }

    public AutoReconnectService(BooleanSupplier isBusy,
                                BooleanSupplier checkNetworkStatus,
                                Runnable performDialSync,
                                Runnable onNetworkRecovered,
                                Runnable onNetworkLost,
                                Consumer<String> infoLogger,
                                Consumer<String> warnLogger,
                                Consumer<String> errorLogger,
                                BackgroundExecutor executor) {
        this.isBusy = isBusy;
        this.checkNetworkStatus = checkNetworkStatus;
        this.performDialSync = performDialSync;
        this.onNetworkRecovered = onNetworkRecovered;
        this.onNetworkLost = onNetworkLost;
        this.infoLogger = infoLogger;
        this.warnLogger = warnLogger;
        this.errorLogger = errorLogger;
        this.executor = executor;
    }

    public static int clampIntervalSeconds(int intervalSeconds) {
        return Math.max(5, intervalSeconds);
    }

    public static boolean shouldAttemptReconnectDial(boolean online, boolean busy) {
        return !online && !busy;
    }

    public boolean isRunning() {
        return enabled.get() && tickFuture != null && !tickFuture.isCancelled();
    }

    public synchronized void start(int intervalSeconds, boolean dialImmediately) {
        if (executor == null) {
            throw new IllegalStateException("BackgroundExecutor required");
        }
        if (isRunning()) return;
        stopInternal(false);
        int safe = clampIntervalSeconds(intervalSeconds);
        this.intervalSeconds.set(safe);
        needImmediateDial.set(dialImmediately);
        hasSample = false;
        lastOnline = false;
        enabled.set(true);
        infoLogger.accept("自动重连已开启，间隔: " + safe + "秒");
        // first tick ASAP; subsequent delay driven by reschedule after each tick
        tickFuture = executor.schedule(this::tickSafe, dialImmediately ? 0L : 500L);
    }

    public synchronized void stop() {
        stopInternal(true);
    }

    private void stopInternal(boolean logStopped) {
        enabled.set(false);
        BackgroundExecutor.cancel(tickFuture);
        tickFuture = null;
        if (logStopped) {
            // deferred log from last tick exit path; emit immediately for UI clarity
            warnLogger.accept("自动重连已停止");
        }
    }

    private void tickSafe() {
        if (!enabled.get()) return;
        long nextDelaySec = intervalSeconds.get();
        try {
            boolean online = checkNetworkStatus.getAsBoolean();
            boolean busy = isBusy.getAsBoolean();

            if (hasSample) {
                if (online && !lastOnline) {
                    onNetworkRecovered.run();
                } else if (!online && lastOnline) {
                    if (!busy) {
                        onNetworkLost.run();
                    }
                }
            }
            lastOnline = online;
            hasSample = true;

            if (shouldAttemptReconnectDial(online, busy)) {
                if (needImmediateDial.getAndSet(false)) {
                    infoLogger.accept("启动后检测到未连接，立即尝试拨号...");
                } else {
                    warnLogger.accept("检测到断网，自动重连...");
                }
                performDialSync.run();
                nextDelaySec = 5;
            } else {
                needImmediateDial.set(false);
            }
        } catch (Exception e) {
            needImmediateDial.set(false);
            errorLogger.accept("监控异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            nextDelaySec = 5;
        } finally {
            if (enabled.get() && executor != null && !executor.isShutdown()) {
                long delayMs = TimeUnit.SECONDS.toMillis(Math.max(1, nextDelaySec));
                tickFuture = executor.schedule(this::tickSafe, delayMs);
            }
        }
    }
}
