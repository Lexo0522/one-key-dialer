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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The single online-update module: version check, asset selection, download,
 * SHA-256 verification, install preparation, and installer launch.
 * Only a hash-verified download yields a {@link VerifiedPackage}; the installer
 * process must be confirmed started before the app exits, and a launch failure
 * keeps the app running with an error report. Downloads resume from an
 * interrupted {@code .part} file (within one call and across app runs) and the
 * updates dir is pruned of never-reusable leftovers. Release JSON is parsed
 * with Gson.
 */
public final class UpdateModule {
    private static final Pattern SHA256SUM_LINE = Pattern.compile("(?i)^([0-9a-f]{64}) {2}([^\\r\\n]+)$");
    private static final int MAX_CHECKSUM_MANIFEST_CHARS = 1024 * 1024;
    /** No bytes for this long during a download ⇒ abort with a clear message. */
    private static final long DOWNLOAD_STALL_TIMEOUT_MS = 60_000;
    /** Transfer attempts per download() call: the first try plus resume retries. */
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    /** .part files older than this are garbage; younger ones feed cross-run resume. */
    private static final long PART_FILE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000L;

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
        public final String tagName;
        public final String htmlUrl;
        public final String body;
        public final List<Asset> assets;

        public Release(String tagName, String htmlUrl, String body, List<Asset> assets) {
            this.tagName = tagName != null ? tagName : "";
            this.htmlUrl = htmlUrl != null ? htmlUrl : AppVersion.GITHUB_URL + "/releases";
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
        public final String currentVersion;
        public final String latestTag;
        public final String releaseUrl;
        public final String message;
        public final Release release;

        public CheckResult(boolean updateAvailable, String currentVersion, String latestTag,
                           String releaseUrl, String message, Release release) {
            this.updateAvailable = updateAvailable;
            this.currentVersion = currentVersion;
            this.latestTag = latestTag;
            this.releaseUrl = releaseUrl;
            this.message = message;
            this.release = release;
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
         * means the caller restarts from zero.
         */
        DownloadStream open(URI uri, long rangeStart) throws Exception;
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

    public UpdateModule(File updatesDir, ContentFetcher fetcher, StreamOpener opener,
                        InstallerLauncher launcher) {
        this.updatesDir = updatesDir != null ? updatesDir : defaultUpdatesDir();
        this.fetcher = fetcher != null ? fetcher : defaultContentFetcher();
        this.opener = opener != null ? opener : defaultStreamOpener();
        this.launcher = launcher != null ? launcher : defaultInstallerLauncher();
        //noinspection ResultOfMethodCallIgnored
        this.updatesDir.mkdirs();
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

    private static ContentFetcher defaultContentFetcher() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
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

    private static StreamOpener defaultStreamOpener() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NORMAL: never follow an HTTPS -> HTTP redirect downgrade
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        return (uri, rangeStart) -> {
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", AppVersion.USER_AGENT)
                .header("Accept", "application/octet-stream")
                .GET();
            if (rangeStart > 0) {
                builder.header("Range", "bytes=" + rangeStart + "-");
            }
            java.net.http.HttpResponse<InputStream> resp = sendBounded(client, builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream(),
                Duration.ofSeconds(60));
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
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.util.concurrent.CancellationException) {
                throw new IOException("请求无响应（网络或 CDN 可能被拦截），已超时中止", cause);
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("请求无响应（网络或 CDN 可能被拦截），已超时中止", cause);
        } catch (java.util.concurrent.CancellationException e) {
            throw new IOException("请求无响应（网络或 CDN 可能被拦截），已超时中止", e);
        } finally {
            ceiling.cancel(false);
        }
    }

    private static InstallerLauncher defaultInstallerLauncher() {
        return script -> {
            if (script == null || !script.isFile()) {
                throw new IOException("更新脚本不存在");
            }
            new ProcessBuilder("cmd.exe", "/c", "start", "\"PPoEDialerUpdate\"", script.getAbsolutePath())
                .directory(script.getParentFile())
                .start();
        };
    }

    // ---------- check ----------

    public CheckResult check(String currentVersion) {
        return check(AppVersion.RELEASES_API, currentVersion);
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

    public CheckResult check(String apiUrl, String currentVersion) {
        String current = currentVersion != null ? currentVersion : AppVersion.NUMERIC;
        try {
            ContentFetcher.FetchedText resp = fetcher.get(
                requireHttpsUri(apiUrl, "更新接口"), Duration.ofSeconds(12));
            if (resp.statusCode != 200) {
                String hint = resp.statusCode == 403
                    ? "（GitHub API 限流，稍后再试或到发布页查看）" : "";
                return new CheckResult(false, current, null, null,
                    "检查更新失败 HTTP " + resp.statusCode + hint, null);
            }
            Release release = parseReleaseJson(resp.body);
            String tag = release.tagName;
            if (tag == null || tag.isEmpty()) {
                return new CheckResult(false, current, null, release.htmlUrl,
                    "未解析到最新版本号", release);
            }
            int cmp = AppVersion.compareNumeric(current, tag);
            if (cmp < 0) {
                String msg = "发现新版本 " + tag + "（当前 " + AppVersion.DISPLAY + "）";
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
                return new CheckResult(true, current, tag, release.htmlUrl, msg, release);
            }
            return new CheckResult(false, current, tag, release.htmlUrl,
                "已是最新版本（" + AppVersion.DISPLAY + "）", release);
        } catch (JsonParseException e) {
            return new CheckResult(false, current, null, null,
                "更新响应解析失败: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(false, current, null, null, "检查更新已中断", null);
        } catch (Exception e) {
            String detail = e.getMessage() != null && !e.getMessage().isEmpty()
                ? e.getMessage() : e.getClass().getSimpleName();
            return new CheckResult(false, current, null, null,
                "检查更新失败: " + detail, null);
        }
    }

    /** Gson parse of a GitHub latest-release payload. */
    public static Release parseReleaseJson(String json) {
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
        return new Release(parsed.tag_name, parsed.html_url,
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
     * Download the asset and verify SHA-256, resuming from an interrupted
     * {@code .part} (same call or a previous run) when the server honors Range.
     * Only a verified file becomes a {@link VerifiedPackage}; the temp file is
     * removed on verification failure or cancellation, and kept otherwise so a
     * later attempt can resume.
     */
    public VerifiedPackage download(Release release, Asset asset,
                                    Progress progress, AtomicBoolean cancel) throws Exception {
        if (release == null || asset == null) {
            throw new IOException("缺少发布信息或资产");
        }
        Progress p = java.util.Objects.requireNonNull(progress, "progress");
        AtomicBoolean cancelled = cancel != null ? cancel : new AtomicBoolean(false);

        pruneStaleUpdateFiles();

        String safeName = sanitizeFileName(asset.name);
        File out = new File(updatesDir, safeName);
        File part = new File(updatesDir, safeName + ".part");

        URI target = requireHttpsUri(asset.downloadUrl, "更新包");
        String expectedSha256 = fetchExpectedSha256(release, asset, p, cancelled);

        p.onStatus("正在下载 " + asset.name + " …");
        long downloaded = 0L;
        Exception lastFailure = null;
        boolean lastStalled = false;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            long onDisk = part.isFile() ? part.length() : 0L;
            // Campus networks routinely blackhole the GitHub asset CDN mid-transfer;
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
                ds = opener.open(target, onDisk);
                if (ds.statusCode == 416 && asset.sizeBytes > 0 && onDisk == asset.sizeBytes) {
                    // .part already reaches the end of the remote file: go verify it.
                    downloaded = onDisk;
                    break;
                }
                if (ds.statusCode == 416) {
                    // Stale or incompatible .part: drop it and restart from zero.
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        continue;
                    }
                    throw new IOException("本地断点与服务器不匹配，已重置下载");
                }
                if (ds.statusCode / 100 != 2) {
                    throw new IOException("下载失败 HTTP " + ds.statusCode);
                }
                boolean resumed = onDisk > 0 && ds.statusCode == 206;
                downloaded = resumed ? onDisk : 0L;
                os = resumed
                    ? Files.newOutputStream(part.toPath(),
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                    : Files.newOutputStream(part.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                watchdog = startStallWatchdog(ds.stream, lastDataNanos, done, stalled, cancelled);
                long total = asset.sizeBytes > 0 ? asset.sizeBytes
                    : (resumed ? ds.contentLength + downloaded : ds.contentLength);
                InputStream in = new BufferedInputStream(ds.stream);
                byte[] buf = new byte[64 * 1024];
                int n;
                long lastReport = downloaded;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.get()) {
                        throw new IOException("下载已取消");
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
                if (cancelled.get()) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new IOException("下载已取消", e);
                }
                // Retrying only pays off once bytes are on disk; a blackholed CDN
                // that never sent anything would just burn the stall timeout again.
                boolean resumable = part.isFile() && part.length() > 0L;
                if (attempt < MAX_DOWNLOAD_ATTEMPTS && resumable) {
                    p.onStatus("下载中断，正在从断点续传（重试 " + attempt
                        + "/" + (MAX_DOWNLOAD_ATTEMPTS - 1) + "）…");
                    if (!sleepBeforeRetry(attempt, cancelled)) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        throw new IOException("下载已取消", e);
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
                throw new IOException("下载停滞超过 " + (DOWNLOAD_STALL_TIMEOUT_MS / 1000)
                    + " 秒，已中止（断点已保留，可重试续传）；校园网可能拦截了 GitHub 资源，请到发布页手动下载",
                    lastFailure);
            }
            throw lastFailure;
        }

        try {
            p.onStatus("正在验证 SHA-256 …");
            String actualSha256 = sha256(part.toPath());
            if (!expectedSha256.equals(actualSha256)) {
                throw new IOException("更新包 SHA-256 校验失败");
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        p.onProgress(downloaded, downloaded);
        p.onStatus("下载完成: " + out.getAbsolutePath());
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
                                       AtomicBoolean cancelled) throws Exception {
        if (cancelled.get()) throw new IOException("下载已取消");
        Optional<Asset> manifest = release.checksumManifest();
        if (!manifest.isPresent()) {
            throw new IOException("该 Release 未提供 SHA256SUMS.txt，已拒绝下载未校验的更新包");
        }
        progress.onStatus("正在下载 SHA-256 校验清单…");
        ContentFetcher.FetchedText resp = fetcher.get(
            requireHttpsUri(manifest.get().downloadUrl, "SHA-256 校验清单"),
            Duration.ofSeconds(20));
        if (resp.statusCode / 100 != 2) {
            throw new IOException("无法下载 SHA-256 校验清单 HTTP " + resp.statusCode);
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
     * {@link #DOWNLOAD_STALL_TIMEOUT_MS} — or as soon as the user cancels —
     * closing unblocks the reader loop in both cases.
     */
    private static Thread startStallWatchdog(InputStream stream, AtomicLong lastDataNanos,
                                             AtomicBoolean done, AtomicBoolean stalled,
                                             AtomicBoolean cancelled) {
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
                if (stalledMs > DOWNLOAD_STALL_TIMEOUT_MS) {
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
            w.println("start \"\" \"" + relaunchExe.getAbsolutePath() + "\"");
            w.println("endlocal");
        });
    }

    private File writeMsiApplyScript(File msi, File installDir, long pid) throws IOException {
        String exe = new File(installDir, "PPoEDialer.exe").getAbsolutePath();
        return writeApplyScript(w -> {
            w.println("echo Installing MSI update...");
            writeWaitForAppExit(w, pid);
            // start /wait: msiexec is a GUI-subsystem process — without /wait the
            // script would check for the exe (and relaunch) before install finishes
            w.println("start \"PPoEDialerUpdate\" /wait msiexec /i \"" + msi.getAbsolutePath() + "\"");
            // 1602 = UAC cancelled; any nonzero exit means nothing was installed.
            // Surface it instead of silently relaunching the unchanged old version.
            w.println("if errorlevel 1 goto msi_failed");
            w.println("if exist \"" + exe + "\" (");
            w.println("  start \"\" \"" + exe + "\"");
            w.println(")");
            w.println("exit /b 0");
            w.println(":msi_failed");
            w.println("echo MSI install failed (exit code %errorlevel%). The previous version is unchanged.");
            w.println("start \"\" \"" + exe + "\"");
            w.println("echo.");
            w.println("pause");
            w.println("exit /b 1");
        });
    }

    private File writeExeApplyScript(File exe, File installDir, long pid) throws IOException {
        return writeApplyScript(w -> {
            w.println("echo Launching installer...");
            writeWaitForAppExit(w, pid);
            w.println("start \"\" \"" + exe.getAbsolutePath() + "\"");
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
