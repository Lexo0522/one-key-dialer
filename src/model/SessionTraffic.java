package model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe session / traffic counters shared by monitor, tray, diag, and dial history.
 */
public final class SessionTraffic {
    private final AtomicLong connectTimeMillis = new AtomicLong(0);
    private final AtomicLong totalDownload = new AtomicLong(0);
    private final AtomicLong totalUpload = new AtomicLong(0);
    private final AtomicLong currentSpeedDown = new AtomicLong(0);
    private final AtomicLong currentSpeedUp = new AtomicLong(0);
    private final AtomicLong sessionStartDownload = new AtomicLong(0);
    private final AtomicLong sessionStartUpload = new AtomicLong(0);
    private final AtomicLong totalDialCount = new AtomicLong(0);
    private final AtomicLong successDialCount = new AtomicLong(0);

    public AtomicLong connectTimeMillis() {
        return connectTimeMillis;
    }

    public AtomicLong totalDownload() {
        return totalDownload;
    }

    public AtomicLong totalUpload() {
        return totalUpload;
    }

    public AtomicLong currentSpeedDown() {
        return currentSpeedDown;
    }

    public AtomicLong currentSpeedUp() {
        return currentSpeedUp;
    }

    public AtomicLong sessionStartDownload() {
        return sessionStartDownload;
    }

    public AtomicLong sessionStartUpload() {
        return sessionStartUpload;
    }

    public AtomicLong totalDialCount() {
        return totalDialCount;
    }

    public AtomicLong successDialCount() {
        return successDialCount;
    }

    public void applySample(long downBytes, long upBytes) {
        currentSpeedDown.set(downBytes);
        currentSpeedUp.set(upBytes);
        totalDownload.addAndGet(downBytes);
        totalUpload.addAndGet(upBytes);
    }

    public void clearSpeeds() {
        currentSpeedDown.set(0);
        currentSpeedUp.set(0);
    }

    /** Call when transitioning to online (or refreshing session baseline). */
    public void markSessionStart() {
        connectTimeMillis.set(System.currentTimeMillis());
        sessionStartDownload.set(totalDownload.get());
        sessionStartUpload.set(totalUpload.get());
    }

    public void markOffline() {
        connectTimeMillis.set(0);
        clearSpeeds();
    }

    public long sessionTrafficBytes() {
        long down = totalDownload.get() - sessionStartDownload.get();
        long up = totalUpload.get() - sessionStartUpload.get();
        long sum = down + up;
        return sum < 0 ? 0 : sum;
    }
}
