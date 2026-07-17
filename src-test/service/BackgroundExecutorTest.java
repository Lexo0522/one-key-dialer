package service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundExecutorTest {

    @Test
    void scheduleRunsAndShutdownStopsNewWork() throws Exception {
        BackgroundExecutor ex = new BackgroundExecutor();
        AtomicInteger n = new AtomicInteger();
        CountDownLatch once = new CountDownLatch(1);
        ex.schedule(() -> {
            n.incrementAndGet();
            once.countDown();
        }, 20);
        assertTrue(once.await(2, TimeUnit.SECONDS));
        assertEquals(1, n.get());
        ex.shutdown();
        assertTrue(ex.isShutdown());
        CountDownLatch late = new CountDownLatch(1);
        try {
            ex.schedule(late::countDown, 10);
        } catch (Exception ignored) {
            // pool may reject after shutdown
        }
        assertFalse(late.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void cancelFuturePreventsRun() throws Exception {
        BackgroundExecutor ex = new BackgroundExecutor();
        AtomicInteger n = new AtomicInteger();
        var f = ex.schedule(() -> n.incrementAndGet(), 500);
        BackgroundExecutor.cancel(f);
        Thread.sleep(700);
        assertEquals(0, n.get());
        ex.shutdown();
    }

    @Test
    void submitLongRunsOnDedicatedQueue() throws Exception {
        BackgroundExecutor ex = new BackgroundExecutor();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger n = new AtomicInteger();
        ex.submitLong(() -> {
            n.incrementAndGet();
            done.countDown();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(1, n.get());
        ex.shutdown();
    }
}
