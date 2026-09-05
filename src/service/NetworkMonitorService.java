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
        /** Rate for display, bytes per second. */
        public final long downBytesPerSec;
        public final long upBytesPerSec;
        /** Raw bytes accumulated since the previous sample, for totals. */
        public final long downDelta;
        public final long upDelta;

        public SpeedSample(long downBytesPerSec, long upBytesPerSec, long downDelta, long upDelta) {
            this.downBytesPerSec = downBytesPerSec;
            this.upBytesPerSec = upBytesPerSec;
            this.downDelta = downDelta;
            this.upDelta = upDelta;
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
    private long lastSampleTick = 0;
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
        lastSampleTick = 0;
        lastOnline = false;
        tickFuture = executor.scheduleAtFixedRate(this::tickSafe, 300L, 1000L);
    }

    public synchronized void stop() {
        BackgroundExecutor.cancel(tickFuture);
        tickFuture = null;
    }

    // Package-private so tests in src-test/service can drive ticks deterministically.
    void tickSafe() {
        try {
            boolean online = isOnline.getAsBoolean();
            if (online && !lastOnline) {
                firstSample = true;
                failCount = 0;
            }
            lastOnline = online;

            // Offline: sample less often to cut netstat + EDT churn (uptime still 1s).
            int speedInterval = online ? 3 : 30;
            if (tick % speedInterval == 0) {
                long[] traffic = trafficSupplier.get();
                long curReceived = traffic[0];
                long curSent = traffic[1];
                if (curReceived > 0 || curSent > 0) {
                    if (firstSample || curReceived < lastReceived || curSent < lastSent) {
                        // Baseline on first sample or after a counter reset; emit nothing.
                        lastReceived = curReceived;
                        lastSent = curSent;
                        lastSampleTick = tick;
                        firstSample = false;
                    } else {
                        // Ticks run at a fixed 1s rate, so the tick gap is the elapsed seconds.
                        long elapsedSec = Math.max(1, tick - lastSampleTick);
                        long dlDelta = curReceived - lastReceived;
                        long ulDelta = curSent - lastSent;
                        onSpeedSample.accept(new SpeedSample(
                            dlDelta / elapsedSec, ulDelta / elapsedSec, dlDelta, ulDelta));
                        lastReceived = curReceived;
                        lastSent = curSent;
                        lastSampleTick = tick;
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
