package service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UpdateReleaseTest {

    @Test
    void parseTagAndAssets() {
        String json = "{"
            + "\"tag_name\":\"v1.2.0\","
            + "\"html_url\":\"https://github.com/Lexo0522/one-key-dialer/releases/tag/v1.2.0\","
            + "\"body\":\"notes\\nline2\","
            + "\"assets\":[{"
            + "\"name\":\"PPoEDialer-1.2.0-win.zip\","
            + "\"size\":12345678,"
            + "\"browser_download_url\":\"https://example.com/a.zip\","
            + "\"content_type\":\"application/zip\""
            + "},{"
            + "\"name\":\"SHA256SUMS.txt\","
            + "\"size\":150,"
            + "\"browser_download_url\":\"https://example.com/SHA256SUMS.txt\""
            + "},{"
            + "\"name\":\"notes.txt\","
            + "\"size\":10,"
            + "\"browser_download_url\":\"https://example.com/notes.txt\""
            + "}]"
            + "}";
        UpdateRelease r = UpdateRelease.parseGithubLatestJson(json);
        assertEquals("v1.2.0", r.tagName);
        assertTrue(r.htmlUrl.contains("releases"));
        assertTrue(r.body.contains("notes"));
        assertEquals(3, r.assets.size());
        assertTrue(r.checksumManifest().isPresent());
        assertEquals("SHA256SUMS.txt", r.checksumManifest().get().name);
        Optional<UpdateRelease.Asset> pref = r.preferredWindowsAsset();
        assertTrue(pref.isPresent());
        assertTrue(pref.get().isZip());
        assertEquals("PPoEDialer-1.2.0-win.zip", pref.get().name);
    }

    @Test
    void preferZipOverMsi() {
        List<UpdateRelease.Asset> assets = Arrays.asList(
            new UpdateRelease.Asset("PPoEDialer.msi", "https://x/m.msi", 1000, ""),
            new UpdateRelease.Asset("PPoEDialer-portable.zip", "https://x/p.zip", 2000, "")
        );
        UpdateRelease r = new UpdateRelease("v2", "https://x", "", assets);
        assertTrue(r.preferredWindowsAsset().get().isZip());
    }

    @Test
    void sanitizeAndCompareStillWorkViaAppVersion() {
        assertTrue(model.AppVersion.compareNumeric("1.1.0", "v1.2.0") < 0);
        assertEquals("a_b.zip", UpdateDownloadService.sanitizeFileName("a/b.zip"));
    }

    @Test
    void requiresChecksumManifestBeforeAutoInstall() {
        UpdateRelease.Asset zip = new UpdateRelease.Asset("PPoEDialer.zip", "https://x/p.zip", 1, "");
        UpdateRelease withoutManifest = new UpdateRelease("v2", "https://x", "", Collections.singletonList(zip));
        UpdateCheckService.Result unverified = new UpdateCheckService.Result(
            true, "1", "v2", "https://x", "", withoutManifest);
        assertFalse(unverified.hasInstallableAsset());

        UpdateRelease manifestRelease = new UpdateRelease("v2", "https://x", "", Arrays.asList(
            zip, new UpdateRelease.Asset("SHA256SUMS.txt", "https://x/sums", 1, "text/plain")
        ));
        UpdateCheckService.Result verified = new UpdateCheckService.Result(
            true, "1", "v2", "https://x", "", manifestRelease);
        assertTrue(verified.hasInstallableAsset());
    }

    @Test
    void emptyAssetsNoPreference() {
        UpdateRelease r = new UpdateRelease("v1", "u", "", Collections.emptyList());
        assertFalse(r.preferredWindowsAsset().isPresent());
    }
}
