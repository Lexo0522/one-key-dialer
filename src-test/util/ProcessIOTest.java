package util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessIOTest {
    @Test
    void capturesOutputAndExitCode() throws Exception {
        ProcessIO.Result result = ProcessIO.run(
            Arrays.asList("cmd", "/c", "echo hello"),
            5, TimeUnit.SECONDS, StandardCharsets.UTF_8, null);

        assertFalse(result.timedOut);
        assertEquals(0, result.exitCode);
        assertTrue(result.output.toLowerCase().contains("hello"));
    }

    @Test
    void enforcesTimeoutEvenWhenChildKeepsRunning() throws Exception {
        long started = System.nanoTime();
        ProcessIO.Result result = ProcessIO.run(
            Arrays.asList("cmd", "/c", "ping -n 5 127.0.0.1 >nul"),
            100, TimeUnit.MILLISECONDS, StandardCharsets.UTF_8, null);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(result.timedOut);
        assertEquals(-1, result.exitCode);
        assertTrue(elapsedMillis < 4000, "timeout should return promptly: " + elapsedMillis + "ms");
    }

    @Test
    void callbackFailureDoesNotStopOutputDrain() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        ProcessIO.Result result = ProcessIO.run(
            Arrays.asList("cmd", "/c", "echo first & echo second"),
            5, TimeUnit.SECONDS, StandardCharsets.UTF_8,
            line -> {
                callbackCount.incrementAndGet();
                throw new IllegalStateException("test callback failure");
            });

        assertFalse(result.timedOut);
        assertEquals(0, result.exitCode);
        assertTrue(callbackCount.get() >= 2);
        assertTrue(result.output.toLowerCase().contains("first"));
        assertTrue(result.output.toLowerCase().contains("second"));
    }
}
