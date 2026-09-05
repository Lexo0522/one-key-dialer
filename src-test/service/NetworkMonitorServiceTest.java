package service;

import model.SessionTraffic;
import org.junit.jupiter.api.Test;
import service.NetworkMonitorService.SpeedSample;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkMonitorServiceTest {

    /** Drives {@code tickSafe()} manually (fixed-rate 1s ticks), executor unused. */
    private static final class Harness {
        final AtomicBoolean online = new AtomicBoolean(false);
        final AtomicLong received = new AtomicLong(0);
        final AtomicLong sent = new AtomicLong(0);
        final AtomicLong connectTime = new AtomicLong(0);
        final List<SpeedSample> samples = new ArrayList<>();
        final AtomicBoolean unavailable = new AtomicBoolean(false);
        final SessionTraffic sessionTraffic = new SessionTraffic();
        final NetworkMonitorService service;

        Harness() {
            service = new NetworkMonitorService(
                online::get,
                () -> new long[]{received.get(), sent.get()},
                connectTime::get,
                sample -> {
                    samples.add(sample);
                    sessionTraffic.applySample(sample.downBytesPerSec, sample.upBytesPerSec,
                        sample.downDelta, sample.upDelta);
                },
                () -> unavailable.set(true),
                () -> { },
                t -> { },
                null);
        }

        /** One 1-second tick. */
        void tick() {
            service.tickSafe();
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) tick();
        }
    }

    @Test
    void firstSampleBaselinesWithoutEmitting() {
        Harness h = new Harness();
        h.online.set(true);
        h.received.set(3000);
        h.sent.set(1000);

        h.tick();

        assertTrue(h.samples.isEmpty());
        assertFalse(h.unavailable.get());
    }

    @Test
    void rateIsDeltaOverElapsedSecondsAndTotalsGetRawDelta() {
        Harness h = new Harness();
        h.online.set(true);
        h.received.set(3000);
        h.sent.set(1000);
        h.tick();
        h.ticks(2);

        h.received.set(9000);
        h.sent.set(4000);
        h.tick();

        assertEquals(1, h.samples.size());
        SpeedSample s = h.samples.get(0);
        assertEquals(2000, s.downBytesPerSec);
        assertEquals(1000, s.upBytesPerSec);
        assertEquals(6000, s.downDelta);
        assertEquals(3000, s.upDelta);
        assertEquals(6000, h.sessionTraffic.totalDownload().get());
        assertEquals(3000, h.sessionTraffic.totalUpload().get());
        assertEquals(2000, h.sessionTraffic.currentSpeedDown().get());
        assertEquals(1000, h.sessionTraffic.currentSpeedUp().get());
    }

    @Test
    void idleNetworkEmitsZeroRateInsteadOfFailing() {
        Harness h = new Harness();
        h.online.set(true);
        h.received.set(5000);
        h.sent.set(2000);
        h.tick();
        h.ticks(2);
        h.tick();

        assertEquals(1, h.samples.size());
        assertEquals(0, h.samples.get(0).downBytesPerSec);
        assertFalse(h.unavailable.get());
    }

    @Test
    void counterResetRebaselinesInsteadOfEmitting() {
        Harness h = new Harness();
        h.online.set(true);
        h.received.set(5000);
        h.sent.set(2000);
        h.tick();
        h.ticks(2);

        h.received.set(3000);
        h.sent.set(1000);
        h.tick();
        assertTrue(h.samples.isEmpty());

        h.ticks(2);
        h.received.set(6000);
        h.sent.set(2000);
        h.tick();

        assertEquals(1, h.samples.size());
        assertEquals(1000, h.samples.get(0).downBytesPerSec);
        assertEquals(3000, h.samples.get(0).downDelta);
    }

    @Test
    void repeatedZeroSamplesMarkSpeedUnavailable() {
        Harness h = new Harness();
        h.online.set(true);

        h.ticks(10);

        assertTrue(h.unavailable.get());
        assertEquals(0, h.samples.size());
    }

    @Test
    void offlineToOnlineRebaselinesWithoutSpanningGap() {
        Harness h = new Harness();
        h.received.set(100000);
        h.sent.set(100000);
        h.tick();

        h.online.set(true);
        h.received.set(110000);
        h.sent.set(100000);
        h.ticks(3);
        assertTrue(h.samples.isEmpty());

        h.received.set(113000);
        h.sent.set(100000);
        h.ticks(3);

        assertEquals(1, h.samples.size());
        assertEquals(1000, h.samples.get(0).downBytesPerSec);
        assertEquals(3000, h.samples.get(0).downDelta);
        assertEquals(3000, h.sessionTraffic.totalDownload().get());
    }
}
