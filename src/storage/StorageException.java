package storage;

import java.io.IOException;

/**
 * Storage failure with a precise kind so callers can report without guessing.
 * Unknown or missing {@code schemaVersion}, malformed JSON, and I/O errors are
 * never silently repaired or reinterpreted as another format.
 */
public class StorageException extends IOException {
    public enum Kind {
        /** File exists but is not valid JSON. */
        INVALID_JSON,
        /** Valid JSON but schemaVersion is missing, not an integer, or unsupported. */
        UNKNOWN_SCHEMA
    }

    private final Kind kind;

    public StorageException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public StorageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
