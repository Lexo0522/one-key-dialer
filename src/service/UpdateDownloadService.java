package service;

import model.AppVersion;
import util.AppPaths;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Download GitHub release assets and apply them on Windows (zip / msi / exe).
 * <p>
 * ZIP app-image: extract → write apply bat → exit app → bat copies over install dir → relaunch.
 * MSI/EXE: download then launch installer after user confirms; app exits first for MSI.
 */
public final class UpdateDownloadService {
    private static final Pattern SHA256SUM_LINE = Pattern.compile("(?i)^([0-9a-f]{64}) {2}([^\\r\\n]+)$");
    private static final int MAX_CHECKSUM_MANIFEST_CHARS = 1024 * 1024;

    public interface Progress {
        /**
         * @param downloaded bytes so far
         * @param total      content-length or 0 if unknown
         */
        void onProgress(long downloaded, long total);

        void onStatus(String message);
    }

    public static final class DownloadResult {
        public final File file;
        public final UpdateRelease.Asset asset;
        public final UpdateRelease release;

        public DownloadResult(File file, UpdateRelease.Asset asset, UpdateRelease release) {
            this.file = file;
            this.asset = asset;
            this.release = release;
        }
    }

    private UpdateDownloadService() {
    }

    public static File updatesDir() {
        String appData = System.getenv("APPDATA");
        File dir = appData != null
            ? new File(appData, "PPoEDialer" + File.separator + "updates")
            : new File(System.getProperty("user.home"), "PPoEDialer" + File.separator + "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /**
     * Download asset to updates dir. Supports cancel via {@code cancel} flag.
     */
    public static DownloadResult download(UpdateRelease release, UpdateRelease.Asset asset,
                                          Progress progress, AtomicBoolean cancel) throws Exception {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(asset, "asset");
        Progress p = progress != null ? progress : new Progress() {
            @Override public void onProgress(long d, long t) { }
            @Override public void onStatus(String m) { }
        };
        AtomicBoolean cancelled = cancel != null ? cancel : new AtomicBoolean(false);

        String safeName = sanitizeFileName(asset.name);
        File out = new File(updatesDir(), safeName);
        File part = new File(updatesDir(), safeName + ".part");

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
        String expectedSha256 = fetchExpectedSha256(client, release, asset, p, cancelled);

        p.onStatus("正在下载 " + asset.name + " …");
        HttpRequest req = HttpRequest.newBuilder(URI.create(asset.downloadUrl))
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", AppVersion.USER_AGENT)
            .header("Accept", "application/octet-stream")
            .GET()
            .build();

        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("下载失败 HTTP " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(asset.sizeBytes);
        long downloaded = 0L;
        try (InputStream in = new BufferedInputStream(resp.body());
             OutputStream os = Files.newOutputStream(part.toPath(),
                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
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
        p.onProgress(downloaded, total > 0 ? total : downloaded);
        p.onStatus("下载完成: " + out.getAbsolutePath());
        return new DownloadResult(out, asset, release);
    }

    private static String fetchExpectedSha256(HttpClient client, UpdateRelease release,
                                              UpdateRelease.Asset asset, Progress progress,
                                              AtomicBoolean cancelled) throws Exception {
        if (cancelled.get()) throw new IOException("下载已取消");
        Optional<UpdateRelease.Asset> manifest = release.checksumManifest();
        if (!manifest.isPresent()) {
            throw new IOException("该 Release 未提供 SHA256SUMS.txt，已拒绝下载未校验的更新包");
        }
        progress.onStatus("正在下载 SHA-256 校验清单…");
        HttpRequest request = HttpRequest.newBuilder(URI.create(manifest.get().downloadUrl))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", AppVersion.USER_AGENT)
            .header("Accept", "text/plain")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("无法下载 SHA-256 校验清单 HTTP " + response.statusCode());
        }
        String contents = response.body();
        if (contents == null || contents.length() > MAX_CHECKSUM_MANIFEST_CHARS) {
            throw new IOException("SHA-256 校验清单无效或过大");
        }
        return expectedSha256(contents, asset.name);
    }

    static String expectedSha256(String manifest, String assetName) throws IOException {
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

    static String sha256(Path file) throws IOException {
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

    public static void downloadAsync(BackgroundExecutor executor,
                                     UpdateRelease release, UpdateRelease.Asset asset,
                                     Progress progress, AtomicBoolean cancel,
                                     BiConsumer<DownloadResult, Exception> onDone) {
        if (executor == null || onDone == null) return;
        executor.submitLong(() -> {
            try {
                DownloadResult r = download(release, asset, progress, cancel);
                onDone.accept(r, null);
            } catch (Exception e) {
                onDone.accept(null, e);
            }
        });
    }

    /**
     * Apply downloaded package. Returns a short status for logs.
     * For ZIP: stages extract + bat then returns instruction that caller should exit.
     * For MSI/EXE: prepares bat that waits then launches installer.
     *
     * @return path of apply script if app should exit, or null if nothing to exit for
     */
    public static File prepareApplyAndRelaunch(DownloadResult dr) throws Exception {
        if (dr == null || dr.file == null || !dr.file.isFile()) {
            throw new IOException("安装包不存在");
        }
        File installDir = resolveInstallDir();
        File staged = new File(updatesDir(), "staged-" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        staged.mkdirs();

        String lower = dr.file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            unzip(dr.file, staged);
            File payloadRoot = findPayloadRoot(staged);
            File apply = writeZipApplyScript(installDir, payloadRoot, findRelaunchExe(installDir, payloadRoot));
            return apply;
        }
        if (lower.endsWith(".msi")) {
            return writeMsiApplyScript(dr.file, installDir);
        }
        if (lower.endsWith(".exe")) {
            return writeExeApplyScript(dr.file, installDir);
        }
        throw new IOException("不支持的安装包类型: " + dr.file.getName());
    }

    public static void launchApplyScript(File bat) throws IOException {
        if (bat == null || !bat.isFile()) throw new IOException("更新脚本不存在");
        new ProcessBuilder("cmd.exe", "/c", "start", "\"PPoEDialerUpdate\"", bat.getAbsolutePath())
            .directory(bat.getParentFile())
            .start();
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
            File code = new File(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getAbsoluteFile();
            if (code.isFile() && code.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                File parent = code.getParentFile();
                if (parent != null) return parent;
            }
            if (code.isDirectory()) return code;
        } catch (Exception ignored) {
        }
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "update.bin";
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 180 ? n.substring(0, 180) : n;
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
        // Prefer a child that contains PPoEDialer.exe
        for (File k : kids) {
            if (k.isDirectory() && new File(k, "PPoEDialer.exe").isFile()) return k;
        }
        if (new File(staged, "PPoEDialer.exe").isFile()) return staged;
        return onlyDir != null ? onlyDir : staged;
    }

    static File findRelaunchExe(File installDir, File payloadRoot) {
        File a = new File(payloadRoot, "PPoEDialer.exe");
        if (a.isFile()) return new File(installDir, "PPoEDialer.exe");
        File b = new File(installDir, "PPoEDialer.exe");
        if (b.isFile()) return b;
        return new File(installDir, "PPoEDialer.exe");
    }

    private static File writeZipApplyScript(File installDir, File payloadRoot, File relaunchExe)
        throws IOException {
        File bat = new File(updatesDir(), "apply_update.bat");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(bat.toPath(), StandardCharsets.UTF_8))) {
            w.println("@echo off");
            w.println("chcp 65001 >nul");
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
            w.println("exit /b 0");
        }
        return bat;
    }

    private static File writeMsiApplyScript(File msi, File installDir) throws IOException {
        File bat = new File(updatesDir(), "apply_update.bat");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(bat.toPath(), StandardCharsets.UTF_8))) {
            w.println("@echo off");
            w.println("chcp 65001 >nul");
            w.println("echo Installing MSI update...");
            w.println("timeout /t 2 /nobreak >nul");
            w.println("msiexec /i \"" + msi.getAbsolutePath() + "\"");
            w.println("if exist \"" + new File(installDir, "PPoEDialer.exe").getAbsolutePath() + "\" (");
            w.println("  start \"\" \"" + new File(installDir, "PPoEDialer.exe").getAbsolutePath() + "\"");
            w.println(")");
            w.println("exit /b 0");
        }
        return bat;
    }

    private static File writeExeApplyScript(File exe, File installDir) throws IOException {
        File bat = new File(updatesDir(), "apply_update.bat");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(bat.toPath(), StandardCharsets.UTF_8))) {
            w.println("@echo off");
            w.println("chcp 65001 >nul");
            w.println("echo Launching installer...");
            w.println("timeout /t 2 /nobreak >nul");
            w.println("start \"\" \"" + exe.getAbsolutePath() + "\"");
            w.println("exit /b 0");
        }
        return bat;
    }
}
