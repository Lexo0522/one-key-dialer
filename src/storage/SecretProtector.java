package storage;

/**
 * Protection for account secrets at rest. The only sanctioned path on this
 * platform is Windows DPAPI; when protection is unavailable the caller must
 * not persist the secret.
 */
public interface SecretProtector {
    /**
     * Protect plaintext chars into a rest-format blob.
     * @return blob, or null when protection is unavailable or failed (secret must not be persisted)
     */
    String protect(char[] plain);

    /**
     * Restore plaintext from a blob produced by {@link #protect}.
     * @return fresh plaintext array (caller must clear), or null on any failure
     */
    char[] unprotect(String blob);
}
