package util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Shared process helpers: charset, bounded execution, drain, timeout destroy. */
public final class ProcessIO {
    /** Prevent a broken or unexpected child process from consuming unbounded memory. */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 64 * 1024;

    /** Result of a bounded child-process execution. */
    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;

        public Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output != null ? output : "";
            this.timedOut = timedOut;
        }
    }

    private ProcessIO() {
    }

    /**
     * Execute a child process with a real deadline while draining its merged output concurrently.
     * The previous read-then-wait pattern could block forever when a child kept its output stream
     * open. Output is retained only up to {@link #DEFAULT_MAX_OUTPUT_CHARS}.
     */
    public static Result run(List<String> command, long timeout, TimeUnit unit,
                             Charset charset, Consumer<String> lineConsumer)
        throws Exception {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) throw new IllegalArgumentException("command must not be empty");
        Objects.requireNonNull(unit, "unit");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Charset cs = charset != null ? charset : childCharset();
        StringBuilder output = new StringBuilder(Math.min(DEFAULT_MAX_OUTPUT_CHARS, 4096));

        Thread reader = new Thread(() -> drainBounded(process, cs, output, lineConsumer),
            "ProcessIO-reader");
        reader.setDaemon(true);
        reader.start();

        boolean timedOut = false;
        int exitCode;
        try {
            if (process.waitFor(timeout, unit)) {
                exitCode = process.exitValue();
            } else {
                timedOut = true;
                terminate(process);
                exitCode = -1;
            }
        } catch (InterruptedException e) {
            terminate(process);
            reader.join(2000L);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }

        reader.join(2000L);
        if (reader.isAlive()) reader.interrupt();
        synchronized (output) {
            return new Result(exitCode, output.toString(), timedOut);
        }
    }

    private static void drainBounded(Process process, Charset charset, StringBuilder output,
                                     Consumer<String> lineConsumer) {
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(process.getInputStream(), charset))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (output) {
                    if (output.length() < DEFAULT_MAX_OUTPUT_CHARS) {
                        int remaining = DEFAULT_MAX_OUTPUT_CHARS - output.length();
                        if (line.length() <= remaining) {
                            output.append(line);
                        } else {
                            output.append(line, 0, remaining);
                        }
                        if (output.length() < DEFAULT_MAX_OUTPUT_CHARS) output.append('\n');
                    }
                }
                if (lineConsumer != null) {
                    try {
                        lineConsumer.accept(line);
                    } catch (RuntimeException ignored) {
                        // A logging/UI callback must not stop stdout from being drained.
                    }
                }
            }
        } catch (Exception ignored) {
            // The process result and timeout remain authoritative for callers.
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    public static Charset childCharset() {
        String jnu = System.getProperty("sun.jnu.encoding");
        if (jnu != null && !jnu.isEmpty()) {
            try {
                return Charset.forName(jnu);
            } catch (Exception ignored) {
            }
        }
        String fileEnc = System.getProperty("file.encoding");
        if (fileEnc != null) {
            try {
                return Charset.forName(fileEnc);
            } catch (Exception ignored) {
            }
        }
        // Chinese Windows fallback
        try {
            return Charset.forName("GBK");
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }

    public static String readAll(InputStream in, Charset charset) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, charset))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    public static void drainLines(InputStream in, Charset charset, Consumer<String> lineConsumer) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, charset))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (lineConsumer != null) lineConsumer.accept(line);
            }
        }
    }

    public static int waitOrKill(Process p, long timeout, TimeUnit unit) throws InterruptedException {
        boolean finished = p.waitFor(timeout, unit);
        if (finished) return p.exitValue();
        p.destroy();
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
        return -1;
    }
}
