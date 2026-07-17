package service;

import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Traffic / uptime / tray tooltip ticks on {@link BackgroundExecutor}.
 * {@link #stop()} never joins (EDT-safe).
 */
public class NetworkMonitorService {
    public static class SpeedSample {
        public final long downBytes;
        public final long upBytes;

        public SpeedSample(long downBytes, long upBytes) {
            this.downBytes = downBytes;
            this.upBytes = upBytes;
        }
    }

    private final BooleanSupplier isOnline;
    private final Supplier<long[]> trafficSupplier;
    private final LongSupplier connectTimeSupplier;
    private final Consumer<SpeedSample> onSpeedSample;
    private final Runnable onSpeedUnavailable;
    private final Runnable onTooltipRefresh;
    private final Consumer<Long> onUptimeTick;
    private final BackgroundExecutor executor;

    private volatile ScheduledFuture<?> tickFuture;
    private long lastReceived = 0;
    private long lastSent = 0;
    private boolean firstSample = true;
    private boolean lastOnline = false;
    private int failCount = 0;
    private int tick = 0;

    public NetworkMonitorService(BooleanSupplier isOnline,
                                 Supplier<long[]> trafficSupplier,
                                 LongSupplier connectTimeSupplier,
                                 Consumer<SpeedSample> onSpeedSample,
                                 Runnable onSpeedUnavailable,
                                 Runnable onTooltipRefresh,
                                 Consumer<Long> onUptimeTick) {
        this(isOnline, trafficSupplier, connectTimeSupplier, onSpeedSample, onSpeedUnavailable,
            onTooltipRefresh, onUptimeTick, null);
    }

    public NetworkMonitorService(BooleanSupplier isOnline,
                                 Supplier<long[]> trafficSupplier,
                                 LongSupplier connectTimeSupplier,
                                 Consumer<SpeedSample> onSpeedSample,
                                 Runnable onSpeedUnavailable,
                                 Runnable onTooltipRefresh,
                                 Consumer<Long> onUptimeTick,
                                 BackgroundExecutor executor) {
        this.isOnline = isOnline;
        this.trafficSupplier = trafficSupplier;
        this.connectTimeSupplier = connectTimeSupplier;
        this.onSpeedSample = onSpeedSample;
        this.onSpeedUnavailable = onSpeedUnavailable;
        this.onTooltipRefresh = onTooltipRefresh;
        this.onUptimeTick = onUptimeTick;
        this.executor = executor;
    }

    public synchronized void start() {
        if (executor == null) {
            throw new IllegalStateException("BackgroundExecutor required");
        }
        if (tickFuture != null && !tickFuture.isCancelled()) return;
        firstSample = true;
        failCount = 0;
        tick = 0;
        lastOnline = false;
        tickFuture = executor.scheduleAtFixedRate(this::tickSafe, 300L, 1000L);
    }

    public synchronized void stop() {
        BackgroundExecutor.cancel(tickFuture);
        tickFuture = null;
    }

    private void tickSafe() {
        try {
            boolean online = isOnline.getAsBoolean();
            if (online && !lastOnline) {
                firstSample = true;
                failCount = 0;
            }
            lastOnline = online;

            // Offline: sample less often to cut netstat/MXBean + EDT churn (uptime still 1s).
            int speedInterval = online ? 3 : 30;
            if (tick % speedInterval == 0) {
                long[] traffic = trafficSupplier.get();
                long curReceived = traffic[0];
                long curSent = traffic[1];
                if (curReceived > 0 || curSent > 0) {
                    if (firstSample) {
                        lastReceived = curReceived;
                        lastSent = curSent;
                        firstSample = false;
                    } else {
                        long dlSpeed = curReceived - lastReceived;
                        long ulSpeed = curSent - lastSent;
                        if (dlSpeed >= 0 && ulSpeed >= 0) {
                            onSpeedSample.accept(new SpeedSample(dlSpeed, ulSpeed));
                        }
                        lastReceived = curReceived;
                        lastSent = curSent;
                    }
                    failCount = 0;
                } else {
                    failCount++;
                    if (failCount > 3 && online) onSpeedUnavailable.run();
                }
            }

            if (tick % 3 == 0) onTooltipRefresh.run();

            long connTime = connectTimeSupplier.getAsLong();
            onUptimeTick.accept(online && connTime > 0 ? connTime : 0L);

            tick++;
        } catch (Exception e) {
            onSpeedUnavailable.run();
        }
    }
}
