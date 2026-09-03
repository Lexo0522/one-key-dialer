package service;

import model.DialCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.ProcessIO;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Windows RAS module with a fake process runner and a temp phonebook file. */
class WindowsRasModuleTest {
    @TempDir
    Path dir;

    private static final class RecordingRunner implements WindowsRasModule.ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        ProcessIO.Result nextResult = new ProcessIO.Result(0, "Command completed successfully.", false);
        RuntimeException failure;

        @Override
        public ProcessIO.Result run(List<String> command, long timeout, TimeUnit unit,
                                    Charset charset, Consumer<String> lineConsumer) {
            commands.add(new ArrayList<>(command));
            if (failure != null) {
                throw failure;
            }
            return nextResult;
        }
    }

    private WindowsRasModule module(RecordingRunner runner, File pbk) {
        return new WindowsRasModule("pppoe_native_java", runner, pbk);
    }

    @Test
    void dialSuccessGoesThroughNativePathWithoutArgv() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, dir.resolve("pbk").toFile());
        int[] nativeCalls = {0};
        module.setNativeDial((entry, pbk, creds) -> {
            nativeCalls[0]++;
            assertEquals("pppoe_native_java", entry);
            assertEquals("2023001", creds.username());
            return 0;
        });

        DialPort.DialResult result = module.connect(
            new DialCredentials("2023001", "secret".toCharArray()));

        assertTrue(result.isSuccess());
        assertEquals(1, nativeCalls[0]);
        assertEquals(0, runner.commands.size(), "password must never reach a command line");
    }

    @Test
    void dialErrorCodesPassThrough() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, dir.resolve("pbk").toFile());
        module.setNativeDial((entry, pbk, creds) -> 691);
        assertEquals(691, module.connect(new DialCredentials("u", "p".toCharArray())).code);

        module.setNativeDial((entry, pbk, creds) -> 678);
        assertEquals(678, module.connect(new DialCredentials("u", "p".toCharArray())).code);
    }

    @Test
    void missingNativeBindingReportsClearFailure() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, dir.resolve("pbk").toFile());
        module.setNativeDial((entry, pbk, creds) -> null);
        DialPort.DialResult result = module.connect(new DialCredentials("u", "p".toCharArray()));
        assertFalse(result.isSuccess());
        assertEquals(-1, result.code);
        assertEquals(0, runner.commands.size(), "no silent argv fallback anymore");
    }

    @Test
    void timeoutResultMapsToMinusOneDescription() {
        String msg = WindowsRasModule.describeFailure(
            new DialPort.DialResult(-1, "The operation timed out"));
        assertTrue(msg.contains("超时"), msg);
        assertTrue(WindowsRasModule.describeFailure(
            new DialPort.DialResult(691, "")).contains("691"));
        assertTrue(WindowsRasModule.describeFailure(
            new DialPort.DialResult(619, "")).contains("619"));
        assertTrue(WindowsRasModule.describeFailure(
            new DialPort.DialResult(678, "")).contains("678"));
        assertTrue(WindowsRasModule.describeFailure(
            new DialPort.DialResult(999, "")).contains("999"));
    }

    @Test
    void connectFailsWithoutEnsureEntry() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, null); // APPDATA unavailable equivalent
        module.setNativeDial((entry, pbk, creds) -> {
            throw new AssertionError("native dial must not run without a phonebook");
        });
        DialPort.DialResult result = module.connect(new DialCredentials("u", "p".toCharArray()));
        assertFalse(result.isSuccess());
        assertEquals(-1, result.code);
        assertEquals(0, runner.commands.size(), "no rasdial run without a phonebook");
    }

    @Test
    void disconnectRunsAfterSuccessfulConnect() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        File pbk = dir.resolve("pbk").toFile();
        WindowsRasModule module = module(runner, pbk);
        module.setNativeDial((entry, pbk2, creds) -> 0);

        runner.nextResult = new ProcessIO.Result(0, "ok", false);
        module.connect(new DialCredentials("u", "p".toCharArray()));

        module.disconnect();
        List<String> cmd = runner.commands.get(0);
        assertEquals("rasdial", cmd.get(0));
        assertEquals("pppoe_native_java", cmd.get(1));
        assertEquals("/disconnect", cmd.get(2));
    }

    @Test
    void disconnectFailureReportsExitCode() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, dir.resolve("pbk").toFile());
        module.setNativeDial((entry, pbk, creds) -> 0);
        module.connect(new DialCredentials("u", "p".toCharArray()));

        runner.nextResult = new ProcessIO.Result(-1, "error", false);
        assertEquals(-1, module.disconnect());
    }

    @Test
    void ensureEntryCreatesPhonebookSection() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        File pbk = dir.resolve("pbk").toFile();
        WindowsRasModule module = module(runner, pbk);

        assertTrue(module.ensureEntry());
        String content = new String(Files.readAllBytes(pbk.toPath()), StandardCharsets.UTF_8);
        assertTrue(WindowsRasModule.contentContainsSection(content, "pppoe_native_java"));
        assertTrue(content.contains("PreferredDevice=WAN Miniport (PPPOE)"));
        assertTrue(module.hasEntry());
    }

    @Test
    void ensureEntryKeepsExistingOtherEntriesAndBacksUp() throws Exception {
        File pbk = dir.resolve("pbk").toFile();
        Files.write(pbk.toPath(), ("[Existing]\nEncoding=1\nPreferredPort=PPPoE9-1\n"
            + "PreferredDevice=WAN Miniport (PPPOE)\n\n").getBytes(StandardCharsets.UTF_8));
        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, pbk);

        assertTrue(module.ensureEntry());
        String content = new String(Files.readAllBytes(pbk.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("[Existing]"));
        assertTrue(WindowsRasModule.contentContainsSection(content, "pppoe_native_java"));
        assertTrue(new File(pbk.getParentFile(), "rasphone.pbk.bak").isFile(), "backup written");
        // Device hint reused from existing entries on next list call
        assertEquals(1, module.listDeviceOptions().stream()
            .filter(h -> "PPPoE9-1".equals(h.port)).count());
    }

    @Test
    void preferredDeviceIsUsedWhenCreating() throws Exception {
        File pbk = dir.resolve("pbk").toFile();
        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, pbk);
        module.setPreferredDevice(
            new WindowsRasModule.DeviceHint("PPPoE7-2", "WAN Miniport (PPPoE-2)", false));

        assertTrue(module.ensureEntry());
        String content = new String(Files.readAllBytes(pbk.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("PreferredPort=PPPoE7-2"));
        assertTrue(content.contains("PreferredDevice=WAN Miniport (PPPoE-2)"));
    }

    @Test
    void rewriteEntryReplacesSection() throws Exception {
        File pbk = dir.resolve("pbk").toFile();
        WindowsRasModule first = module(new RecordingRunner(), pbk);
        assertTrue(first.ensureEntry());

        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, pbk);
        module.setPreferredDevice(
            new WindowsRasModule.DeviceHint("PPPoE6-0", "WAN Miniport (PPPOE-X)", true));
        assertTrue(module.rewriteEntry());

        String content = new String(Files.readAllBytes(pbk.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("PreferredPort=PPPoE6-0"));
        assertEquals(1, countOccurrences(content, "[pppoe_native_java]"));
    }

    @Test
    void utf16LePhonebookIsPreserved() throws Exception {
        File pbk = dir.resolve("pbk").toFile();
        String existing = "[Existing]\nEncoding=1\nPreferredPort=PPPoE9-9\n"
            + "PreferredDevice=WAN Miniport (PPPOE)\n\n";
        // UTF-16LE with BOM — how Windows writes rasphone.pbk on many systems.
        byte[] bytes = existing.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[bytes.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(bytes, 0, withBom, 2, bytes.length);
        Files.write(pbk.toPath(), withBom);

        RecordingRunner runner = new RecordingRunner();
        WindowsRasModule module = module(runner, pbk);
        assertTrue(module.ensureEntry());

        byte[] after = Files.readAllBytes(pbk.toPath());
        assertEquals((byte) 0xFF, after[0]);
        assertEquals((byte) 0xFE, after[1]);
        String content = new String(after, StandardCharsets.UTF_16LE);
        assertTrue(content.contains("[Existing]"));
        assertTrue(WindowsRasModule.contentContainsSection(content, "pppoe_native_java"));
    }

    @Test
    void invalidConnectionNameIsRejected() {
        assertFalse(WindowsRasModule.isValidConnectionName("bad name"));
        assertFalse(WindowsRasModule.isValidConnectionName(null));
        assertTrue(WindowsRasModule.isValidConnectionName("pppoe_native_java"));
    }

    @Test
    void statusSnapshotReportsEntryPresence() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        File pbk = dir.resolve("pbk").toFile();
        WindowsRasModule module = module(runner, pbk);
        WindowsRasModule.Status before = module.snapshotStatus();
        assertFalse(before.hasEntry);

        assertTrue(module.ensureEntry());
        WindowsRasModule.Status after = module.snapshotStatus();
        assertTrue(after.exists);
        assertTrue(after.hasEntry);
        assertTrue(WindowsRasModule.formatStatus(after).contains("hasEntry=true"));
    }

    @Test
    void collectPppoeDevicesFindsHints() {
        String content = "[A]\nPreferredPort=PPPoE5-0\nPreferredDevice=WAN Miniport (PPPOE)\n"
            + "[B]\nPreferredPort=ETH-0\nPreferredDevice=Ethernet\n";
        List<WindowsRasModule.DeviceHint> hints = WindowsRasModule.collectPppoeDevices(content);
        assertEquals(1, hints.size());
        assertEquals("PPPoE5-0", hints.get(0).port);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
