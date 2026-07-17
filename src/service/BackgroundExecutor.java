package service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared background scheduler for reconnect / schedule / network-monitor ticks,
 * plus a dedicated single-thread pool for long UI diagnostics (ping/tracert/ipconfig)
 * so 60s jobs do not starve 1s monitor ticks.
 * <p>
 * Dial/disconnect work stays on {@link DialOrchestrator}'s RasDial thread.
 * {@link #shutdown()} never blocks the EDT for long: await runs on a daemon helper.
 * The longJobs pool is created on first {@link #submitLong} (diag tab).
 */
public final class BackgroundExecutor {
    private final ScheduledExecutorService scheduler;
    private final Object longJobsLock = new Object();
    private volatile ExecutorService longJobs;
    private volatile boolean shutdown;

    public BackgroundExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "AppBackground-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // 2 threads: long auto-dial waits must not starve 1s monitor/schedule ticks
        this.scheduler = Executors.newScheduledThreadPool(2, tf);
    }

    public boolean isShutdown() {
        return shutdown || scheduler.isShutdown();
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelayMs, long periodMs) {
        return scheduler.scheduleAtFixedRate(wrap(command), initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelayMs, long delayMs) {
        return scheduler.scheduleWithFixedDelay(wrap(command), initialDelayMs, delayMs, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> schedule(Runnable command, long delayMs) {
        return scheduler.schedule(wrap(command), delayMs, TimeUnit.MILLISECONDS);
    }

    /** Short one-shot work (probe test, reconnect scheduling glue). */
    public Future<?> submit(Runnable command) {
        return scheduler.submit(wrap(command));
    }

    /**
     * Long diagnostics (ipconfig/tracert/ping). Dedicated queue so monitor ticks stay responsive.
     * Pool is created lazily on first use.
     */
    public Future<?> submitLong(Runnable command) {
        if (shutdown) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return longJobs().submit(wrap(command));
    }

    private ExecutorService longJobs() {
        ExecutorService jobs = longJobs;
        if (jobs != null && !jobs.isShutdown()) {
            return jobs;
        }
        synchronized (longJobsLock) {
            jobs = longJobs;
            if (jobs == null || jobs.isShutdown()) {
                jobs = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "AppLongJob");
                    t.setDaemon(true);
                    return t;
                });
                longJobs = jobs;
            }
            return jobs;
        }
    }

    /**
     * Cancel a task without waiting. Safe on EDT.
     */
    public static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * Stop accepting work. Does not block the EDT waiting for tasks.
     */
    public void shutdown() {
        shutdown = true;
        scheduler.shutdownNow();
        ExecutorService jobs;
        synchronized (longJobsLock) {
            jobs = longJobs;
            longJobs = null;
        }
        if (jobs != null) {
            jobs.shutdownNow();
        }
        if (isLikelyEventDispatchThread()) {
            final ExecutorService longJobsRef = jobs;
            Thread helper = new Thread(() -> {
                awaitQuiet(scheduler, 2, TimeUnit.SECONDS);
                if (longJobsRef != null) {
                    awaitQuiet(longJobsRef, 2, TimeUnit.SECONDS);
                }
            }, "AppBackground-Shutdown");
            helper.setDaemon(true);
            helper.start();
        } else {
            awaitQuiet(scheduler, 2, TimeUnit.SECONDS);
            if (jobs != null) {
                awaitQuiet(jobs, 2, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitQuiet(ExecutorService es, long timeout, TimeUnit unit) {
        try {
            es.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isLikelyEventDispatchThread() {
        try {
            Class<?> su = Class.forName("javax.swing.SwingUtilities");
            Object r = su.getMethod("isEventDispatchThread").invoke(null);
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    private Runnable wrap(Runnable command) {
        return () -> {
            if (shutdown) return;
            try {
                command.run();
            } catch (Throwable t) {
                // keep scheduler alive; callers should catch their own errors
                if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }
}
