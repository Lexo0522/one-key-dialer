package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import model.AppVersion;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Online update module: Gson parsing, hash-gated packages, install orchestration. */
class UpdateModuleTest {
    @TempDir
    Path dir;

    private static final class FakeFetcher implements UpdateModule.ContentFetcher {
        String body = "";
        int statusCode = 200;
        RuntimeException failure;

        @Override
        public UpdateModule.ContentFetcher.FetchedText get(URI uri, Duration timeout) {
            if (failure != null) throw failure;
            return new UpdateModule.ContentFetcher.FetchedText(statusCode, body);
        }
    }

    private static final class FakeOpener implements UpdateModule.StreamOpener {
        byte[] content = "package-bytes".getBytes(StandardCharsets.UTF_8);
        int statusCode = 200;
        RuntimeException failure;
        /** Emulate a range-capable CDN: 206 + sliced body for rangeStart > 0. */
        boolean rangeCapable = false;
        /** First open serves this many bytes then throws, simulating a broken transfer. */
        int failFirstAttemptAfterBytes = 0;
        final List<Long> requestedRanges = new ArrayList<>();
        int opens = 0;

        @Override
        public UpdateModule.StreamOpener.DownloadStream open(URI uri, long rangeStart) {
            opens++;
            requestedRanges.add(rangeStart);
            if (failure != null) throw failure;
            if (opens == 1 && failFirstAttemptAfterBytes > 0) {
                return new UpdateModule.StreamOpener.DownloadStream(
                    failingAfter(content, failFirstAttemptAfterBytes), content.length, 200);
            }
            if (rangeCapable && rangeStart > 0) {
                byte[] rest = Arrays.copyOfRange(content, (int) rangeStart, content.length);
                return new UpdateModule.StreamOpener.DownloadStream(
                    new ByteArrayInputStream(rest), content.length, 206);
            }
            return new UpdateModule.StreamOpener.DownloadStream(
                new ByteArrayInputStream(content), content.length, statusCode);
        }

        private static InputStream failingAfter(byte[] data, int bytes) {
            return new InputStream() {
                int sent = 0;

                @Override public int read() throws IOException {
                    if (sent >= bytes) throw new IOException("模拟传输中断");
                    return data[sent++] & 0xff;
                }
            };
        }
    }

    private UpdateModule module(FakeFetcher fetcher, FakeOpener opener,
                                UpdateModule.InstallerLauncher launcher) {
        return new UpdateModule(dir.resolve("updates").toFile(), fetcher, opener, launcher);
    }

    /** Records progress events; download/prepare require a non-null progress sink. */
    private static final class RecordingProgress implements UpdateModule.Progress {
        final List<String> statuses = new ArrayList<>();
        long lastDone = -1;
        long lastTotal = -1;
        int progressEvents;

        @Override public void onProgress(long downloaded, long total) {
            progressEvents++;
            lastDone = downloaded;
            lastTotal = total;
        }

        @Override public void onStatus(String message) {
            statuses.add(message);
        }
    }

    // ---------- check / parse ----------

    @Test
    void parseReleaseJsonWithGson() {
        String json = "{\"tag_name\":\"v1.2.0\",\"html_url\":\"https://example.test/r\",\"body\":\"notes\\r\\nline2\","
            + "\"assets\":[{\"name\":\"PPoEDialer-1.2.0-windows.zip\",\"browser_download_url\":\"https://example.test/a.zip\","
            + "\"size\":2048,\"content_type\":\"application/zip\"},"
            + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"https://example.test/s\",\"size\":10}]}";
        UpdateModule.Release release = UpdateModule.parseReleaseJson(json);

        assertEquals("v1.2.0", release.tagName);
        assertEquals("https://example.test/r", release.htmlUrl);
        assertTrue(release.body.contains("\n"));
        assertEquals(2, release.assets.size());
        assertTrue(release.preferredWindowsAsset(true).isPresent());
        assertTrue(release.checksumManifest().isPresent());
    }

    @Test
    void duplicateChecksumManifestsAreRejected() {
        String json = "{\"tag_name\":\"v1.2.0\",\"assets\":["
            + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"https://a/s\"},"
            + "{\"name\":\"sha256sums.txt\",\"browser_download_url\":\"https://b/s\"}]}";
        UpdateModule.Release release = UpdateModule.parseReleaseJson(json);
        assertFalse(release.checksumManifest().isPresent());
    }

    @Test
    void assetSelectionHonorsInstallDirWritability() {
        String json = "{\"tag_name\":\"v1.2.0\",\"assets\":["
            + "{\"name\":\"PPoEDialer-1.2.0-windows.zip\",\"browser_download_url\":\"https://example.test/a.zip\",\"size\":2048},"
            + "{\"name\":\"PPoEDialer-1.2.0-windows.msi\",\"browser_download_url\":\"https://example.test/a.msi\",\"size\":4096}]}";
        UpdateModule.Release release = UpdateModule.parseReleaseJson(json);

        // portable (writable) install: zip wins; Program Files install: msi wins
        assertTrue(release.preferredWindowsAsset(true).get().isZip());
        assertTrue(release.preferredWindowsAsset(false).get().isMsi());
    }

    @Test
    void nonWritableInstallWithZipOnlyOffersNoAutoInstall() {
        String json = "{\"tag_name\":\"v1.2.0\",\"assets\":["
            + "{\"name\":\"PPoEDialer-1.2.0-windows.zip\",\"browser_download_url\":\"https://example.test/a.zip\",\"size\":2048}]}";
        UpdateModule.Release release = UpdateModule.parseReleaseJson(json);

        // zip cannot apply into a non-writable dir — updater must fall back to the release page
        assertFalse(release.preferredWindowsAsset(false).isPresent());
        assertTrue(release.preferredWindowsAsset(true).isPresent());
    }

    @Test
    void automaticUpdatesRejectPlainHttpUrls() {
        String json = "{\"tag_name\":\"v1.2.0\",\"assets\":["
            + "{\"name\":\"PPoEDialer-1.2.0-windows.zip\",\"browser_download_url\":\"http://example.test/a.zip\"},"
            + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"http://example.test/sums\"}]}";
        UpdateModule.Release release = UpdateModule.parseReleaseJson(json);

        assertFalse(release.preferredWindowsAsset(true).isPresent());
        assertFalse(release.checksumManifest().isPresent());

        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "{\"tag_name\":\"v9.9.9\"}";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });
        UpdateModule.CheckResult result = module.check("http://example.test/api", "1.0.0");
        assertFalse(result.updateAvailable);
        assertTrue(result.message.contains("HTTPS"), result.message);
    }

    @Test
    void noUpdateWhenTagIsNotNewer() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "{\"tag_name\":\"v" + AppVersion.NUMERIC + "\"}";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        UpdateModule.CheckResult result = module.check(AppVersion.NUMERIC);
        assertFalse(result.updateAvailable);
        assertTrue(result.message.contains("已是最新版本"));
    }

    @Test
    void updateAvailableWithInstallableAsset() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "{\"tag_name\":\"v9.9.9\",\"assets\":["
            + "{\"name\":\"PPoEDialer-9.9.9-windows.zip\",\"browser_download_url\":\"https://a/a.zip\",\"size\":10},"
            + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"https://a/sums\"}]}";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        UpdateModule.CheckResult result = module.check("1.0.0");
        assertTrue(result.updateAvailable);
        assertTrue(result.hasInstallableAsset(true));
    }

    @Test
    void invalidResponseIsReportedNotCrashed() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "not json at all";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        UpdateModule.CheckResult result = module.check("1.0.0");
        assertFalse(result.updateAvailable);
        assertFalse(result.hasInstallableAsset(true));
    }

    @Test
    void httpErrorIsReported() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.statusCode = 503;
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        UpdateModule.CheckResult result = module.check("1.0.0");
        assertFalse(result.updateAvailable);
        assertTrue(result.message.contains("503"));
    }

    @Test
    void networkFailureIsReported() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.failure = new RuntimeException("offline");
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        UpdateModule.CheckResult result = module.check("1.0.0");
        assertFalse(result.updateAvailable);
        assertTrue(result.message.contains("检查更新失败"));
    }

    // ---------- download ----------

    private UpdateModule.Release releaseWithManifest() {
        return UpdateModule.parseReleaseJson("{\"tag_name\":\"v9.9.9\",\"assets\":["
            + "{\"name\":\"PPoEDialer-9.9.9-windows.zip\",\"browser_download_url\":\"https://a/a.zip\",\"size\":13},"
            + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"https://a/sums\"}]}");
    }

    @Test
    void verifiedDownloadProducesVerifiedPackage() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        FakeOpener opener = new FakeOpener();
        byte[] pkg = "zip-content-12345".getBytes(StandardCharsets.UTF_8);
        opener.content = pkg;
        String hash = UpdateModule.sha256(Files.write(
            dir.resolve("tmp-hash"), pkg));
        Files.deleteIfExists(dir.resolve("tmp-hash"));
        fetcher.body = hash + "  PPoEDialer-9.9.9-windows.zip\n";

        UpdateModule module = module(fetcher, opener, script -> { });
        UpdateModule.VerifiedPackage verified =
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), new AtomicBoolean(false));

        assertTrue(verified.file.isFile());
        assertFalse(new File(verified.file.getParentFile(), verified.file.getName() + ".part").exists(),
            "temp file must be renamed away");
        assertEquals("PPoEDialer-9.9.9-windows.zip", verified.asset.name);
    }

    @Test
    void missingManifestRefusesDownload() {
        FakeFetcher fetcher = new FakeFetcher();
        UpdateModule.Release release = UpdateModule.parseReleaseJson(
            "{\"tag_name\":\"v9.9.9\",\"assets\":[{\"name\":\"a.zip\",\"browser_download_url\":\"https://a/a.zip\"}]}");
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        Exception e = assertThrows(Exception.class, () ->
            module.download(release, release.assets.get(0), new RecordingProgress(), new AtomicBoolean(false)));
        assertTrue(e.getMessage().contains("SHA256SUMS"), e.getMessage());
    }

    @Test
    void duplicateManifestEntriesRefuseDownload() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  a.zip\n"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  a.zip\n";
        UpdateModule.Release release = UpdateModule.parseReleaseJson(
            "{\"tag_name\":\"v9.9.9\",\"assets\":[{\"name\":\"a.zip\",\"browser_download_url\":\"https://a/a.zip\"},"
                + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"https://a/sums\"}]}");
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        Exception e = assertThrows(Exception.class, () ->
            module.download(release, release.assets.get(0), new RecordingProgress(), new AtomicBoolean(false)));
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    @Test
    void hashMismatchDeletesTempFileAndThrows() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "0000000000000000000000000000000000000000000000000000000000000000  PPoEDialer-9.9.9-windows.zip\n";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        assertThrows(Exception.class, () ->
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), new AtomicBoolean(false)));

        try (var files = Files.list(dir.resolve("updates"))) {
            assertEquals(0, files.count(), "no .part or package file may survive a hash failure");
        }
    }

    @Test
    void cancellationCleansUpTempFile() throws Exception {
        FakeOpener opener = new FakeOpener();
        opener.content = new byte[1024 * 1024]; // enough to notice cancellation mid-read
        FakeFetcher fetcher = new FakeFetcher();
        byte[] one = new byte[1024 * 1024];
        fetcher.body = UpdateModule.sha256(Files.write(dir.resolve("t2"), one))
            + "  PPoEDialer-9.9.9-windows.zip\n";
        Files.deleteIfExists(dir.resolve("t2"));
        AtomicBoolean cancel = new AtomicBoolean(true);

        UpdateModule module = module(fetcher, opener, script -> { });
        assertThrows(Exception.class, () ->
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), cancel));

        try (var files = Files.list(dir.resolve("updates"))) {
            assertEquals(0, files.count(), "cancelled download must leave nothing behind");
        }
    }

    @Test
    void badManifestFormatIsRejected() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "nothash here";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });
        assertThrows(Exception.class, () ->
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), new AtomicBoolean(false)));
    }

    @Test
    void interruptedDownloadResumesFromPart() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        FakeOpener opener = new FakeOpener();
        byte[] pkg = new byte[300 * 1024];
        new Random(42).nextBytes(pkg);
        opener.content = pkg;
        opener.rangeCapable = true;
        opener.failFirstAttemptAfterBytes = 100 * 1024;
        fetcher.body = UpdateModule.sha256(Files.write(dir.resolve("t3"), pkg))
            + "  PPoEDialer-9.9.9-windows.zip\n";
        Files.deleteIfExists(dir.resolve("t3"));

        UpdateModule module = module(fetcher, opener, script -> { });
        UpdateModule.VerifiedPackage verified =
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), new AtomicBoolean(false));

        assertEquals(2, opener.opens, "one resume retry after the broken transfer");
        assertEquals(0L, opener.requestedRanges.get(0));
        assertTrue(opener.requestedRanges.get(1) > 0, "second attempt must carry a Range start");
        assertEquals(pkg.length, Files.size(verified.file.toPath()),
            "resumed download must reconstruct the full payload");
    }

    @Test
    void failureWithoutProgressIsNotRetried() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        FakeOpener opener = new FakeOpener();
        fetcher.body = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + "  PPoEDialer-9.9.9-windows.zip\n";
        opener.failure = new RuntimeException("offline");
        UpdateModule module = module(fetcher, opener, script -> { });

        assertThrows(Exception.class, () ->
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), new AtomicBoolean(false)));
        assertEquals(1, opener.opens, "no bytes on disk ⇒ a retry would just stall again");
    }

    @Test
    void completePartShortcutsToVerificationOnRangeNotSatisfiable() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        FakeOpener opener = new FakeOpener();
        byte[] pkg = "1234567890123".getBytes(StandardCharsets.UTF_8); // 13 bytes = asset.sizeBytes
        fetcher.body = UpdateModule.sha256(Files.write(dir.resolve("t4"), pkg))
            + "  PPoEDialer-9.9.9-windows.zip\n";
        Files.deleteIfExists(dir.resolve("t4"));
        Path updates = dir.resolve("updates");
        Files.createDirectories(updates);
        Files.write(updates.resolve("PPoEDialer-9.9.9-windows.zip.part"), pkg);
        opener.statusCode = 416;

        UpdateModule module = module(fetcher, opener, script -> { });
        UpdateModule.VerifiedPackage verified =
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), new AtomicBoolean(false));

        assertEquals(1, opener.opens, "a complete .part needs no second request");
        assertTrue(verified.file.isFile());
    }

    @Test
    void rangeNotSatisfiableWithWrongPartSizeResetsDownload() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        FakeOpener opener = new FakeOpener();
        fetcher.body = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + "  PPoEDialer-9.9.9-windows.zip\n";
        Path updates = dir.resolve("updates");
        Files.createDirectories(updates);
        Path stalePart = updates.resolve("PPoEDialer-9.9.9-windows.zip.part");
        Files.write(stalePart, new byte[999]);
        opener.statusCode = 416;

        UpdateModule module = module(fetcher, opener, script -> { });
        Exception e = assertThrows(Exception.class, () ->
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                new RecordingProgress(), new AtomicBoolean(false)));

        assertTrue(e.getMessage().contains("断点"), e.getMessage());
        assertFalse(Files.exists(stalePart), "an incompatible .part must be reset");
        assertEquals(3, opener.opens, "resets, retries from zero, then gives up");
    }

    @Test
    void pruneRemovesStagedDirsAndAgedPartFiles() throws Exception {
        Path updates = dir.resolve("updates");
        Files.createDirectories(updates);
        Path staged = updates.resolve("staged-123");
        Files.createDirectories(staged);
        Files.write(staged.resolve("old.jar"), new byte[]{1});
        Path freshPart = updates.resolve("pkg.zip.part");
        Files.write(freshPart, new byte[]{1, 2, 3});
        Path agedPart = updates.resolve("old.zip.part");
        Files.write(agedPart, new byte[]{1});
        Files.setLastModifiedTime(agedPart, FileTime.fromMillis(
            System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000));
        Path keptPkg = updates.resolve("PPoEDialer-9.9.9-windows.zip");
        Files.write(keptPkg, new byte[]{4});

        module(new FakeFetcher(), new FakeOpener(), script -> { }).pruneStaleUpdateFiles();

        assertFalse(Files.exists(staged), "staged extraction dirs are never reused");
        assertFalse(Files.exists(agedPart), ".part files past the resume window are garbage");
        assertTrue(Files.exists(freshPart), "recent .part files feed cross-run resume");
        assertTrue(Files.exists(keptPkg), "downloaded packages are offered as 仅保留文件 and kept");
    }

    // ---------- prepare & install ----------

    private File makeZip(String entryName) throws IOException {
        File zip = dir.resolve("pkg.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            // No ".exe" entries here: freshly written exe files are briefly locked by
            // Windows AV scans, which breaks JUnit temp-dir cleanup.
            zos.putNextEntry(new ZipEntry("PPoEDialer/app.jar"));
            zos.write("fake jar".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        // keep the requested name for type detection
        File named = new File(zip.getParentFile(), entryName);
        if (!zip.renameTo(named)) {
            throw new IOException("rename failed");
        }
        return named;
    }

    @Test
    void prepareZipWritesApplyScript() throws Exception {
        File zip = makeZip("PPoEDialer-9.9.9-windows.zip");
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> { });
        UpdateModule.VerifiedPackage pkg = new UpdateModule.VerifiedPackage(
            zip, releaseWithManifest().assets.get(0), releaseWithManifest());

        UpdateModule.PreparedUpdate prepared = module.prepare(pkg, new RecordingProgress());
        assertTrue(prepared.applyScript.isFile());
        assertEquals("zip", prepared.kind);
        String script = new String(Files.readAllBytes(prepared.applyScript.toPath()),
            StandardCharsets.UTF_8);
        assertTrue(script.contains("xcopy"));
        assertTrue(script.contains("PPoEDialer.exe"));
        assertTrue(script.contains("tasklist"),
            "apply script must wait for the running process instead of a fixed sleep");
    }

    @Test
    void unzipReportsDeterminateProgress() throws Exception {
        File zip = makeZip("PPoEDialer-9.9.9-windows.zip");
        RecordingProgress progress = new RecordingProgress();
        File dest = dir.resolve("unzipped").toFile();

        UpdateModule.unzip(zip, dest, progress);

        assertTrue(new File(dest, "PPoEDialer/app.jar").isFile(), "payload extracted");
        assertTrue(progress.progressEvents > 0, "progress events were reported");
        assertEquals(progress.lastTotal, progress.lastDone, "final event reports completion");
        assertTrue(progress.lastTotal > 0, "total is the uncompressed size");
        assertFalse(progress.statuses.isEmpty(), "stage statuses were reported");
    }

    /**
     * Windows CI zippers store '\' separators, so ZipEntry.isDirectory() is false
     * even for directory entries and every entry below them used to fail after a
     * zero-byte "file" clobbered the directory. Must unzip cleanly anyway.
     */
    @Test
    void unzipHandlesBackslashSeparatorEntries() throws Exception {
        File zip = dir.resolve("backslash.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("PPoEDialer\\runtime\\legal\\"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("PPoEDialer\\runtime\\legal\\java.base\\LICENSE"));
            zos.write("legal text".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("PPoEDialer\\app\\app.jar"));
            zos.write("jar".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        File dest = dir.resolve("backslash-out").toFile();

        UpdateModule.unzip(zip, dest, new RecordingProgress());

        assertTrue(new File(dest, "PPoEDialer/runtime/legal/java.base/LICENSE").isFile(),
            "nested backslash paths must land as a directory tree");
        assertTrue(new File(dest, "PPoEDialer/app/app.jar").isFile());
        assertFalse(new File(dest, "PPoEDialer/runtime/legal").isFile(),
            "the directory entry must not be written as a file");
    }

    @Test
    void prepareMsiWritesInstallerScript() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9-windows.msi").toFile();
        Files.write(pkg.toPath(), new byte[]{1, 2, 3});
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> { });

        UpdateModule.PreparedUpdate prepared = module.prepare(
            new UpdateModule.VerifiedPackage(pkg, null, null), new RecordingProgress());
        assertEquals("msi", prepared.kind);
        String script = new String(Files.readAllBytes(prepared.applyScript.toPath()),
            StandardCharsets.UTF_8);
        assertTrue(script.contains("msiexec"));
        assertTrue(script.contains("msi_failed"),
            "MSI script must report a failed install instead of silently relaunching");
    }

    @Test
    void unsupportedPackageTypeIsRejected() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9.tar.gz").toFile();
        Files.write(pkg.toPath(), new byte[]{1});
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> { });
        assertThrows(IOException.class, () ->
            module.prepare(new UpdateModule.VerifiedPackage(pkg, null, null), new RecordingProgress()));
    }

    @Test
    void installLaunchFailureKeepsAppRunning() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9-windows.msi").toFile();
        Files.write(pkg.toPath(), new byte[]{1, 2, 3});
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> {
            throw new IOException("start failed");
        });

        UpdateModule.PreparedUpdate prepared = module.prepare(
            new UpdateModule.VerifiedPackage(pkg, null, null), new RecordingProgress());
        assertFalse(module.launchInstall(prepared),
            "failed launch must be reported so the app does not exit");
    }

    @Test
    void installLaunchSuccessConfirmedBeforeExit() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9-windows.msi").toFile();
        Files.write(pkg.toPath(), new byte[]{1, 2, 3});
        List<File> launched = new java.util.concurrent.CopyOnWriteArrayList<>();
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), launched::add);

        UpdateModule.PreparedUpdate prepared = module.prepare(
            new UpdateModule.VerifiedPackage(pkg, null, null), new RecordingProgress());
        assertTrue(module.launchInstall(prepared));
        assertEquals(1, launched.size());
    }
}
