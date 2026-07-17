package util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Shared process helpers: charset, drain, timeout destroy. */
public final class ProcessIO {
    private ProcessIO() {
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
