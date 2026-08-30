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
 * keeps the app running with an error report. Release JSON is parsed with Gson.
 */
public final class UpdateModule {
    private static final Pattern SHA256SUM_LINE = Pattern.compile("(?i)^([0-9a-f]{64}) {2}([^\\r\\n]+)$");
    private static final int MAX_CHECKSUM_MANIFEST_CHARS = 1024 * 1024;

    // ---------- data ----------

    public static final class Asset {
        public final String name;
        public final String downloadUrl;
        public final long sizeBytes;
        public final String contentType;

        public Asset(String name, String downloadUrl, long sizeBytes, String contentType) {
            this.name = name != null ? name : "";
            this.downloadUrl = downloadUrl != null ? downloadUrl : "";
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.contentType = contentType != null ? contentType : "";
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
         * Pick best installable asset for Windows packages.
         * Preference: zip (app-image) → msi → exe; prefer names mentioning the product.
         */
        public Optional<Asset> preferredWindowsAsset() {
            Asset bestZip = null;
            Asset bestMsi = null;
            Asset bestExe = null;
            int bestZipScore = Integer.MIN_VALUE;
            int bestMsiScore = Integer.MIN_VALUE;
            int bestExeScore = Integer.MIN_VALUE;
            for (Asset a : assets) {
                if (a == null || a.downloadUrl.isEmpty() || a.name.isEmpty()) continue;
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
            if (bestZip != null) return Optional.of(bestZip);
            if (bestMsi != null) return Optional.of(bestMsi);
            if (bestExe != null) return Optional.of(bestExe);
            return Optional.empty();
        }

        /** The single SHA256SUMS.txt asset, or empty when absent or duplicated. */
        public Optional<Asset> checksumManifest() {
            Asset manifest = null;
            for (Asset asset : assets) {
                if (asset != null && !asset.downloadUrl.isEmpty() && asset.isChecksumManifest()) {
                    if (manifest != null) return Optional.empty();
                    manifest = asset;
                }
            }
            return Optional.ofNullable(manifest);
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

        public boolean hasInstallableAsset() {
            return release != null
                && release.preferredWindowsAsset().isPresent()
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

        DownloadStream open(URI uri) throws Exception;
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
            java.net.http.HttpResponse<String> resp =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return new ContentFetcher.FetchedText(resp.statusCode(), resp.body());
        };
    }

    private static StreamOpener defaultStreamOpener() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NORMAL: never follow an HTTPS -> HTTP redirect downgrade
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        return uri -> {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", AppVersion.USER_AGENT)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
            java.net.http.HttpResponse<InputStream> resp =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            long len = resp.headers().firstValueAsLong("Content-Length").orElse(0L);
            return new StreamOpener.DownloadStream(resp.body(), len, resp.statusCode());
        };
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

    public CheckResult check(String apiUrl, String currentVersion) {
        String current = currentVersion != null ? currentVersion : AppVersion.NUMERIC;
        try {
            ContentFetcher.FetchedText resp = fetcher.get(URI.create(apiUrl), Duration.ofSeconds(12));
            if (resp.statusCode != 200) {
                return new CheckResult(false, current, null, null,
                    "检查更新失败 HTTP " + resp.statusCode, null);
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
                if (release.preferredWindowsAsset().isPresent()
                    && release.checksumManifest().isPresent()) {
                    msg += "\n可下载: " + release.preferredWindowsAsset().get().name;
                } else if (release.preferredWindowsAsset().isPresent()) {
                    msg += "\n（发布包缺少 SHA256SUMS.txt，已禁用自动安装，请到发布页手动确认）";
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
            return new CheckResult(false, current, null, null,
                "检查更新失败: " + e.getClass().getSimpleName(), null);
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
                assets.add(new Asset(a.name, a.browser_download_url, a.size, a.content_type));
            }
        }
        String body = parsed.body;
        return new Release(parsed.tag_name, parsed.html_url,
            body != null ? body.replace("\\r\\n", "\n") : "", assets);
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
        String content_type;
    }

    // ---------- download ----------

    /**
     * Download the asset and verify SHA-256. Only a verified file becomes a
     * {@link VerifiedPackage}; temp files are cleaned up on any failure.
     */
    public VerifiedPackage download(Release release, Asset asset,
                                    Progress progress, AtomicBoolean cancel) throws Exception {
        if (release == null || asset == null) {
            throw new IOException("缺少发布信息或资产");
        }
        Progress p = progress != null ? progress : new Progress() {
            @Override public void onProgress(long d, long t) { }
            @Override public void onStatus(String m) { }
        };
        AtomicBoolean cancelled = cancel != null ? cancel : new AtomicBoolean(false);

        String safeName = sanitizeFileName(asset.name);
        File out = new File(updatesDir, safeName);
        File part = new File(updatesDir, safeName + ".part");

        String expectedSha256 = fetchExpectedSha256(release, asset, p, cancelled);

        p.onStatus("正在下载 " + asset.name + " …");
        URI target = URI.create(asset.downloadUrl);
        long downloaded = 0L;
        try (StreamOpener.DownloadStream ds = opener.open(target)) {
            if (ds.statusCode / 100 != 2) {
                throw new IOException("下载失败 HTTP " + ds.statusCode);
            }
            long total = ds.contentLength > 0 ? ds.contentLength : asset.sizeBytes;
            try (java.io.OutputStream os = Files.newOutputStream(part.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
                 InputStream in = new BufferedInputStream(ds.stream)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long lastReport = 0L;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.get()) {
                        throw new IOException("下载已取消");
                    }
                    if (n == 0) continue;
                    os.write(buf, 0, n);
                    downloaded += n;
                    if (downloaded - lastReport >= 256 * 1024 || downloaded == total) {
                        p.onProgress(downloaded, total);
                        lastReport = downloaded;
                    }
                }
                os.flush();
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
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

    private String fetchExpectedSha256(Release release, Asset asset, Progress progress,
                                       AtomicBoolean cancelled) throws Exception {
        if (cancelled.get()) throw new IOException("下载已取消");
        Optional<Asset> manifest = release.checksumManifest();
        if (!manifest.isPresent()) {
            throw new IOException("该 Release 未提供 SHA256SUMS.txt，已拒绝下载未校验的更新包");
        }
        progress.onStatus("正在下载 SHA-256 校验清单…");
        ContentFetcher.FetchedText resp = fetcher.get(
            URI.create(manifest.get().downloadUrl), Duration.ofSeconds(20));
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

    // ---------- prepare & install ----------

    /**
     * Stage the verified package and write the apply script.
     * ZIP: extract → copy over install dir → relaunch. MSI/EXE: launch installer.
     */
    public PreparedUpdate prepare(VerifiedPackage pkg) throws Exception {
        if (pkg == null || pkg.file == null || !pkg.file.isFile()) {
            throw new IOException("安装包不存在");
        }
        File installDir = resolveInstallDir();
        File staged = new File(updatesDir, "staged-" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        staged.mkdirs();

        String lower = pkg.file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            unzip(pkg.file, staged);
            File payloadRoot = findPayloadRoot(staged);
            return new PreparedUpdate(
                writeZipApplyScript(installDir, payloadRoot, findRelaunchExe(installDir, payloadRoot)),
                "zip");
        }
        if (lower.endsWith(".msi")) {
            return new PreparedUpdate(writeMsiApplyScript(pkg.file, installDir), "msi");
        }
        if (lower.endsWith(".exe")) {
            return new PreparedUpdate(writeExeApplyScript(pkg.file, installDir), "exe");
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

    private File writeZipApplyScript(File installDir, File payloadRoot, File relaunchExe)
        throws IOException {
        return writeApplyScript(w -> {
            w.println("setlocal");
            w.println("echo Applying PPoEDialer update...");
            w.println("rem Wait for main process to exit");
            w.println("timeout /t 2 /nobreak >nul");
            w.println("set \"SRC=" + payloadRoot.getAbsolutePath() + "\"");
            w.println("set \"DST=" + installDir.getAbsolutePath() + "\"");
            w.println("if not exist \"%SRC%\\\" (");
            w.println("  echo Source missing");
            w.println("  pause");
            w.println("  exit /b 1");
            w.println(")");
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

    private File writeMsiApplyScript(File msi, File installDir) throws IOException {
        return writeApplyScript(w -> {
            w.println("echo Installing MSI update...");
            w.println("timeout /t 2 /nobreak >nul");
            // start /wait: msiexec is a GUI-subsystem process — without /wait the
            // script would check for the exe (and relaunch) before install finishes
            w.println("start \"PPoEDialerUpdate\" /wait msiexec /i \"" + msi.getAbsolutePath() + "\"");
            w.println("if exist \"" + new File(installDir, "PPoEDialer.exe").getAbsolutePath() + "\" (");
            w.println("  start \"\" \"" + new File(installDir, "PPoEDialer.exe").getAbsolutePath() + "\"");
            w.println(")");
        });
    }

    private File writeExeApplyScript(File exe, File installDir) throws IOException {
        return writeApplyScript(w -> {
            w.println("echo Launching installer...");
            w.println("timeout /t 2 /nobreak >nul");
            w.println("start \"\" \"" + exe.getAbsolutePath() + "\"");
        });
    }

    static void unzip(File zip, File destDir) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        try (ZipFile zf = new ZipFile(zip, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            Path dest = destDir.toPath().toAbsolutePath().normalize();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("非法 zip 路径: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
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

    static File findRelaunchExe(File installDir, File payloadRoot) {
        File b = new File(installDir, "PPoEDialer.exe");
        if (b.isFile()) return b;
        return new File(installDir, "PPoEDialer.exe");
    }
}
