package util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-rasdial reachability confirmation. RAS success does not always mean usable internet.
 * Supports ICMP/ping host probes and HTTP(S) HEAD/GET fallback (campus nets often block ICMP).
 * HTTP uses {@link HttpClient} (JDK 11+).
 */
public final class ConnectivityConfirm {
    public static final String DEFAULT_HOST = "223.5.5.5";
    public static final String DEFAULT_HTTP_URL = "http://connectivitycheck.gstatic.com/generate_204";
    public static final int DEFAULT_ATTEMPTS = 3;
    public static final long DEFAULT_DELAY_MS = 1000L;
    public static final int DEFAULT_HTTP_TIMEOUT_MS = 2500;

    public static final String MODE_ICMP = "icmp";
    public static final String MODE_HTTP = "http";
    public static final String MODE_AUTO = "auto";

    public static final String HISTORY_STATUS_RAS_NO_INTERNET = "RAS成功无外网";
    public static final String HISTORY_STATUS_SUCCESS = "成功";

    @FunctionalInterface
    public interface Reachability {
        boolean isReachable(String host);
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Production / settings-driven probe configuration. */
    public static final class Config {
        public final String mode;
        public final String host;
        public final String httpUrl;
        public final int attempts;
        public final long delayMs;
        public final int httpTimeoutMs;

        public Config(String mode, String host, String httpUrl, int attempts, long delayMs, int httpTimeoutMs) {
            this.mode = mode != null ? mode : MODE_AUTO;
            this.host = (host == null || host.isEmpty()) ? DEFAULT_HOST : host;
            this.httpUrl = (httpUrl == null || httpUrl.isEmpty()) ? DEFAULT_HTTP_URL : httpUrl;
            this.attempts = Math.max(1, attempts);
            this.delayMs = Math.max(0L, delayMs);
            this.httpTimeoutMs = Math.max(500, httpTimeoutMs);
        }

        public static Config defaults() {
            return new Config(MODE_AUTO, DEFAULT_HOST, DEFAULT_HTTP_URL,
                DEFAULT_ATTEMPTS, DEFAULT_DELAY_MS, DEFAULT_HTTP_TIMEOUT_MS);
        }

        public static Config from(String mode, String host, String httpUrl, int attempts, int delayMs) {
            return new Config(mode, host, httpUrl, attempts, delayMs, DEFAULT_HTTP_TIMEOUT_MS);
        }
    }

    private ConnectivityConfirm() {
    }

    public static String normalizeMode(String mode) {
        if (mode == null) return MODE_AUTO;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (MODE_ICMP.equals(m) || MODE_HTTP.equals(m) || MODE_AUTO.equals(m)) return m;
        return MODE_AUTO;
    }

    /**
     * Probe up to {@code attempts} times. Sleeps between failures only.
     */
    public static boolean confirm(Reachability probe,
                                  String host,
                                  int attempts,
                                  long delayMs,
                                  Sleeper sleeper) {
        if (probe == null) return false;
        String h = (host == null || host.isEmpty()) ? DEFAULT_HOST : host;
        int n = Math.max(1, attempts);
        long delay = Math.max(0L, delayMs);
        Sleeper sleep = sleeper != null ? sleeper : Thread::sleep;

        for (int i = 0; i < n; i++) {
            try {
                if (probe.isReachable(h)) return true;
            } catch (RuntimeException ignored) {
            }
            if (i + 1 < n && delay > 0) {
                try {
                    sleep.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    public static boolean confirm(Reachability probe, String host, int attempts, long delayMs) {
        return confirm(probe, host, attempts, delayMs, Thread::sleep);
    }

    /** ICMP/ping only (legacy default). */
    public static boolean confirmDefault() {
        return confirm(Config.defaults());
    }

    public static boolean confirm(Config config) {
        return confirmDetailed(config, "probe").ok;
    }

    /**
     * Single-attempt, zero-delay check for periodic monitors (auto-reconnect).
     * Uses the same mode/host/http as {@code config} so UI probe settings stay authoritative.
     */
    public static boolean quickCheck(Config config) {
        Config cfg = config != null ? config : Config.defaults();
        Config oneShot = new Config(cfg.mode, cfg.host, cfg.httpUrl, 1, 0L, cfg.httpTimeoutMs);
        return confirm(oneShot);
    }

    /** Convenience: quick check with defaults (auto mode). */
    public static boolean quickCheckDefault() {
        return quickCheck(Config.defaults());
    }

    /**
     * Same as {@link #confirm(Config)} but returns timing/mode for logs and Diag.
     *
     * @param source short tag e.g. {@code post-dial}, {@code manual-test}
     */
    public static ProbeOutcome confirmDetailed(Config config, String source) {
        Config cfg = config != null ? config : Config.defaults();
        long t0 = System.nanoTime();
        boolean ok = confirm(cfg, NetworkProbe::icmpReachable, ConnectivityConfirm::httpReachable, Thread::sleep);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return new ProbeOutcome(
            ok, ms, normalizeMode(cfg.mode), cfg.host, cfg.httpUrl, cfg.attempts,
            source, System.currentTimeMillis());
    }

    /**
     * Full confirm with injectable ICMP + HTTP for tests.
     * AUTO: each attempt tries ICMP then HTTP; success if either works.
     */
    public static boolean confirm(Config config,
                                  Reachability icmpProbe,
                                  Reachability httpProbe,
                                  Sleeper sleeper) {
        Config cfg = config != null ? config : Config.defaults();
        String mode = normalizeMode(cfg.mode);
        Sleeper sleep = sleeper != null ? sleeper : Thread::sleep;
        Reachability icmp = icmpProbe != null ? icmpProbe : NetworkProbe::icmpReachable;
        Reachability http = httpProbe != null ? httpProbe : ConnectivityConfirm::httpReachable;

        for (int i = 0; i < cfg.attempts; i++) {
            boolean ok = false;
            try {
                if (MODE_ICMP.equals(mode)) {
                    ok = icmp.isReachable(cfg.host);
                } else if (MODE_HTTP.equals(mode)) {
                    ok = http.isReachable(cfg.httpUrl);
                } else {
                    ok = icmp.isReachable(cfg.host) || http.isReachable(cfg.httpUrl);
                }
            } catch (RuntimeException ignored) {
                ok = false;
            }
            if (ok) return true;
            if (i + 1 < cfg.attempts && cfg.delayMs > 0) {
                try {
                    sleep.sleep(cfg.delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Lightweight HTTP(S) check. Treats 2xx/3xx (and generate_204's 204) as success.
     * The {@code target} is a full URL (not a bare host).
     */
    public static boolean httpReachable(String target) {
        return httpReachable(target, DEFAULT_HTTP_TIMEOUT_MS);
    }

    /** Cached clients by connect-timeout ms (probe settings rarely change). */
    private static final ConcurrentHashMap<Integer, HttpClient> HTTP_CLIENTS = new ConcurrentHashMap<>();

    private static HttpClient httpClient(int timeoutMs) {
        return HTTP_CLIENTS.computeIfAbsent(timeoutMs, t -> HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(t))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    public static boolean httpReachable(String target, int timeoutMs) {
        if (target == null || target.isEmpty()) return false;
        int timeout = Math.max(500, timeoutMs);
        try {
            URI uri = URI.create(target.trim());
            if (uri.getScheme() == null) return false;
            HttpClient client = httpClient(timeout);
            HttpRequest.Builder base = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeout))
                .header("User-Agent", model.AppVersion.USER_AGENT);
            // Prefer HEAD (cheap); some portals reject HEAD — fall back to GET.
            HttpResponse<Void> head = client.send(
                base.method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
            int code = head.statusCode();
            if (code >= 200 && code < 400) return true;
            HttpResponse<Void> get = client.send(
                base.GET().build(),
                HttpResponse.BodyHandlers.discarding());
            code = get.statusCode();
            return code >= 200 && code < 400;
        } catch (IllegalArgumentException | IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public static String historyStatus(boolean rasSuccess, boolean netOk, int rasCode) {
        if (!rasSuccess) {
            return "失败:" + rasCode;
        }
        if (!netOk) {
            return HISTORY_STATUS_RAS_NO_INTERNET;
        }
        return HISTORY_STATUS_SUCCESS;
    }

    /** Test helper equality. */
    public static boolean sameConfig(Config a, Config b) {
        return a != null && b != null
            && Objects.equals(normalizeMode(a.mode), normalizeMode(b.mode))
            && Objects.equals(a.host, b.host)
            && Objects.equals(a.httpUrl, b.httpUrl)
            && a.attempts == b.attempts
            && a.delayMs == b.delayMs;
    }
}
