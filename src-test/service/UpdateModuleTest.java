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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        /** Throw from open() for the first N opens (0 = never), regardless of host. */
        int failFirstOpens = 0;
        final List<Long> requestedRanges = new ArrayList<>();
        int opens = 0;

        @Override
        public UpdateModule.StreamOpener.DownloadStream open(URI uri, long rangeStart,
                                                             Duration headersTimeout) {
            opens++;
            requestedRanges.add(rangeStart);
            if (failFirstOpens > 0 && opens <= failFirstOpens) {
                throw new RuntimeException("模拟传输中断");
            }
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

    private static final String TEST_API = "https://test.example/api";

    private static UpdateSources.Source line(String id, String api) {
        return new UpdateSources.Source(id, id, api, 12_000, 1, 3, 60_000, 60_000, 2, 600_000);
    }

    private static UpdateSources singleLineSources() {
        return UpdateSources.of(List.of(line("test", TEST_API)), 5000);
    }

    private static UpdateSources twoLineSources() {
        return UpdateSources.of(List.of(
            line("gitee", "https://gitee.test/api/latest"),
            line("github", "https://github.test/api/latest")), 5000);
    }

    private final List<String> logLines = new ArrayList<>();

    private UpdateModule.LogSink recordingSink() {
        return (message, level) -> logLines.add(level + ": " + message);
    }

    private UpdateModule module(UpdateModule.ContentFetcher fetcher, FakeOpener opener,
                                UpdateModule.InstallerLauncher launcher) {
        return module(fetcher, opener, launcher, singleLineSources());
    }

    private UpdateModule module(UpdateModule.ContentFetcher fetcher, FakeOpener opener,
                                UpdateModule.InstallerLauncher launcher, UpdateSources sources) {
        return new UpdateModule(dir.resolve("updates").toFile(), fetcher, opener, launcher,
            sources, recordingSink(), null);
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
        UpdateModule.CheckResult result =
            module.checkOnce(line("test", "http://example.test/api"), "1.0.0");
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

    // ---------- multi-line failover ----------

    /** Metadata answers routed by URL path, so each fake line serves its own payload. */
    private static final class RoutingFetcher implements UpdateModule.ContentFetcher {
        static final class Answer {
            int statusCode = 200;
            String body = "";
            Exception failure;
        }

        private final Map<String, Answer> byHostPath = new HashMap<>();
        final List<URI> requests = new ArrayList<>();

        void route(String host, String path, Answer answer) {
            byHostPath.put(host + path, answer);
        }

        long count(String host) {
            return requests.stream().filter(u -> u.getHost().equals(host)).count();
        }

        @Override
        public UpdateModule.ContentFetcher.FetchedText get(URI uri, Duration timeout)
                throws Exception {
            requests.add(uri);
            Answer answer = byHostPath.get(uri.getHost() + uri.getPath());
            if (answer == null) {
                throw new IllegalStateException("no fake route for " + uri);
            }
            if (answer.failure != null) throw answer.failure;
            return new UpdateModule.ContentFetcher.FetchedText(answer.statusCode, answer.body);
        }
    }

    private static RoutingFetcher.Answer answer(String body) {
        RoutingFetcher.Answer a = new RoutingFetcher.Answer();
        a.body = body;
        return a;
    }

    private static String releaseJson(String host, String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"assets\":["
            + "{\"name\":\"PPoEDialer-1.2.0-windows.zip\","
            + "\"browser_download_url\":\"https://" + host + "/pkg.zip\",\"size\":13},"
            + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"https://" + host + "/sums\"}]}";
    }

    private Path writeTemp(byte[] content) throws IOException {
        Path path = dir.resolve("tmp-" + System.nanoTime());
        Files.write(path, content);
        return path;
    }

    @Test
    void primaryCheckFailureFallsOverToBackupLine() {
        RoutingFetcher fetcher = new RoutingFetcher();
        RoutingFetcher.Answer gitee = new RoutingFetcher.Answer();
        gitee.failure = new java.net.http.HttpConnectTimeoutException("connect timed out");
        fetcher.route("gitee.test", "/api/latest", gitee);
        fetcher.route("github.test", "/api/latest", answer(releaseJson("github.test", "v9.9.9")));

        UpdateModule module = module(fetcher, new FakeOpener(), script -> { }, twoLineSources());
        UpdateModule.CheckResult result = module.check("1.0.0");

        assertTrue(result.updateAvailable);
        assertEquals("github", result.sourceId, "the backup line served the check");
        assertEquals("v9.9.9", result.latestTag);
        assertTrue(logLines.stream().anyMatch(l -> l.contains("连接超时")),
            "the failure reason must reach the log");
    }

    @Test
    void primaryUpToDateDoesNotConsultBackup() {
        RoutingFetcher fetcher = new RoutingFetcher();
        fetcher.route("gitee.test", "/api/latest",
            answer(releaseJson("gitee.test", "v" + AppVersion.NUMERIC)));
        fetcher.route("github.test", "/api/latest", answer(releaseJson("github.test", "v9.9.9")));

        UpdateModule module = module(fetcher, new FakeOpener(), script -> { }, twoLineSources());
        UpdateModule.CheckResult result = module.check(AppVersion.NUMERIC);

        assertFalse(result.updateAvailable);
        assertTrue(result.message.contains("已是最新版本"), result.message);
        assertEquals("gitee", result.sourceId);
        assertEquals(0L, fetcher.count("github.test"),
            "a healthy primary saying 'no update' must end the walk (serial, no backup probe)");
    }

    @Test
    void primaryHashMismatchFallsOverToBackup() throws Exception {
        byte[] pkg = "zip-content-12345".getBytes(StandardCharsets.UTF_8);
        String goodHash = UpdateModule.sha256(writeTemp(pkg));
        RoutingFetcher fetcher = new RoutingFetcher();
        fetcher.route("gitee.test", "/api/latest", answer(releaseJson("gitee.test", "v9.9.9")));
        fetcher.route("gitee.test", "/sums", answer(
            "0000000000000000000000000000000000000000000000000000000000000000"
                + "  PPoEDialer-1.2.0-windows.zip\n"));
        fetcher.route("github.test", "/api/latest", answer(releaseJson("github.test", "v9.9.9")));
        fetcher.route("github.test", "/sums", answer(goodHash + "  PPoEDialer-1.2.0-windows.zip\n"));
        FakeOpener opener = new FakeOpener();
        opener.content = pkg;

        UpdateModule module = module(fetcher, opener, script -> { }, twoLineSources());
        UpdateModule.CheckResult check = module.check("1.0.0");
        assertTrue(check.updateAvailable);
        assertEquals("gitee", check.sourceId);

        UpdateModule.VerifiedPackage verified = module.downloadWithFailover(check,
            check.release.preferredWindowsAsset(true).get(),
            new RecordingProgress(), new AtomicBoolean(false));

        assertEquals("github", verified.release.sourceId,
            "the verified package must come from the backup line");
        try (var files = Files.list(dir.resolve("updates"))) {
            assertTrue(files.allMatch(f -> !f.getFileName().toString().endsWith(".part")),
                "corrupted bytes must not survive into another line's resume");
        }
        assertTrue(logLines.stream().anyMatch(l -> l.contains("哈希校验失败")
                && l.contains("expected=") && l.contains("actual=")),
            "the hash failure must be logged with both hashes");
    }

    @Test
    void lowerVersionBackupIsRefused() throws Exception {
        byte[] pkg = "zip-content-12345".getBytes(StandardCharsets.UTF_8);
        String goodHash = UpdateModule.sha256(writeTemp(pkg));
        RoutingFetcher fetcher = new RoutingFetcher();
        fetcher.route("gitee.test", "/api/latest", answer(releaseJson("gitee.test", "v9.9.9")));
        fetcher.route("gitee.test", "/sums", answer(goodHash + "  PPoEDialer-1.2.0-windows.zip\n"));
        fetcher.route("github.test", "/api/latest", answer(releaseJson("github.test", "v1.0.0")));
        FakeOpener opener = new FakeOpener();
        opener.content = pkg;
        opener.failFirstOpens = 1;

        UpdateModule module = module(fetcher, opener, script -> { }, twoLineSources());
        UpdateModule.CheckResult check = module.check("1.0.0");
        assertTrue(check.updateAvailable);
        assertEquals("gitee", check.sourceId);

        Exception e = assertThrows(Exception.class, () ->
            module.downloadWithFailover(check, check.release.preferredWindowsAsset(true).get(),
                new RecordingProgress(), new AtomicBoolean(false)));

        assertTrue(e.getMessage().contains("低于主线路"), e.getMessage());
        assertTrue(logLines.stream().anyMatch(l -> l.contains("主备版本不一致")),
            "the version mismatch must be logged");
        assertEquals(1L, fetcher.count("github.test"),
            "the backup's metadata was checked, but nothing may be downloaded from it");
        assertEquals(1, opener.opens, "only the primary transfer attempt happened");
    }

    @Test
    void higherVersionBackupProceedsWhenPrimaryFails() throws Exception {
        byte[] pkg = "zip-content-12345".getBytes(StandardCharsets.UTF_8);
        String goodHash = UpdateModule.sha256(writeTemp(pkg));
        RoutingFetcher fetcher = new RoutingFetcher();
        fetcher.route("gitee.test", "/api/latest", answer(releaseJson("gitee.test", "v2.0.0")));
        fetcher.route("gitee.test", "/sums", answer(goodHash + "  PPoEDialer-1.2.0-windows.zip\n"));
        fetcher.route("github.test", "/api/latest", answer(releaseJson("github.test", "v3.0.0")));
        fetcher.route("github.test", "/sums", answer(goodHash + "  PPoEDialer-1.2.0-windows.zip\n"));
        FakeOpener opener = new FakeOpener();
        opener.content = pkg;
        opener.failFirstOpens = 1;

        UpdateModule module = module(fetcher, opener, script -> { }, twoLineSources());
        UpdateModule.CheckResult check = module.check("1.0.0");
        assertEquals("gitee", check.sourceId);
        assertEquals("v2.0.0", check.latestTag);

        UpdateModule.VerifiedPackage verified = module.downloadWithFailover(check,
            check.release.preferredWindowsAsset(true).get(),
            new RecordingProgress(), new AtomicBoolean(false));

        assertEquals("github", verified.release.sourceId);
        assertEquals("v3.0.0", verified.release.tagName);
        assertTrue(logLines.stream().anyMatch(l -> l.contains("主备版本不一致")),
            "the mismatch is logged, but the truth-source version wins");
    }

    @Test
    void cancellationAbortsTheWholeFlowWithoutFailover() throws Exception {
        RoutingFetcher fetcher = new RoutingFetcher();
        fetcher.route("gitee.test", "/api/latest", answer(releaseJson("gitee.test", "v9.9.9")));
        fetcher.route("github.test", "/api/latest", answer(releaseJson("github.test", "v9.9.9")));
        FakeOpener opener = new FakeOpener();
        opener.content = new byte[1024 * 1024];

        UpdateModule module = module(fetcher, opener, script -> { }, twoLineSources());
        UpdateModule.CheckResult check = module.check("1.0.0");

        Exception e = assertThrows(Exception.class, () ->
            module.downloadWithFailover(check, check.release.preferredWindowsAsset(true).get(),
                new RecordingProgress(), new AtomicBoolean(true)));

        assertTrue(e instanceof UpdateModule.UpdateCancelledException, e.getMessage());
        assertEquals(1L, fetcher.count("gitee.test"),
            "cancel hits before the manifest fetch");
        assertEquals(0L, fetcher.count("github.test"),
            "cancellation must not trigger failover");
        assertEquals(0, opener.opens);
    }

    @Test
    void breakerSkipsFailingLineAndRecoversAfterCooldown() {
        RoutingFetcher fetcher = new RoutingFetcher();
        RoutingFetcher.Answer gitee = new RoutingFetcher.Answer();
        gitee.failure = new java.net.http.HttpConnectTimeoutException("connect timed out");
        fetcher.route("gitee.test", "/api/latest", gitee);
        fetcher.route("github.test", "/api/latest", answer(releaseJson("github.test", "v9.9.9")));

        UpdateSources sources = UpdateSources.of(List.of(
            new UpdateSources.Source("gitee", "Gitee", "https://gitee.test/api/latest",
                8_000, 1, 2, 30_000, 30_000, 2, 600_000),
            line("github", "https://github.test/api/latest")), 5000);
        long[] now = {System.currentTimeMillis()};
        UpdateModule module = new UpdateModule(dir.resolve("updates").toFile(), fetcher,
            new FakeOpener(), script -> { }, sources, recordingSink(), () -> now[0]);

        UpdateModule.CheckResult first = module.check("1.0.0");
        assertEquals("github", first.sourceId);
        module.check("1.0.0");
        assertEquals(2L, fetcher.count("gitee.test"), "two checks, one probe each");

        UpdateModule.CheckResult third = module.check("1.0.0");
        assertEquals("github", third.sourceId);
        assertEquals(2L, fetcher.count("gitee.test"),
            "a tripped line is skipped entirely");
        assertTrue(logLines.stream().anyMatch(l -> l.contains("熔断开启")));

        now[0] += 601_000;
        module.check("1.0.0");
        assertEquals(3L, fetcher.count("gitee.test"),
            "cooldown expired: one half-open probe is allowed");
        module.check("1.0.0");
        assertEquals(3L, fetcher.count("gitee.test"),
            "a failed probe re-opens the line for a full cooldown");
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
        assertRelaunchPinsWorkingDir(script);
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
        assertTrue(script.contains("msiexec /i"),
            "the batch must run msiexec directly so its exit code is the MSI result");
        assertTrue(script.contains("if not errorlevel 3010"),
            "3010 (installed, reboot pending) is a success and must not hit msi_failed");
        assertFalse(script.contains("PPoEDialerUpdate"),
            "no console window title: the script must run in a hidden console");
        assertRelaunchPinsWorkingDir(script);
    }

    @Test
    void prepareExeWritesInstallerScript() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9-windows.exe").toFile();
        Files.write(pkg.toPath(), new byte[]{1, 2, 3});
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> { });

        UpdateModule.PreparedUpdate prepared = module.prepare(
            new UpdateModule.VerifiedPackage(pkg, null, null), new RecordingProgress());
        assertEquals("exe", prepared.kind);
        String script = new String(Files.readAllBytes(prepared.applyScript.toPath()),
            StandardCharsets.UTF_8);
        assertRelaunchPinsWorkingDir(script);
    }

    /**
     * Every relaunch must set the working directory to the install dir. A plain
     * {@code start} inherits the updates dir, and AppPaths adopts a writable CWD
     * as the data dir — the app then boots factory-fresh with its data "gone".
     */
    private static void assertRelaunchPinsWorkingDir(String script) {
        assertTrue(script.contains("start \"\" /D"),
            "relaunch must pin the working directory to the install dir");
        assertFalse(script.replaceAll("start \"\" /D \"[^\"]*\"", "").contains("start \"\" \""),
            "no relaunch line may inherit the script's updates-dir CWD");
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

    /**
     * The real launcher must return before the script finishes (the app exits and
     * the batch continues in a hidden console: waits for our exit, applies, relaunches).
     */
    @Test
    void defaultLauncherRunsScriptDetached() throws Exception {
        File script = dir.resolve("probe.bat").toFile();
        File done = dir.resolve("done.flag").toFile();
        Files.write(script.toPath(), ("@echo off\r\n"
            + "ping -n 2 127.0.0.1 > nul\r\n"
            + "type nul > \"" + done.getAbsolutePath() + "\"\r\n").getBytes(StandardCharsets.UTF_8));

        long started = System.nanoTime();
        UpdateModule.defaultInstallerLauncher().launch(script);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 2000, "launcher must not wait for the script to finish");

        for (int i = 0; i < 100 && !done.isFile(); i++) {
            Thread.sleep(100);
        }
        assertTrue(done.isFile(), "the detached script ran to completion");
    }
}
