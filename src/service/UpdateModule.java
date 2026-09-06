package service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import model.AppVersion;
import util.AppPaths;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The single online-update module: multi-line version check, asset selection,
 * download, SHA-256 verification, install preparation, and installer launch.
 *
 * <p>Update lines come from {@link UpdateSources} in priority order (primary
 * Gitee mirror, backup GitHub — GitHub stays the source of truth for tags and
 * artifacts). Everything runs serially: the primary line is tried first and the
 * backup only after the primary failed (timeout, network error, 404, missing
 * version, hash mismatch). Failures charge a per-line breaker that skips a
 * flapping line for a cooldown. Only a hash-verified download yields a
 * {@link VerifiedPackage}; the installer process must be confirmed started
 * before the app exits, and a launch failure keeps the app running with an
 * error report. Downloads resume from an interrupted {@code .part} file (within
 * one call and across app runs) and the updates dir is pruned of
 * never-reusable leftovers. Release JSON (GitHub- and Gitee-compatible field
 * names) is parsed with Gson. All line-level behavior is reported through the
 * {@link LogSink}: which line was used, why one failed, versions and hashes.
 */
public final class UpdateModule {
    private static final Pattern SHA256SUM_LINE = Pattern.compile("(?i)^([0-9a-f]{64}) {2}([^\\r\\n]+)$");
    private static final int MAX_CHECKSUM_MANIFEST_CHARS = 1024 * 1024;
    /** .part files older than this are garbage; younger ones feed cross-run resume. */
    private static final long PART_FILE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000L;
    /** downloadFrom defaults when no configured line is in play (tests, manual use). */
    private static final int DEFAULT_DOWNLOAD_ATTEMPTS = 3;
    private static final int DEFAULT_STALL_TIMEOUT_MS = 60_000;
    private static final int DEFAULT_MANIFEST_TIMEOUT_MS = 20_000;

    // ---------- data ----------

    public static final class Asset {
        public final String name;
        public final String downloadUrl;
        public final long sizeBytes;

        public Asset(String name, String downloadUrl, long sizeBytes) {
            this.name = name != null ? name : "";
            this.downloadUrl = downloadUrl != null ? downloadUrl : "";
            this.sizeBytes = Math.max(0L, sizeBytes);
        }

        public String lowerName() {
            return name.toLowerCase(Locale.ROOT);
        }

        public boolean isZip() {
            return lowerName().endsWith(".zip");
        }

        public boolean isMsi() {
            return lowerName().endsWith(".msi");
        }

        public boolean isExe() {
            return lowerName().endsWith(".exe");
        }

        public boolean isChecksumManifest() {
            return "sha256sums.txt".equals(lowerName());
        }
    }

    public static final class Release {
        public final String sourceId;
        public final String sourceName;
        public final String tagName;
        public final String htmlUrl;
        public final String body;
        public final List<Asset> assets;

        public Release(String sourceId, String sourceName, String tagName, String htmlUrl,
                       String body, List<Asset> assets) {
            this.sourceId = sourceId != null ? sourceId : "";
            this.sourceName = sourceName != null ? sourceName : "";
            this.tagName = tagName != null ? tagName : "";
            this.htmlUrl = htmlUrl != null ? htmlUrl : "";
            this.body = body != null ? body : "";
            this.assets = assets != null
                ? java.util.Collections.unmodifiableList(new ArrayList<>(assets))
                : java.util.Collections.emptyList();
        }

        /**
         * Pick best installable asset honoring the install type. A writable install
         * dir (portable app-image) takes the zip. A non-writable dir (MSI install
         * under Program Files) must NOT take the zip — its apply script copies into
         * the install dir and would fail without elevation — so the msi (msiexec
         * triggers its own UAC prompt) or exe is offered instead.
         */
        public Optional<Asset> preferredWindowsAsset(boolean installDirWritable) {
            Asset[] best = bestPerType();
            if (installDirWritable) {
                if (best[0] != null) return Optional.of(best[0]);
                if (best[1] != null) return Optional.of(best[1]);
                if (best[2] != null) return Optional.of(best[2]);
                return Optional.empty();
            }
            if (best[1] != null) return Optional.of(best[1]);
            if (best[2] != null) return Optional.of(best[2]);
            return Optional.empty();
        }

        /** Best zip / msi / exe candidate by score, in that index order. */
        private Asset[] bestPerType() {
            Asset bestZip = null;
            Asset bestMsi = null;
            Asset bestExe = null;
            int bestZipScore = Integer.MIN_VALUE;
            int bestMsiScore = Integer.MIN_VALUE;
            int bestExeScore = Integer.MIN_VALUE;
            for (Asset a : assets) {
                if (a == null || a.downloadUrl.isEmpty() || a.name.isEmpty()
                    || !isHttpsUrl(a.downloadUrl)) continue;
                int score = scoreAsset(a);
                if (a.isZip() && score > bestZipScore) {
                    bestZipScore = score;
                    bestZip = a;
                } else if (a.isMsi() && score > bestMsiScore) {
                    bestMsiScore = score;
                    bestMsi = a;
                } else if (a.isExe() && score > bestExeScore) {
                    bestExeScore = score;
                    bestExe = a;
                }
            }
            return new Asset[]{bestZip, bestMsi, bestExe};
        }

        /** The single SHA256SUMS.txt asset, or empty when absent or duplicated. */
        public Optional<Asset> checksumManifest() {
            Asset manifest = null;
            for (Asset asset : assets) {
                if (asset != null && !asset.downloadUrl.isEmpty()
                    && isHttpsUrl(asset.downloadUrl) && asset.isChecksumManifest()) {
                    if (manifest != null) return Optional.empty();
                    manifest = asset;
                }
            }
            return Optional.ofNullable(manifest);
        }

        private static boolean isHttpsUrl(String value) {
            try {
                URI uri = URI.create(value != null ? value.trim() : "");
                return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isEmpty();
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private static int scoreAsset(Asset a) {
            String n = a.lowerName();
            int s = 0;
            if (n.contains("ppoe") || n.contains("pppoe") || n.contains("one-key") || n.contains("dialer")) {
                s += 100;
            }
            if (n.contains("win") || n.contains("windows")) s += 20;
            if (n.contains("portable") || n.contains("app-image") || n.contains("appimage")) s += 15;
            if (n.contains("debug") || n.contains("sources") || n.contains("src")) s -= 50;
            if (a.sizeBytes > 5_000_000L) s += 5;
            return s;
        }
    }

    public static final class CheckResult {
        public final boolean updateAvailable;
        /** True when the line was reachable and its payload parsed, update or not. */
        public final boolean sourceOk;
        public final String sourceId;
        public final String sourceName;
        public final String currentVersion;
        public final String latestTag;
        public final String releaseUrl;
        public final String message;
        public final Release release;
        /** Why the check failed; null when {@link #sourceOk}. */
        public final FailureKind failureKind;

        public CheckResult(boolean updateAvailable, boolean sourceOk, String sourceId,
                           String sourceName, String currentVersion, String latestTag,
                           String releaseUrl, String message, Release release,
                           FailureKind failureKind) {
            this.updateAvailable = updateAvailable;
            this.sourceOk = sourceOk;
            this.sourceId = sourceId != null ? sourceId : "";
            this.sourceName = sourceName != null ? sourceName : "";
            this.currentVersion = currentVersion;
            this.latestTag = latestTag;
            this.releaseUrl = releaseUrl;
            this.message = message;
            this.release = release;
            this.failureKind = failureKind;
        }

        public boolean hasInstallableAsset(boolean installDirWritable) {
            return release != null
                && release.preferredWindowsAsset(installDirWritable).isPresent()
                && release.checksumManifest().isPresent();
        }
    }

    /** A downloaded package that passed SHA-256 verification. Nothing else may be installed. */
    public static final class VerifiedPackage {
        public final File file;
        public final Asset asset;
        public final Release release;

        public VerifiedPackage(File file, Asset asset, Release release) {
            this.file = file;
            this.asset = asset;
            this.release = release;
        }
    }

    /** Staged + scripted update, ready for {@link #launchInstall}. */
    public static final class PreparedUpdate {
        public final File applyScript;
        public final String kind; // zip | msi | exe

        public PreparedUpdate(File applyScript, String kind) {
            this.applyScript = applyScript;
            this.kind = kind;
        }
    }

    public interface Progress {
        void onProgress(long downloaded, long total);

        void onStatus(String message);
    }

    /** Behavior log seam: line switches, failure reasons, versions, hashes. */
    @FunctionalInterface
    public interface LogSink {
        enum Level { INFO, WARNING, ERROR }

        void log(String message, Level level);
    }

    // ---------- failure taxonomy ----------

    /** 更新失败原因分类：驱动行为日志、降级与熔断决策。 */
    public enum FailureKind {
        CONNECT_TIMEOUT("连接超时"),
        TIMEOUT("响应超时"),
        STALL("下载停滞超时"),
        NETWORK("网络错误"),
        HTTP_STATUS("HTTP 错误"),
        VERSION_MISSING("版本不存在"),
        PARSE("响应解析失败"),
        HASH_MISMATCH("哈希校验失败"),
        CANCELLED("已取消"),
        UNKNOWN("未知错误");

        private final String label;

        FailureKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** User cancellation — aborts the whole flow: no failover, no breaker charge. */
    public static final class UpdateCancelledException extends IOException {
        public UpdateCancelledException() {
            super("下载已取消");
        }

        public UpdateCancelledException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The transfer received no bytes for the line's stall window. */
    public static final class StallTimeoutException extends IOException {
        public StallTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Non-2xx status on an asset or checksum-manifest fetch. */
    public static final class HttpStatusException extends IOException {
        public final int statusCode;

        public HttpStatusException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /** SHA-256 mismatch — the package is corrupt or tampered; it is never installed. */
    public static final class HashMismatchException extends IOException {
        public final String expectedSha256;
        public final String actualSha256;

        public HashMismatchException(String expectedSha256, String actualSha256) {
            super("更新包 SHA-256 校验失败 expected=" + expectedSha256
                + " actual=" + actualSha256);
            this.expectedSha256 = expectedSha256;
            this.actualSha256 = actualSha256;
        }
    }

    static FailureKind classify(Throwable e) {
        if (e instanceof UpdateCancelledException) return FailureKind.CANCELLED;
        if (e instanceof HashMismatchException) return FailureKind.HASH_MISMATCH;
        if (e instanceof StallTimeoutException) return FailureKind.STALL;
        if (e instanceof HttpStatusException h) {
            return h.statusCode == 404 ? FailureKind.VERSION_MISSING : FailureKind.HTTP_STATUS;
        }
        if (e instanceof java.net.http.HttpConnectTimeoutException) return FailureKind.CONNECT_TIMEOUT;
        if (e instanceof java.net.http.HttpTimeoutException) return FailureKind.TIMEOUT;
        if (e instanceof java.net.SocketTimeoutException) return FailureKind.TIMEOUT;
        if (e instanceof java.util.concurrent.CancellationException) return FailureKind.CANCELLED;
        if (e instanceof IOException) return FailureKind.NETWORK;
        return FailureKind.UNKNOWN;
    }

    // ---------- breaker ----------

    /**
     * In-memory simple breaker: after {@code breakerThreshold} consecutive
     * failures a line is skipped for {@code breakerCooldownMs}; when the cooldown
     * expires one half-open probe is allowed and a failed probe re-opens the line
     * for a full cooldown. Any success resets the line. Not persisted — every
     * app start starts with all lines enabled.
     */
    static final class SourceBreaker {
        private static final class State {
            int consecutiveFailures;
            long openUntilMs;
        }

        private final LongSupplier clock;
        private final java.util.Map<String, State> states = new java.util.HashMap<>();

        SourceBreaker(LongSupplier clock) {
            this.clock = clock != null ? clock : System::currentTimeMillis;
        }

        boolean isOpen(UpdateSources.Source source) {
            State st = states.get(source.id);
            return st != null && clock.getAsLong() < st.openUntilMs;
        }

        long cooldownRemainingMs(UpdateSources.Source source) {
            State st = states.get(source.id);
            return st == null ? 0L : Math.max(0L, st.openUntilMs - clock.getAsLong());
        }

        void recordSuccess(UpdateSources.Source source) {
            states.remove(source.id);
        }

        void recordFailure(UpdateSources.Source source) {
            State st = states.computeIfAbsent(source.id, k -> new State());
            long now = clock.getAsLong();
            if (st.openUntilMs > 0 && now >= st.openUntilMs) {
                // the half-open probe failed: re-open immediately for a full cooldown
                st.openUntilMs = now + source.breakerCooldownMs;
                st.consecutiveFailures = 0;
                return;
            }
            st.consecutiveFailures++;
            if (st.consecutiveFailures >= source.breakerThreshold) {
                st.openUntilMs = now + source.breakerCooldownMs;
                st.consecutiveFailures = 0;
            }
        }
    }

    // ---------- seams ----------

    /** Plain-text HTTP GET seam (release metadata, checksum manifest). */
    @FunctionalInterface
    public interface ContentFetcher {
        final class FetchedText {
            public final int statusCode;
            public final String body;

            public FetchedText(int statusCode, String body) {
                this.statusCode = statusCode;
                this.body = body != null ? body : "";
            }
        }

        FetchedText get(URI uri, Duration timeout) throws Exception;
    }

    /** Binary download stream seam. */
    @FunctionalInterface
    public interface StreamOpener {
        final class DownloadStream implements AutoCloseable {
            public final InputStream stream;
            public final long contentLength;
            public final int statusCode;

            public DownloadStream(InputStream stream, long contentLength, int statusCode) {
                this.stream = stream;
                this.contentLength = contentLength;
                this.statusCode = statusCode;
            }

            @Override public void close() throws IOException {
                stream.close();
            }
        }

        /**
         * Open the asset stream. {@code rangeStart > 0} asks the opener to send a
         * Range request: a 206 response resumes the {@code .part}, any other 2xx
         * means the caller restarts from zero. {@code headersTimeout} bounds the
         * wait for the response headers; the body itself is stall-guarded by the
         * caller.
         */
        DownloadStream open(URI uri, long rangeStart, Duration headersTimeout) throws Exception;
    }

    /** Installer launch seam. Production starts the apply script via cmd. */
    @FunctionalInterface
    public interface InstallerLauncher {
        void launch(File applyScript) throws IOException;
    }

    // ---------- instance ----------

    private static final Gson GSON = new Gson();

    private final File updatesDir;
    private final ContentFetcher fetcher;
    private final StreamOpener opener;
    private final InstallerLauncher launcher;
    private final UpdateSources sources;
    private final LogSink logSink;
    private final java.util.function.LongSupplier clock;
    private final SourceBreaker breaker;

    public UpdateModule(File updatesDir, ContentFetcher fetcher, StreamOpener opener,
                        InstallerLauncher launcher, UpdateSources sources, LogSink logSink,
                        java.util.function.LongSupplier clock) {
        this.updatesDir = updatesDir != null ? updatesDir : defaultUpdatesDir();
        this.sources = sources != null ? sources : UpdateSources.defaults();
        this.fetcher = fetcher != null ? fetcher : defaultContentFetcher(this.sources.connectTimeoutMs);
        this.opener = opener != null ? opener : defaultStreamOpener(this.sources.connectTimeoutMs);
        this.launcher = launcher != null ? launcher : defaultInstallerLauncher();
        this.logSink = logSink != null ? logSink : (message, level) -> { };
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.breaker = new SourceBreaker(this.clock);
        //noinspection ResultOfMethodCallIgnored
        this.updatesDir.mkdirs();
    }

    private void log(LogSink.Level level, String message) {
        logSink.log(message, level);
    }

    public static File defaultUpdatesDir() {
        String appData = System.getenv("APPDATA");
        File dir = appData != null
            ? new File(appData, "PPoEDialer" + File.separator + "updates")
            : new File(System.getProperty("user.home"), "PPoEDialer" + File.separator + "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static ContentFetcher defaultContentFetcher(int connectTimeoutMs) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        return (uri, timeout) -> {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", AppVersion.USER_AGENT)
                .GET()
                .build();
            // JDK HttpClient does not timeout DNS resolution; campus networks often
            // blackhole the GitHub asset CDN. Bound the whole exchange hard.
            java.net.http.HttpResponse<String> resp = sendBounded(client, req,
                java.net.http.HttpResponse.BodyHandlers.ofString(), timeout);
            return new ContentFetcher.FetchedText(resp.statusCode(), resp.body());
        };
    }

    private static StreamOpener defaultStreamOpener(int connectTimeoutMs) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            // NORMAL: never follow an HTTPS -> HTTP redirect downgrade
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        return (uri, rangeStart, headersTimeout) -> {
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(headersTimeout)
                .header("User-Agent", AppVersion.USER_AGENT)
                .header("Accept", "application/octet-stream")
                .GET();
            if (rangeStart > 0) {
                builder.header("Range", "bytes=" + rangeStart + "-");
            }
            java.net.http.HttpResponse<InputStream> resp = sendBounded(client, builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream(), headersTimeout);
            long len = resp.headers().firstValueAsLong("Content-Length").orElse(0L);
            return new StreamOpener.DownloadStream(resp.body(), len, resp.statusCode());
        };
    }

    /** Cancels exchanges whose hard ceiling expired — cancellation aborts the socket; a mere future timeout would leave them hanging. */
    private static final java.util.concurrent.ScheduledExecutorService SEND_TIMEOUT_SCHEDULER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UpdateHttpTimeout");
            t.setDaemon(true);
            return t;
        });

    /**
     * send() with a hard ceiling over the whole exchange (DNS + connect + TLS +
     * response headers). The per-request timeout does not cover DNS resolution,
     * so a blackholed CDN can otherwise hang a worker thread forever. When the
     * ceiling fires, the future is cancelled, which aborts the underlying
     * exchange instead of leaving it running in the background.
     */
    private static <T> java.net.http.HttpResponse<T> sendBounded(
        java.net.http.HttpClient client, java.net.http.HttpRequest request,
        java.net.http.HttpResponse.BodyHandler<T> bodyHandler, Duration requestTimeout)
        throws IOException {
        long hardMs = Math.max(30_000L, requestTimeout.toMillis() + 10_000L);
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> future =
            client.sendAsync(request, bodyHandler);
        java.util.concurrent.ScheduledFuture<?> ceiling = SEND_TIMEOUT_SCHEDULER.schedule(
            () -> future.cancel(true), hardMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException
                 | java.util.concurrent.CancellationException e) {
            Throwable cause = e instanceof java.util.concurrent.CompletionException
                && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("请求无响应（网络或 CDN 可能被拦截），已超时中止", cause);
        } finally {
            ceiling.cancel(false);
        }
    }

    /**
     * Runs the apply script in a hidden console: Java pipes the child's stdio, so
     * Windows creates cmd.exe with CREATE_NO_WINDOW — no flashing console during
     * the update (the old {@code start "title" script} invocation opened a visible
     * window and could leave it open on failure paths). The batch outlives this
     * app: it waits for our exit, applies the package, and relaunches the exe.
     */
    static InstallerLauncher defaultInstallerLauncher() {
        return script -> {
            if (script == null || !script.isFile()) {
                throw new IOException("更新脚本不存在");
            }
            new ProcessBuilder("cmd.exe", "/c", script.getAbsolutePath())
                .directory(script.getParentFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        };
    }

    // ---------- check ----------

    /**
     * Multi-line version check. Walks the configured lines serially in priority
     * order (primary first); a line that answers — with an update or with
     * "already latest" — wins immediately, and only a line that failed all of
     * its retries falls through to the next one. "No update" from a healthy
     * primary is a definitive answer, not a failure, so the backup is not
     * consulted for it.
     */
    public CheckResult check(String currentVersion) {
        String current = currentVersion != null ? currentVersion : AppVersion.NUMERIC;
        List<UpdateSources.Source> chain = sources.enabled();
        if (chain.isEmpty()) {
            return failedCheck(null, current, FailureKind.UNKNOWN, "未配置可用更新线路");
        }
        StringBuilder lines = new StringBuilder();
        for (UpdateSources.Source src : chain) {
            if (lines.length() > 0) lines.append(" → ");
            lines.append(src.displayName);
        }
        log(LogSink.Level.INFO, "[更新] 检查更新 当前=" + AppVersion.DISPLAY
            + " 线路=" + lines);
        CheckResult last = null;
        for (UpdateSources.Source src : chain) {
            if (breaker.isOpen(src)) {
                log(LogSink.Level.WARNING, "[更新] 线路=" + src.displayName + " 熔断中（剩余 "
                    + breaker.cooldownRemainingMs(src) / 1000 + "s），跳过");
                continue;
            }
            CheckResult result = checkWithRetry(src, current);
            if (result.sourceOk) {
                breaker.recordSuccess(src);
                return result;
            }
            breaker.recordFailure(src);
            if (breaker.isOpen(src)) {
                log(LogSink.Level.WARNING, "[更新] 线路=" + src.displayName
                    + " 连续失败达到阈值，熔断开启，冷却 " + src.breakerCooldownMs / 1000 + "s");
            }
            last = result;
        }
        if (last == null) {
            return failedCheck(null, current, FailureKind.UNKNOWN, "所有更新线路均在熔断冷却中");
        }
        return last;
    }

    /** One line's check with its configured short timeout and retry budget. */
    private CheckResult checkWithRetry(UpdateSources.Source src, String current) {
        int attempts = Math.max(1, src.checkAttempts);
        CheckResult last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long started = clock.getAsLong();
            CheckResult result = checkOnce(src, current);
            long elapsedMs = clock.getAsLong() - started;
            if (result.sourceOk) {
                log(LogSink.Level.INFO, "[更新] 线路=" + src.displayName + " 检查成功 耗时="
                    + elapsedMs + "ms tag=" + result.latestTag
                    + (result.updateAvailable ? "（有更新）" : "（无更新）"));
                return result;
            }
            log(LogSink.Level.WARNING, "[更新] 线路=" + src.displayName + " 检查失败("
                + attempt + "/" + attempts + ") 原因="
                + (result.failureKind != null ? result.failureKind.label() : FailureKind.UNKNOWN.label())
                + " 耗时=" + elapsedMs + "ms");
            last = result;
            if (attempt < attempts) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last;
    }

    /**
     * Best-effort probe: can this install write into its own directory? Portable
     * app-image installs can (zip updates apply by copy); MSI installs under
     * Program Files cannot and must take the MSI asset instead.
     */
    public static boolean isInstallDirWritable() {
        File dir = resolveInstallDir();
        if (dir == null || !dir.isDirectory()) return false;
        try {
            File probe = File.createTempFile("ppoe_probe_", ".tmp", dir);
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** One probe against one line: no retry, no breaker — the caller owns those. */
    public CheckResult checkOnce(UpdateSources.Source source, String currentVersion) {
        String current = currentVersion != null ? currentVersion : AppVersion.NUMERIC;
        try {
            ContentFetcher.FetchedText resp = fetcher.get(
                requireHttpsUri(source.apiUrl, "更新接口"),
                Duration.ofMillis(source.checkTimeoutMs));
            if (resp.statusCode != 200) {
                if (resp.statusCode == 404) {
                    return failedCheck(source, current, FailureKind.VERSION_MISSING,
                        "版本不存在（HTTP 404，该线路可能尚未同步此发布）");
                }
                String hint = resp.statusCode == 403 ? "（更新接口限流，稍后再试或到发布页查看）" : "";
                return failedCheck(source, current, FailureKind.HTTP_STATUS,
                    "HTTP " + resp.statusCode + hint);
            }
            Release release = parseReleaseJson(resp.body, source);
            String tag = release.tagName;
            if (tag == null || tag.isEmpty()) {
                return failedCheck(source, current, FailureKind.PARSE, "未解析到最新版本号");
            }
            int cmp = AppVersion.compareNumeric(current, tag);
            if (cmp < 0) {
                String msg = "发现新版本 " + tag + "（当前 " + AppVersion.DISPLAY
                    + "，线路 " + source.displayName + "）";
                boolean writable = isInstallDirWritable();
                if (release.preferredWindowsAsset(writable).isPresent()
                    && release.checksumManifest().isPresent()) {
                    msg += "\n可下载: " + release.preferredWindowsAsset(writable).get().name;
                    if (!writable) {
                        msg += "\n（安装目录不可写，已选择 MSI 安装包）";
                    }
                } else if (release.preferredWindowsAsset(writable).isPresent()) {
                    msg += "\n（发布包缺少 SHA256SUMS.txt，已禁用自动安装，请到发布页手动确认）";
                } else if (release.preferredWindowsAsset(true).isPresent()) {
                    msg += "\n（当前安装方式无法自动应用更新包，请到发布页手动下载 MSI）";
                } else {
                    msg += "\n（发布页暂无匹配的 Windows 安装包，可手动打开网页）";
                }
                return new CheckResult(true, true, source.id, source.displayName, current,
                    tag, release.htmlUrl, msg, release, null);
            }
            return new CheckResult(false, true, source.id, source.displayName, current,
                tag, release.htmlUrl,
                "已是最新版本（" + AppVersion.DISPLAY + "，线路 " + source.displayName + "）",
                release, null);
        } catch (JsonParseException e) {
            return failedCheck(source, current, FailureKind.PARSE,
                "更新响应解析失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedCheck(source, current, FailureKind.CANCELLED, "检查更新已中断");
        } catch (Exception e) {
            String detail = e.getMessage() != null && !e.getMessage().isEmpty()
                ? e.getMessage() : e.getClass().getSimpleName();
            return failedCheck(source, current, classify(e), detail);
        }
    }

    private CheckResult failedCheck(UpdateSources.Source source, String current,
                                    FailureKind kind, String detail) {
        String name = source != null ? source.displayName : "";
        String prefix = name.isEmpty() ? "检查更新失败: " : "检查更新失败[" + name + "]: ";
        return new CheckResult(false, false,
            source != null ? source.id : "", name, current, null, null,
            prefix + detail, null, kind);
    }

    /** Gson parse of a GitHub/Gitee latest-release payload, tagged with its line. */
    public static Release parseReleaseJson(String json) {
        return parseReleaseJson(json, null, null);
    }

    public static Release parseReleaseJson(String json, UpdateSources.Source source) {
        return parseReleaseJson(json,
            source != null ? source.id : null,
            source != null ? source.displayName : null);
    }

    private static Release parseReleaseJson(String json, String sourceId, String sourceName) {
        if (json == null || json.trim().isEmpty()) {
            throw new JsonParseException("更新响应为空");
        }
        ReleaseJson parsed = GSON.fromJson(json, ReleaseJson.class);
        if (parsed == null) {
            throw new JsonParseException("更新响应为空");
        }
        List<Asset> assets = new ArrayList<>();
        if (parsed.assets != null) {
            for (AssetJson a : parsed.assets) {
                if (a == null || a.name == null || a.browser_download_url == null) continue;
                assets.add(new Asset(a.name, a.browser_download_url, a.size));
            }
        }
        return new Release(sourceId, sourceName, parsed.tag_name, parsed.html_url,
            parsed.body != null ? parsed.body : "", assets);
    }

    private static final class ReleaseJson {
        String tag_name;
        String html_url;
        String body;
        List<AssetJson> assets;
    }

    private static final class AssetJson {
        String name;
        String browser_download_url;
        long size;
    }

    // ---------- download ----------

    /**
     * Serial multi-line download. Walks the configured lines starting at the
     * line that served {@code primary}: the primary release/asset is downloaded
     * as-is; before switching to a backup line that line is re-checked and its
     * tag must not be lower than the primary's (a lower-version backup must
     * never overwrite the update) nor lower than the running version. Cancellation
     * aborts everything — no failover, no breaker charge. Lines are never
     * fetched concurrently.
     *
     * @throws IOException when every usable line failed; the local install is
     *     untouched in that case
     */
    public VerifiedPackage downloadWithFailover(CheckResult primary, Asset primaryAsset,
                                                Progress progress,
                                                AtomicBoolean cancel) throws Exception {
        Progress p = java.util.Objects.requireNonNull(progress, "progress");
        AtomicBoolean cancelled = cancel != null ? cancel : new AtomicBoolean(false);
        List<UpdateSources.Source> chain = orderedChain(primary != null ? primary.sourceId : "");
        String primaryTag = primary != null ? primary.latestTag : null;
        String current = primary != null ? primary.currentVersion : AppVersion.NUMERIC;
        Exception lastFailure = null;

        for (UpdateSources.Source src : chain) {
            Release release;
            Asset asset;
            if (primary != null && src.id.equals(primary.sourceId)) {
                release = primary.release;
                asset = primaryAsset;
            } else {
                // Failover: the backup line serves its own metadata, possibly a
                // different tag. Guard both consistency rules before touching it.
                if (cancelled.get()) throw new UpdateCancelledException();
                log(LogSink.Level.INFO, "[更新] 切换线路 → " + src.displayName);
                if (breaker.isOpen(src)) {
                    log(LogSink.Level.WARNING, "[更新] 线路=" + src.displayName
                        + " 熔断中（剩余 " + breaker.cooldownRemainingMs(src) / 1000 + "s），跳过");
                    continue;
                }
                CheckResult alt = checkWithRetry(src, current);
                if (!alt.sourceOk) {
                    breaker.recordFailure(src);
                    lastFailure = new IOException(alt.message != null ? alt.message
                        : "线路 " + src.displayName + " 检查失败");
                    continue;
                }
                breaker.recordSuccess(src);
                if (primaryTag != null && !primaryTag.isEmpty()
                    && AppVersion.compareNumeric(alt.latestTag, primaryTag) < 0) {
                    log(LogSink.Level.WARNING, "[更新] 主备版本不一致 主="
                        + primary.sourceName + " " + primaryTag + " 备="
                        + alt.sourceName + " " + alt.latestTag
                        + "：低版本备源不允许覆盖更新，已停止本次更新");
                    throw new IOException("备用线路版本（" + alt.latestTag
                        + "）低于主线路（" + primaryTag + "），已保留当前版本");
                }
                if (AppVersion.compareNumeric(current, alt.latestTag) >= 0) {
                    log(LogSink.Level.WARNING, "[更新] 线路=" + src.displayName
                        + " 版本 " + alt.latestTag + " 不高于当前版本 " + AppVersion.DISPLAY
                        + "，不安装");
                    throw new IOException("备用线路版本不高于当前版本，无需更新");
                }
                if (alt.latestTag != null && !alt.latestTag.isEmpty()
                    && primaryTag != null && !primaryTag.isEmpty()
                    && AppVersion.compareNumeric(alt.latestTag, primaryTag) > 0) {
                    log(LogSink.Level.WARNING, "[更新] 主备版本不一致 主=" + primary.sourceName
                        + " " + primaryTag + " 备=" + alt.sourceName + " " + alt.latestTag
                        + "：备源版本更高，按发布真相源继续");
                }
                release = alt.release;
                asset = alt.release.preferredWindowsAsset(isInstallDirWritable()).orElse(null);
                if (asset == null || release.checksumManifest().isEmpty()) {
                    log(LogSink.Level.WARNING, "[更新] 线路=" + src.displayName
                        + " 无可自动安装的更新包（缺资产或 SHA256SUMS.txt），跳过");
                    lastFailure = new IOException("线路 " + src.displayName
                        + " 无可安装的更新包");
                    continue;
                }
            }

            try {
                VerifiedPackage pkg = downloadFrom(src, release, asset, p, cancelled);
                breaker.recordSuccess(src);
                log(LogSink.Level.INFO, "[更新] 下载完成 线路=" + src.displayName
                    + " tag=" + release.tagName + " 资产=" + asset.name
                    + " 文件=" + pkg.file.getAbsolutePath());
                return pkg;
            } catch (Exception e) {
                if (cancelled.get() || e instanceof UpdateCancelledException) {
                    throw e;
                }
                breaker.recordFailure(src);
                log(LogSink.Level.WARNING, "[更新] 线路=" + src.displayName + " 下载失败 原因="
                    + classify(e).label() + " tag=" + release.tagName
                    + (e.getMessage() != null && !e.getMessage().isEmpty()
                        ? " 详情=" + e.getMessage() : ""));
                lastFailure = e;
            }
        }
        throw new IOException("所有更新线路均下载失败，已保留当前版本"
            + (lastFailure != null && lastFailure.getMessage() != null
                ? "；最后错误：" + lastFailure.getMessage() : ""), lastFailure);
    }

    /** Configured lines with the line that served {@code primarySourceId} first. */
    private List<UpdateSources.Source> orderedChain(String primarySourceId) {
        List<UpdateSources.Source> chain = new ArrayList<>(sources.enabled());
        if (primarySourceId != null && !primarySourceId.isEmpty()) {
            for (int i = 1; i < chain.size(); i++) {
                if (chain.get(i).id.equals(primarySourceId)) {
                    chain.add(0, chain.remove(i));
                    break;
                }
            }
        }
        return chain;
    }

    /**
     * Download the asset from one line and verify SHA-256, resuming from an
     * interrupted {@code .part} (same call or a previous run) when the server
     * honors Range. Attempts, header timeout and the stall watchdog come from
     * the line config; {@code src == null} falls back to conservative defaults
     * (tests and manual single-line use). Only a verified file becomes a
     * {@link VerifiedPackage}; the temp file is removed on verification failure
     * or cancellation, and kept otherwise so a later attempt can resume.
     */
    public VerifiedPackage download(Release release, Asset asset,
                                    Progress progress, AtomicBoolean cancel) throws Exception {
        return downloadFrom(null, release, asset, progress, cancel);
    }

    private VerifiedPackage downloadFrom(UpdateSources.Source src, Release release, Asset asset,
                                         Progress progress, AtomicBoolean cancel) throws Exception {
        if (release == null || asset == null) {
            throw new IOException("缺少发布信息或资产");
        }
        int maxAttempts = src != null ? src.downloadAttempts : DEFAULT_DOWNLOAD_ATTEMPTS;
        long stallTimeoutMs = src != null ? src.stallTimeoutMs : DEFAULT_STALL_TIMEOUT_MS;
        Duration manifestTimeout = Duration.ofMillis(src != null
            ? src.checkTimeoutMs : DEFAULT_MANIFEST_TIMEOUT_MS);
        Duration headersTimeout = Duration.ofMillis(src != null
            ? src.downloadHeaderTimeoutMs : 60_000);
        Progress p = java.util.Objects.requireNonNull(progress, "progress");
        AtomicBoolean cancelled = cancel != null ? cancel : new AtomicBoolean(false);

        pruneStaleUpdateFiles();

        String safeName = sanitizeFileName(asset.name);
        File out = new File(updatesDir, safeName);
        File part = new File(updatesDir, safeName + ".part");

        URI target = requireHttpsUri(asset.downloadUrl, "更新包");
        String expectedSha256 = fetchExpectedSha256(release, asset, p, cancelled, manifestTimeout);
        String lineName = src != null ? src.displayName : "默认";

        p.onStatus("正在下载 " + asset.name + " …");
        long downloaded = 0L;
        Exception lastFailure = null;
        boolean lastStalled = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long onDisk = part.isFile() ? part.length() : 0L;
            // Campus networks routinely blackhole the update CDN mid-transfer;
            // a blocking read() must not hang forever, so a watchdog closes the
            // stream after a no-data window or when the user cancels, and the
            // transfer either resumes from the .part or aborts with a clear message.
            final AtomicLong lastDataNanos = new AtomicLong(System.nanoTime());
            final AtomicBoolean stalled = new AtomicBoolean(false);
            final AtomicBoolean done = new AtomicBoolean(false);
            StreamOpener.DownloadStream ds = null;
            java.io.OutputStream os = null;
            Thread watchdog = null;
            try {
                ds = opener.open(target, onDisk, headersTimeout);
                if (ds.statusCode == 416 && asset.sizeBytes > 0 && onDisk == asset.sizeBytes) {
                    // .part already reaches the end of the remote file: go verify it.
                    downloaded = onDisk;
                    break;
                }
                if (ds.statusCode == 416) {
                    // Stale or incompatible .part: drop it and restart from zero.
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    if (attempt < maxAttempts) {
                        continue;
                    }
                    throw new IOException("本地断点与服务器不匹配，已重置下载");
                }
                if (ds.statusCode / 100 != 2) {
                    throw new HttpStatusException("下载失败 HTTP " + ds.statusCode, ds.statusCode);
                }
                boolean resumed = onDisk > 0 && ds.statusCode == 206;
                downloaded = resumed ? onDisk : 0L;
                os = resumed
                    ? Files.newOutputStream(part.toPath(),
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                    : Files.newOutputStream(part.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                watchdog = startStallWatchdog(ds.stream, lastDataNanos, done, stalled,
                    cancelled, stallTimeoutMs);
                long total = asset.sizeBytes > 0 ? asset.sizeBytes
                    : (resumed ? ds.contentLength + downloaded : ds.contentLength);
                InputStream in = new BufferedInputStream(ds.stream);
                byte[] buf = new byte[64 * 1024];
                int n;
                long lastReport = downloaded;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.get()) {
                        throw new UpdateCancelledException();
                    }
                    if (n == 0) continue;
                    os.write(buf, 0, n);
                    downloaded += n;
                    lastDataNanos.set(System.nanoTime());
                    if (downloaded - lastReport >= 256 * 1024 || (total > 0 && downloaded == total)) {
                        p.onProgress(downloaded, total);
                        lastReport = downloaded;
                    }
                }
                os.flush();
                lastFailure = null;
                break;
            } catch (Exception e) {
                lastFailure = e;
                if (cancelled.get() || e instanceof UpdateCancelledException) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new UpdateCancelledException("下载已取消", e);
                }
                // Retrying only pays off once bytes are on disk; a blackholed CDN
                // that never sent anything would just burn the stall timeout again.
                boolean resumable = part.isFile() && part.length() > 0L;
                if (attempt < maxAttempts && resumable) {
                    p.onStatus("下载中断，正在从断点续传（重试 " + attempt
                        + "/" + (maxAttempts - 1) + "）…");
                    if (!sleepBeforeRetry(attempt, cancelled)) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        throw new UpdateCancelledException("下载已取消", e);
                    }
                    continue;
                }
                if (stalled.get()) {
                    lastStalled = true;
                }
                // give up: nothing resumable on disk (or attempts exhausted); keep
                // the .part (within its age window) so the next manual attempt can resume
                break;
            } finally {
                done.set(true);
                if (watchdog != null) {
                    watchdog.interrupt();
                    try {
                        watchdog.join(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException ignored) {
                    }
                }
                if (ds != null) {
                    try {
                        ds.stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (lastFailure != null) {
            if (lastStalled) {
                throw new StallTimeoutException("下载停滞超过 " + (stallTimeoutMs / 1000)
                    + " 秒，已中止（断点已保留，可重试续传）；可稍后重试或到发布页手动下载",
                    lastFailure);
            }
            throw lastFailure;
        }

        String actualSha256;
        try {
            p.onStatus("正在验证 SHA-256 …");
            actualSha256 = sha256(part.toPath());
            if (!expectedSha256.equals(actualSha256)) {
                throw new HashMismatchException(expectedSha256, actualSha256);
            }
        } catch (Exception e) {
            // The corrupted bytes must never leak into another line's resume.
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        p.onProgress(downloaded, downloaded);
        p.onStatus("下载完成: " + out.getAbsolutePath());
        log(LogSink.Level.INFO, "[更新] 哈希校验通过 sha256=" + actualSha256
            + " 线路=" + lineName + " 资产=" + asset.name);
        return new VerifiedPackage(out, asset, release);
    }

    /** Back off between resume attempts; false when cancelled during the wait. */
    private static boolean sleepBeforeRetry(int attempt, AtomicBoolean cancelled) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !cancelled.get();
    }

    private String fetchExpectedSha256(Release release, Asset asset, Progress progress,
                                       AtomicBoolean cancelled,
                                       Duration manifestTimeout) throws Exception {
        if (cancelled.get()) throw new UpdateCancelledException();
        Optional<Asset> manifest = release.checksumManifest();
        if (!manifest.isPresent()) {
            throw new IOException("该 Release 未提供 SHA256SUMS.txt，已拒绝下载未校验的更新包");
        }
        progress.onStatus("正在下载 SHA-256 校验清单…");
        ContentFetcher.FetchedText resp = fetcher.get(
            requireHttpsUri(manifest.get().downloadUrl, "SHA-256 校验清单"), manifestTimeout);
        if (resp.statusCode / 100 != 2) {
            throw new HttpStatusException("无法下载 SHA-256 校验清单 HTTP " + resp.statusCode,
                resp.statusCode);
        }
        if (resp.body == null || resp.body.length() > MAX_CHECKSUM_MANIFEST_CHARS) {
            throw new IOException("SHA-256 校验清单无效或过大");
        }
        return expectedSha256(resp.body, asset.name);
    }

    /** Extract the expected hash for {@code assetName}; duplicates are rejected. */
    public static String expectedSha256(String manifest, String assetName) throws IOException {
        if (manifest == null || assetName == null || assetName.isEmpty()) {
            throw new IOException("SHA-256 校验清单缺少目标文件");
        }
        String expected = null;
        String[] lines = manifest.split("\\R", -1);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            Matcher matcher = SHA256SUM_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IOException("SHA-256 校验清单格式无效");
            }
            if (assetName.equals(matcher.group(2))) {
                if (expected != null) {
                    throw new IOException("SHA-256 校验清单包含重复文件名");
                }
                expected = matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        if (expected == null) {
            throw new IOException("SHA-256 校验清单未包含 " + assetName);
        }
        return expected;
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(b & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 Java 运行时不支持 SHA-256", e);
        }
    }

    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "update.bin";
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 180 ? n.substring(0, 180) : n;
    }

    /**
     * Automatic update traffic must never start on plaintext HTTP. Checking the
     * scheme and host here protects both the built-in clients and injected seams.
     */
    static URI requireHttpsUri(String value, String label) throws IOException {
        final URI uri;
        try {
            uri = URI.create(value != null ? value.trim() : "");
        } catch (IllegalArgumentException e) {
            throw new IOException(label + "地址无效", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IOException(label + "必须使用 HTTPS");
        }
        return uri;
    }

    /**
     * Daemon thread that closes the download stream when no bytes arrive for
     * {@code stallTimeoutMs} — or as soon as the user cancels — closing
     * unblocks the reader loop in both cases.
     */
    private static Thread startStallWatchdog(InputStream stream, AtomicLong lastDataNanos,
                                             AtomicBoolean done, AtomicBoolean stalled,
                                             AtomicBoolean cancelled, long stallTimeoutMs) {
        Thread t = new Thread(() -> {
            while (!done.get()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                if (done.get()) return;
                if (cancelled.get()) {
                    closeQuietly(stream);
                    return;
                }
                long stalledMs = (System.nanoTime() - lastDataNanos.get()) / 1_000_000L;
                if (stalledMs > stallTimeoutMs) {
                    stalled.set(true);
                    closeQuietly(stream);
                    return;
                }
            }
        }, "UpdateStallWatchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    // ---------- updates-dir hygiene ----------

    /**
     * Remove updates-dir leftovers that can never be reused: extracted staged-*
     * dirs from an earlier apply and .part files past the cross-run resume
     * window. Downloaded packages are kept — the UI explicitly offers
     * "仅保留文件" for manual installation. Best effort; a leftover never
     * blocks a new download.
     */
    public void pruneStaleUpdateFiles() {
        File[] kids = updatesDir.listFiles();
        if (kids == null) return;
        long now = System.currentTimeMillis();
        for (File kid : kids) {
            String name = kid.getName().toLowerCase(Locale.ROOT);
            try {
                if (kid.isDirectory() && name.startsWith("staged-")) {
                    deleteRecursively(kid.toPath());
                } else if (kid.isFile() && name.endsWith(".part")
                    && now - kid.lastModified() > PART_FILE_MAX_AGE_MS) {
                    //noinspection ResultOfMethodCallIgnored
                    kid.delete();
                }
            } catch (Exception ignored) {
                // keep going; leftovers are harmless
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    // ---------- prepare & install ----------

    /**
     * Stage the verified package and write the apply script.
     * ZIP: extract → copy over install dir → relaunch. MSI/EXE: launch installer.
     * {@code progress} receives the unzip percentage for zip packages.
     */
    public PreparedUpdate prepare(VerifiedPackage pkg, Progress progress) throws Exception {
        if (pkg == null || pkg.file == null || !pkg.file.isFile()) {
            throw new IOException("安装包不存在");
        }
        Progress p = java.util.Objects.requireNonNull(progress, "progress");
        File installDir = resolveInstallDir();
        File staged = new File(updatesDir, "staged-" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        staged.mkdirs();

        long pid = ProcessHandle.current().pid();
        String lower = pkg.file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            unzip(pkg.file, staged, p);
            File payloadRoot = findPayloadRoot(staged);
            return new PreparedUpdate(
                writeZipApplyScript(installDir, payloadRoot, findRelaunchExe(installDir), pid),
                "zip");
        }
        if (lower.endsWith(".msi")) {
            return new PreparedUpdate(writeMsiApplyScript(pkg.file, installDir, pid), "msi");
        }
        if (lower.endsWith(".exe")) {
            return new PreparedUpdate(writeExeApplyScript(pkg.file, installDir, pid), "exe");
        }
        throw new IOException("不支持的安装包类型: " + pkg.file.getName());
    }

    /**
     * Start the apply script. @return true when the installer process was confirmed
     * started; only then may the caller exit.
     */
    public boolean launchInstall(PreparedUpdate prepared) {
        if (prepared == null || prepared.applyScript == null) {
            return false;
        }
        try {
            launcher.launch(prepared.applyScript);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static File resolveInstallDir() {
        try {
            String cmd = ProcessHandle.current().info().command().orElse("");
            if (!cmd.isEmpty()) {
                File exe = new File(cmd).getAbsoluteFile();
                String name = exe.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".exe") && !name.equals("java.exe") && !name.equals("javaw.exe")) {
                    File parent = exe.getParentFile();
                    if (parent != null && parent.isDirectory()) return parent;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            File code = new File(UpdateModule.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getAbsoluteFile();
            if (code.isFile() && code.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                File parent = code.getParentFile();
                if (parent != null) return parent;
            }
            if (code.isDirectory()) return code;
        } catch (Exception ignored) {
        }
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    // ---------- script + zip helpers ----------

    private File writeApplyScript(Consumer<PrintWriter> body) throws IOException {
        File bat = new File(updatesDir, "apply_update.bat");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
            bat.toPath(), StandardCharsets.UTF_8))) {
            w.println("@echo off");
            w.println("chcp 65001 >nul");
            body.accept(w);
            w.println("exit /b 0");
        }
        util.FilePermissions.restrictToOwner(bat);
        return bat;
    }

    /**
     * Relaunch the installed app with the install dir as its working directory.
     * A plain {@code start} inherits this script's CWD (the updates dir), and a
     * writable CWD is exactly what AppPaths would then adopt as the data dir —
     * the app would boot factory-fresh with all saved data looking gone.
     */
    private static void writeRelaunch(PrintWriter w, File exe, File installDir) {
        w.println("start \"\" /D \"" + installDir.getAbsolutePath() + "\" \""
            + exe.getAbsolutePath() + "\"");
    }

    /**
     * Wait for the running app process to exit before touching its files. A fixed
     * sleep raced an orderly shutdown that flushes stores/logs and could take
     * longer than the wait, which made the xcopy fail on the locked exe.
     */
    private static void writeWaitForAppExit(PrintWriter w, long pid) {
        w.println("rem Wait up to 30s for the running app to exit (PID " + pid + ")");
        w.println("for /L %%i in (1,1,30) do (");
        w.println("  tasklist /FI \"PID eq " + pid + "\" 2>nul | find /I \"" + pid
            + "\" >nul 2>nul && timeout /t 1 /nobreak >nul");
        w.println(")");
    }

    private File writeZipApplyScript(File installDir, File payloadRoot, File relaunchExe,
                                     long pid) throws IOException {
        return writeApplyScript(w -> {
            w.println("setlocal");
            w.println("echo Applying PPoEDialer update...");
            writeWaitForAppExit(w, pid);
            w.println("set \"SRC=" + payloadRoot.getAbsolutePath() + "\"");
            w.println("set \"DST=" + installDir.getAbsolutePath() + "\"");
            w.println("if not exist \"%SRC%\\\" (");
            w.println("  echo Source missing");
            w.println("  pause");
            w.println("  exit /b 1");
            w.println(")");
            w.println("if not exist \"%DST%\\\" (");
            w.println("  echo Install dir missing: %DST%");
            w.println("  pause");
            w.println("  exit /b 1");
            w.println(")");
            w.println("rem Writability probe — a Program Files install must use the MSI");
            w.println("copy /y nul \"%DST%\\ppoe_update_probe.tmp\" >nul 2>nul");
            w.println("if errorlevel 1 (");
            w.println("  echo Install dir is not writable. Use the MSI package instead.");
            w.println("  pause");
            w.println("  exit /b 1");
            w.println(")");
            w.println("del \"%DST%\\ppoe_update_probe.tmp\" >nul 2>nul");
            w.println("xcopy \"%SRC%\\*\" \"%DST%\\\" /E /Y /I /Q");
            w.println("if errorlevel 1 (");
            w.println("  echo Copy failed");
            w.println("  pause");
            w.println("  exit /b 1");
            w.println(")");
            writeRelaunch(w, relaunchExe, installDir);
            w.println("endlocal");
        });
    }

    private File writeMsiApplyScript(File msi, File installDir, long pid) throws IOException {
        String exe = new File(installDir, "PPoEDialer.exe").getAbsolutePath();
        return writeApplyScript(w -> {
            w.println("echo Installing MSI update...");
            writeWaitForAppExit(w, pid);
            w.println("msiexec /i \"" + msi.getAbsolutePath() + "\"");
            // A batch command waits for the GUI-subsystem msiexec to exit, so the
            // errorlevel here is the MSI result: 0 = installed, 3010 = installed
            // with a reboot pending; anything else (e.g. 1602 = UAC cancelled)
            // means nothing was installed — relaunch the unchanged old version.
            w.println("if errorlevel 1 if not errorlevel 3010 goto msi_failed");
            w.println("if exist \"" + exe + "\" (");
            writeRelaunch(w, new File(exe), installDir);
            w.println(")");
            w.println("exit /b 0");
            w.println(":msi_failed");
            w.println("echo MSI install failed (exit code %errorlevel%). The previous version is unchanged.");
            writeRelaunch(w, new File(exe), installDir);
            w.println("echo.");
            w.println("pause");
            w.println("exit /b 1");
        });
    }

    private File writeExeApplyScript(File exe, File installDir, long pid) throws IOException {
        return writeApplyScript(w -> {
            w.println("echo Launching installer...");
            writeWaitForAppExit(w, pid);
            writeRelaunch(w, exe, installDir);
        });
    }

    static void unzip(File zip, File destDir, Progress progress) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        try (ZipFile zf = new ZipFile(zip, StandardCharsets.UTF_8)) {
            long total = 0L;
            Enumeration<? extends ZipEntry> all = zf.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (!entryName(e).endsWith("/")) total += Math.max(0L, e.getSize());
            }
            progress.onStatus("正在解压更新包…");
            Enumeration<? extends ZipEntry> en = zf.entries();
            Path dest = destDir.toPath().toAbsolutePath().normalize();
            long done = 0L;
            long lastReport = 0L;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                // Windows CI zippers store '\' separators (a spec violation).
                // ZipEntry.isDirectory() only recognizes a trailing '/', so a
                // directory entry would be written as a zero-byte file and every
                // entry below it would fail — normalize before anything else.
                String name = entryName(e);
                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("非法 zip 路径: " + name);
                }
                if (name.endsWith("/")) {
                    Files.createDirectories(out);
                    continue;
                }
                Path parent = out.getParent();
                if (parent != null) Files.createDirectories(parent);
                try (InputStream in = zf.getInputStream(e)) {
                    try (java.io.OutputStream os = Files.newOutputStream(out,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (n == 0) continue;
                            os.write(buf, 0, n);
                            done += n;
                            if (done - lastReport >= 256 * 1024 && total > 0) {
                                progress.onProgress(done, total);
                                lastReport = done;
                            }
                        }
                    }
                }
            }
            progress.onProgress(total, total);
        }
    }

    private static String entryName(ZipEntry e) {
        return e.getName().replace('\\', '/');
    }

    /** If zip contains a single top-level folder, use it as payload root. */
    static File findPayloadRoot(File staged) {
        File[] kids = staged.listFiles();
        if (kids == null || kids.length == 0) return staged;
        File onlyDir = null;
        int dirs = 0;
        for (File k : kids) {
            if (k.isDirectory()) {
                dirs++;
                onlyDir = k;
            }
        }
        if (dirs == 1 && kids.length == 1) return onlyDir;
        for (File k : kids) {
            if (k.isDirectory() && new File(k, "PPoEDialer.exe").isFile()) return k;
        }
        if (new File(staged, "PPoEDialer.exe").isFile()) return staged;
        return onlyDir != null ? onlyDir : staged;
    }

    static File findRelaunchExe(File installDir) {
        return new File(installDir, "PPoEDialer.exe");
    }
}
