package service;

import java.time.LocalTime;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Minute-aligned scheduled dial/disconnect on {@link BackgroundExecutor}.
 * Automated dial/disconnect must gate on {@link model.DialLifecycle#isBusy()}.
 * {@link #stop()} never joins (EDT-safe).
 */
public class ScheduleService {
    private final BooleanSupplier scheduledDialEnabled;
    private final BooleanSupplier scheduledDisconnectEnabled;
    private final IntSupplier dialHourSupplier;
    private final IntSupplier dialMinuteSupplier;
    private final IntSupplier disconnectHourSupplier;
    private final IntSupplier disconnectMinuteSupplier;
    private final BooleanSupplier isOnline;
    private final BooleanSupplier isBusy;
    private final Runnable onScheduledDial;
    private final Runnable onScheduledDisconnect;
    private final Runnable onDialTriggerLog;
    private final Runnable onDisconnectTriggerLog;
    private final Consumer<String> errorLogger;
    private final BackgroundExecutor executor;

    private volatile long lastDialTriggerEpochMinute = -1;
    private volatile long lastDisconnectTriggerEpochMinute = -1;
    private volatile ScheduledFuture<?> tickFuture;

    public ScheduleService(BooleanSupplier scheduledDialEnabled,
                           BooleanSupplier scheduledDisconnectEnabled,
                           IntSupplier dialHourSupplier,
                           IntSupplier dialMinuteSupplier,
                           IntSupplier disconnectHourSupplier,
                           IntSupplier disconnectMinuteSupplier,
                           BooleanSupplier isOnline,
                           BooleanSupplier isBusy,
                           Runnable onScheduledDial,
                           Runnable onScheduledDisconnect,
                           Runnable onDialTriggerLog,
                           Runnable onDisconnectTriggerLog,
                           Consumer<String> errorLogger) {
        this(scheduledDialEnabled, scheduledDisconnectEnabled, dialHourSupplier, dialMinuteSupplier,
            disconnectHourSupplier, disconnectMinuteSupplier, isOnline, isBusy,
            onScheduledDial, onScheduledDisconnect, onDialTriggerLog, onDisconnectTriggerLog,
            errorLogger, null);
    }

    public ScheduleService(BooleanSupplier scheduledDialEnabled,
                           BooleanSupplier scheduledDisconnectEnabled,
                           IntSupplier dialHourSupplier,
                           IntSupplier dialMinuteSupplier,
                           IntSupplier disconnectHourSupplier,
                           IntSupplier disconnectMinuteSupplier,
                           BooleanSupplier isOnline,
                           BooleanSupplier isBusy,
                           Runnable onScheduledDial,
                           Runnable onScheduledDisconnect,
                           Runnable onDialTriggerLog,
                           Runnable onDisconnectTriggerLog,
                           Consumer<String> errorLogger,
                           BackgroundExecutor executor) {
        this.scheduledDialEnabled = scheduledDialEnabled;
        this.scheduledDisconnectEnabled = scheduledDisconnectEnabled;
        this.dialHourSupplier = dialHourSupplier;
        this.dialMinuteSupplier = dialMinuteSupplier;
        this.disconnectHourSupplier = disconnectHourSupplier;
        this.disconnectMinuteSupplier = disconnectMinuteSupplier;
        this.isOnline = isOnline;
        this.isBusy = isBusy;
        this.onScheduledDial = onScheduledDial;
        this.onScheduledDisconnect = onScheduledDisconnect;
        this.onDialTriggerLog = onDialTriggerLog;
        this.onDisconnectTriggerLog = onDisconnectTriggerLog;
        this.errorLogger = errorLogger != null ? errorLogger : msg -> {};
        this.executor = executor;
    }

    /** Back-compat constructor without error logger. */
    public ScheduleService(BooleanSupplier scheduledDialEnabled,
                           BooleanSupplier scheduledDisconnectEnabled,
                           IntSupplier dialHourSupplier,
                           IntSupplier dialMinuteSupplier,
                           IntSupplier disconnectHourSupplier,
                           IntSupplier disconnectMinuteSupplier,
                           BooleanSupplier isOnline,
                           BooleanSupplier isBusy,
                           Runnable onScheduledDial,
                           Runnable onScheduledDisconnect,
                           Runnable onDialTriggerLog,
                           Runnable onDisconnectTriggerLog) {
        this(scheduledDialEnabled, scheduledDisconnectEnabled, dialHourSupplier, dialMinuteSupplier,
            disconnectHourSupplier, disconnectMinuteSupplier, isOnline, isBusy,
            onScheduledDial, onScheduledDisconnect, onDialTriggerLog, onDisconnectTriggerLog, null, null);
    }

    public static boolean shouldFireDial(boolean enabled,
                                         boolean online,
                                         boolean busy,
                                         boolean minuteMatches,
                                         long epochMinute,
                                         long lastTriggerEpochMinute) {
        return enabled && !online && !busy && minuteMatches && epochMinute != lastTriggerEpochMinute;
    }

    public static boolean shouldFireDisconnect(boolean enabled,
                                               boolean online,
                                               boolean busy,
                                               boolean minuteMatches,
                                               long epochMinute,
                                               long lastTriggerEpochMinute) {
        return enabled && online && !busy && minuteMatches && epochMinute != lastTriggerEpochMinute;
    }

    public synchronized void restart() {
        stop();
        if (executor == null) {
            throw new IllegalStateException("BackgroundExecutor required");
        }
        if (!scheduledDialEnabled.getAsBoolean() && !scheduledDisconnectEnabled.getAsBoolean()) return;
        tickFuture = executor.scheduleAtFixedRate(this::tickSafe, 200L, 1000L);
    }

    public synchronized void stop() {
        BackgroundExecutor.cancel(tickFuture);
        tickFuture = null;
    }

    private void tickSafe() {
        try {
            LocalTime now = LocalTime.now();
            long epochMinute = System.currentTimeMillis() / 60000L;
            boolean online = isOnline.getAsBoolean();
            boolean busy = isBusy.getAsBoolean();

            if (scheduledDialEnabled.getAsBoolean()) {
                int h = dialHourSupplier.getAsInt();
                int m = dialMinuteSupplier.getAsInt();
                boolean minuteMatches = matchesCurrentMinute(now, h, m);
                if (shouldFireDial(true, online, busy, minuteMatches, epochMinute, lastDialTriggerEpochMinute)) {
                    lastDialTriggerEpochMinute = epochMinute;
                    onDialTriggerLog.run();
                    onScheduledDial.run();
                }
            }

            if (scheduledDisconnectEnabled.getAsBoolean()) {
                int h = disconnectHourSupplier.getAsInt();
                int m = disconnectMinuteSupplier.getAsInt();
                boolean minuteMatches = matchesCurrentMinute(now, h, m);
                if (shouldFireDisconnect(true, online, busy, minuteMatches, epochMinute, lastDisconnectTriggerEpochMinute)) {
                    lastDisconnectTriggerEpochMinute = epochMinute;
                    onDisconnectTriggerLog.run();
                    onScheduledDisconnect.run();
                }
            }
        } catch (Exception e) {
            errorLogger.accept("定时任务异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private boolean matchesCurrentMinute(LocalTime now, int hour, int minute) {
        return now.getHour() == hour && now.getMinute() == minute;
    }
}
