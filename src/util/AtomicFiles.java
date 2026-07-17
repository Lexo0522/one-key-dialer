package util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Write-then-replace helpers to avoid truncated config files. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeUtf8(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = parent != null
            ? Files.createTempFile(parent, target.getFileName().toString(), ".tmp")
            : Files.createTempFile(target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw e;
        }
    }

    public static void writeString(Path target, String content, Charset charset) throws IOException {
        write(target, content.getBytes(charset));
    }
}
