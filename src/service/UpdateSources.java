package service;

import model.AppVersion;
import util.AppPaths;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Update-line configuration: endpoint URLs, timeouts, retries, breaker params.
 * Nothing about the update sources is hardcoded at the call sites.
 *
 * <p>Precedence: built-in {@code /update.properties} defaults ← external
 * {@code update.properties} in the app data dir (per-key override) ← code
 * fallback when no property file is usable. Every load failure degrades to the
 * next layer instead of throwing, so a broken config can never block startup.
 *
 * <p>{@code update.sources} lists the lines in priority order (left = primary).
 * The update engine walks them serially — the primary line is tried first and
 * the next line only after the previous one failed; concurrent fetching of two
 * lines is explicitly not done.
 */
public final class UpdateSources {
    /** Built-in defaults shipped inside the jar. */
    public static final String RESOURCE = "/update.properties";
    /** Optional per-installation override file in the app data dir. */
    public static final String OVERRIDE_FILE = "update.properties";

    private static final String KEY_SOURCES = "update.sources";
    private static final String KEY_CONNECT_TIMEOUT_MS = "update.connectTimeoutMs";
    private static final String DEFAULT_SOURCES = "gitee,github";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    /** Code fallback used only when the built-in resource is missing entirely. */
    private static final String GITEE_DEFAULT_API =
        "https://gitee.com/api/v5/repos/kate522/one-key-dialer/releases/latest";

    /** Global HttpClient connect timeout (DNS + TCP + TLS), shared by all lines. */
    public final int connectTimeoutMs;
    private final List<Source> sources;

    private UpdateSources(List<Source> sources, int connectTimeoutMs) {
        this.sources = List.copyOf(sources);
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** Enabled lines in configured priority order (primary first). */
    public List<Source> enabled() {
        return Collections.unmodifiableList(sources);
    }

    /** One update line with all tunables. Values are clamped to sane ranges. */
    public static final class Source {
        public final String id;
        public final String displayName;
        public final String apiUrl;
        /** Metadata probe timeout. The primary line runs a deliberately short one. */
        public final int checkTimeoutMs;
        /** Total check attempts; 2 = one try plus one retry. */
        public final int checkAttempts;
        /** Transfer attempts per download (first try plus resume retries). */
        public final int downloadAttempts;
        /** Bound on waiting for the download response headers (body is stall-guarded). */
        public final int downloadHeaderTimeoutMs;
        /** No bytes for this long during a transfer ⇒ abort with a clear message. */
        public final int stallTimeoutMs;
        /** Consecutive failures after which the line is tripped open. */
        public final int breakerThreshold;
        /** How long a tripped line is skipped before a half-open probe is allowed. */
        public final long breakerCooldownMs;

        Source(String id, String displayName, String apiUrl,
               int checkTimeoutMs, int checkAttempts, int downloadAttempts,
               int downloadHeaderTimeoutMs, int stallTimeoutMs,
               int breakerThreshold, long breakerCooldownMs) {
            this.id = id;
            this.displayName = displayName;
            this.apiUrl = apiUrl;
            this.checkTimeoutMs = clampInt(String.valueOf(checkTimeoutMs), 1000, 120_000, 12_000);
            this.checkAttempts = clampInt(String.valueOf(checkAttempts), 1, 5, 1);
            this.downloadAttempts = clampInt(String.valueOf(downloadAttempts), 1, 10, 3);
            this.downloadHeaderTimeoutMs = clampInt(String.valueOf(downloadHeaderTimeoutMs),
                5_000, 600_000, 60_000);
            this.stallTimeoutMs = clampInt(String.valueOf(stallTimeoutMs), 5_000, 1_800_000, 60_000);
            this.breakerThreshold = clampInt(String.valueOf(breakerThreshold), 1, 10, 2);
            this.breakerCooldownMs = clampLong(String.valueOf(breakerCooldownMs),
                10_000L, 86_400_000L, 600_000L);
        }

        @Override public String toString() {
            return displayName + "(" + id + ")";
        }
    }

    /** Production loader: built-in resource + data-dir override. */
    public static UpdateSources load(Consumer<String> warnSink) {
        Properties props = new Properties();
        try (InputStream in = UpdateSources.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                warn(warnSink, "内置更新配置 " + RESOURCE + " 缺失，使用代码默认值");
            }
        } catch (IOException e) {
            warn(warnSink, "内置更新配置读取失败，使用代码默认值: " + e.getMessage());
        }
        File external = new File(AppPaths.getDataDir(UpdateSources.class), OVERRIDE_FILE);
        if (external.isFile()) {
            try (InputStream in = Files.newInputStream(external.toPath())) {
                Properties override = new Properties();
                override.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String name : override.stringPropertyNames()) {
                    props.setProperty(name, override.getProperty(name));
                }
            } catch (IOException e) {
                warn(warnSink, "外部更新配置 " + external.getAbsolutePath() + " 读取失败，忽略: "
                    + e.getMessage());
            }
        }
        return fromProperties(props, warnSink);
    }

    /** Code fallback when no property file is usable. */
    public static UpdateSources defaults() {
        return fromProperties(new Properties(), null);
    }

    /** Test/fixture factory. */
    static UpdateSources of(List<Source> sources, int connectTimeoutMs) {
        return new UpdateSources(sources, connectTimeoutMs);
    }

    /** Parse a property set. Unknown line ids need an explicit api, else they are skipped. */
    static UpdateSources fromProperties(Properties props, Consumer<String> warnSink) {
        int connectTimeoutMs = clampInt(props.getProperty(KEY_CONNECT_TIMEOUT_MS),
            1000, 120_000, DEFAULT_CONNECT_TIMEOUT_MS);
        String order = props.getProperty(KEY_SOURCES, DEFAULT_SOURCES).trim();
        List<Source> parsed = new ArrayList<>();
        for (String raw : order.split(",")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || seen(parsed, id)) continue;
            String prefix = "source." + id + ".";
            if (!Boolean.parseBoolean(props.getProperty(prefix + "enabled", "true"))) continue;
            String api = props.getProperty(prefix + "api", defaultApi(id));
            if (api == null || api.isBlank()) {
                warn(warnSink, "更新线路 " + id + " 缺少 " + prefix + "api 配置，已跳过");
                continue;
            }
            parsed.add(new Source(id,
                props.getProperty(prefix + "displayName", defaultDisplayName(id)),
                api.trim(),
                clampInt(props.getProperty(prefix + "checkTimeoutMs"), 1000, 120_000, defaultFor(id, "checkTimeoutMs")),
                clampInt(props.getProperty(prefix + "checkAttempts"), 1, 5, defaultFor(id, "checkAttempts")),
                clampInt(props.getProperty(prefix + "downloadAttempts"), 1, 10, defaultFor(id, "downloadAttempts")),
                clampInt(props.getProperty(prefix + "downloadHeaderTimeoutMs"),
                    5_000, 600_000, defaultFor(id, "downloadHeaderTimeoutMs")),
                clampInt(props.getProperty(prefix + "stallTimeoutMs"),
                    5_000, 1_800_000, defaultFor(id, "stallTimeoutMs")),
                clampInt(props.getProperty(prefix + "breakerThreshold"), 1, 10, defaultFor(id, "breakerThreshold")),
                clampLong(props.getProperty(prefix + "breakerCooldownMs"),
                    10_000L, 86_400_000L, 600_000L)));
        }
        if (parsed.isEmpty()) {
            warn(warnSink, "未配置任何可用更新线路，回退默认 " + DEFAULT_SOURCES);
            return fromProperties(new Properties(), warnSink);
        }
        return new UpdateSources(parsed, connectTimeoutMs);
    }

    /** Faithful per-line code fallbacks matching the shipped update.properties. */
    private static int defaultFor(String id, String key) {
        boolean gitee = "gitee".equals(id);
        return switch (key) {
            case "checkTimeoutMs" -> gitee ? 8_000 : 12_000;
            case "checkAttempts" -> gitee ? 2 : 1;
            case "downloadAttempts" -> gitee ? 2 : 3;
            case "downloadHeaderTimeoutMs" -> gitee ? 30_000 : 60_000;
            case "stallTimeoutMs" -> gitee ? 30_000 : 60_000;
            case "breakerThreshold" -> gitee ? 2 : 3;
            default -> 0;
        };
    }

    private static String defaultApi(String id) {
        if ("gitee".equals(id)) return GITEE_DEFAULT_API;
        if ("github".equals(id)) return AppVersion.RELEASES_API;
        return null;
    }

    private static String defaultDisplayName(String id) {
        if ("gitee".equals(id)) return "Gitee";
        if ("github".equals(id)) return "GitHub";
        return id;
    }

    private static boolean seen(List<Source> parsed, String id) {
        for (Source s : parsed) {
            if (s.id.equals(id)) return true;
        }
        return false;
    }

    private static int clampInt(String raw, int min, int max, int def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long clampLong(String raw, long min, long max, long def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            long value = Long.parseLong(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void warn(Consumer<String> warnSink, String message) {
        if (warnSink != null) warnSink.accept(message);
    }
}
