package service;

import model.AppVersion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Update-line configuration: shipped defaults, external override, clamping. */
class UpdateSourcesTest {

    @Test
    void builtinResourceIsOnClasspathAndParses() throws Exception {
        Properties props = new Properties();
        try (InputStream in = UpdateSources.class.getResourceAsStream(UpdateSources.RESOURCE)) {
            assertNotNull(in, "update.properties must ship inside the jar");
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        UpdateSources sources = UpdateSources.fromProperties(props, null);

        assertEquals(2, sources.enabled().size());
        assertEquals("gitee", sources.enabled().get(0).id, "primary line first");
        assertEquals("github", sources.enabled().get(1).id);
        assertEquals("Gitee", sources.enabled().get(0).displayName);
        // Primary line: independent short timeout plus exactly one retry.
        assertEquals(8_000, sources.enabled().get(0).checkTimeoutMs);
        assertEquals(2, sources.enabled().get(0).checkAttempts);
        assertEquals(30_000, sources.enabled().get(0).stallTimeoutMs);
        assertEquals(AppVersion.RELEASES_API, sources.enabled().get(1).apiUrl);
    }

    @Test
    void codeFallbackKeepsGiteeFirstWithRetry() {
        UpdateSources sources = UpdateSources.defaults();

        assertEquals(2, sources.enabled().size());
        assertEquals("gitee", sources.enabled().get(0).id);
        assertEquals("github", sources.enabled().get(1).id);
        assertEquals(AppVersion.RELEASES_API, sources.enabled().get(1).apiUrl);
        assertEquals(2, sources.enabled().get(0).checkAttempts);
        assertEquals(5000, sources.connectTimeoutMs);
    }

    @Test
    void externalOverrideWinsAndDisabledLinesDropOut() {
        List<String> warnings = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("update.sources", "gitee,github,ghost,mirror");
        props.setProperty("source.gitee.enabled", "false");
        props.setProperty("source.ghost.displayName", "Ghost");
        // ghost intentionally has no api -> skipped with a warning
        props.setProperty("source.mirror.displayName", "Mirror");
        props.setProperty("source.mirror.api", "https://mirror.test/api");
        props.setProperty("source.mirror.checkTimeoutMs", "12345");

        UpdateSources sources = UpdateSources.fromProperties(props, warnings::add);

        assertEquals(2, sources.enabled().size(), "disabled + unconfigured lines drop out");
        assertEquals("github", sources.enabled().get(0).id);
        assertEquals("mirror", sources.enabled().get(1).id);
        assertEquals("Mirror", sources.enabled().get(1).displayName);
        assertEquals("https://mirror.test/api", sources.enabled().get(1).apiUrl);
        assertEquals(12_345, sources.enabled().get(1).checkTimeoutMs);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ghost")),
            "a line without an api must be reported");
    }

    @Test
    void valuesAreClampedToSaneRanges() {
        Properties props = new Properties();
        props.setProperty("update.sources", "gitee");
        props.setProperty("source.gitee.checkTimeoutMs", "0");
        props.setProperty("source.gitee.checkAttempts", "99");
        props.setProperty("source.gitee.stallTimeoutMs", "not-a-number");
        props.setProperty("source.gitee.breakerCooldownMs", "1");

        UpdateSources sources = UpdateSources.fromProperties(props, null);
        UpdateSources.Source gitee = sources.enabled().get(0);

        assertEquals(1000, gitee.checkTimeoutMs);
        assertEquals(5, gitee.checkAttempts);
        assertEquals(30_000, gitee.stallTimeoutMs,
            "unparsable falls back to the per-line default (gitee: 30s)");
        assertEquals(10_000, gitee.breakerCooldownMs);
    }

    @Test
    void duplicateIdsKeepFirstOccurrence() {
        Properties props = new Properties();
        props.setProperty("update.sources", "github,gitee,github");
        props.setProperty("source.github.api", "https://one.test/api");
        props.setProperty("source.github.checkTimeoutMs", "7777");

        UpdateSources sources = UpdateSources.fromProperties(props, null);

        assertEquals(2, sources.enabled().size());
        assertEquals("https://one.test/api", sources.enabled().get(0).apiUrl);
        assertEquals(7777, sources.enabled().get(0).checkTimeoutMs,
            "the first occurrence wins and is not re-parsed");
        assertEquals("gitee", sources.enabled().get(1).id);
    }

    @Test
    void emptyChainFallsBackToDefaults() {
        Properties props = new Properties();
        props.setProperty("update.sources", "   ");
        props.setProperty("source.gitee.enabled", "false");
        props.setProperty("source.github.enabled", "false");

        UpdateSources sources = UpdateSources.fromProperties(props, null);

        assertEquals(2, sources.enabled().size(), "an empty chain must not brick updates");
    }
}
