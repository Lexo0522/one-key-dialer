package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import model.AppVersion;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

        @Override
        public UpdateModule.StreamOpener.DownloadStream open(URI uri) {
            if (failure != null) throw failure;
            return new UpdateModule.StreamOpener.DownloadStream(
                new ByteArrayInputStream(content), content.length, statusCode);
        }
    }

    private UpdateModule module(FakeFetcher fetcher, FakeOpener opener,
                                UpdateModule.InstallerLauncher launcher) {
        return new UpdateModule(dir.resolve("updates").toFile(), fetcher, opener, launcher);
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
        assertTrue(release.preferredWindowsAsset().isPresent());
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
        assertTrue(result.hasInstallableAsset());
    }

    @Test
    void invalidResponseIsReportedNotCrashed() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "not json at all";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        UpdateModule.CheckResult result = module.check("1.0.0");
        assertFalse(result.updateAvailable);
        assertFalse(result.hasInstallableAsset());
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
                null, new AtomicBoolean(false));

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
            module.download(release, release.assets.get(0), null, new AtomicBoolean(false)));
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
            module.download(release, release.assets.get(0), null, new AtomicBoolean(false)));
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    @Test
    void hashMismatchDeletesTempFileAndThrows() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.body = "0000000000000000000000000000000000000000000000000000000000000000  PPoEDialer-9.9.9-windows.zip\n";
        UpdateModule module = module(fetcher, new FakeOpener(), script -> { });

        assertThrows(Exception.class, () ->
            module.download(releaseWithManifest(), releaseWithManifest().assets.get(0),
                null, new AtomicBoolean(false)));

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
                null, cancel));

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
                null, new AtomicBoolean(false)));
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

        UpdateModule.PreparedUpdate prepared = module.prepare(pkg);
        assertTrue(prepared.applyScript.isFile());
        assertEquals("zip", prepared.kind);
        String script = new String(Files.readAllBytes(prepared.applyScript.toPath()),
            StandardCharsets.UTF_8);
        assertTrue(script.contains("xcopy"));
        assertTrue(script.contains("PPoEDialer.exe"));
    }

    @Test
    void prepareMsiWritesInstallerScript() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9-windows.msi").toFile();
        Files.write(pkg.toPath(), new byte[]{1, 2, 3});
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> { });

        UpdateModule.PreparedUpdate prepared = module.prepare(
            new UpdateModule.VerifiedPackage(pkg, null, null));
        assertEquals("msi", prepared.kind);
        String script = new String(Files.readAllBytes(prepared.applyScript.toPath()),
            StandardCharsets.UTF_8);
        assertTrue(script.contains("msiexec"));
    }

    @Test
    void unsupportedPackageTypeIsRejected() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9.tar.gz").toFile();
        Files.write(pkg.toPath(), new byte[]{1});
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> { });
        assertThrows(IOException.class, () ->
            module.prepare(new UpdateModule.VerifiedPackage(pkg, null, null)));
    }

    @Test
    void installLaunchFailureKeepsAppRunning() throws Exception {
        File pkg = dir.resolve("PPoEDialer-9.9.9-windows.msi").toFile();
        Files.write(pkg.toPath(), new byte[]{1, 2, 3});
        UpdateModule module = module(new FakeFetcher(), new FakeOpener(), script -> {
            throw new IOException("start failed");
        });

        UpdateModule.PreparedUpdate prepared = module.prepare(
            new UpdateModule.VerifiedPackage(pkg, null, null));
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
            new UpdateModule.VerifiedPackage(pkg, null, null));
        assertTrue(module.launchInstall(prepared));
        assertEquals(1, launched.size());
    }
}
