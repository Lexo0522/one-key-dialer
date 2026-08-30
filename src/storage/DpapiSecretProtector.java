package storage;

import util.DpapiUtil;

import java.util.Arrays;

/**
 * Production {@link SecretProtector}: Windows DPAPI (CurrentUser scope) via
 * {@link DpapiUtil}. There is no key file and no fallback — when DPAPI is not
 * usable the caller skips persisting the secret entirely.
 */
public final class DpapiSecretProtector implements SecretProtector {
    /** Marker prefix of the DPAPI blob format stored in accounts.json. */
    public static final String BLOB_PREFIX = "DPAPI1:";

    @Override
    public String protect(char[] plain) {
        if (plain == null || plain.length == 0) return "";
        byte[] bytes = charsToBytes(plain);
        try {
            byte[] protectedBytes = DpapiUtil.protect(bytes);
            if (protectedBytes == null || protectedBytes.length == 0) {
                return null;
            }
            return BLOB_PREFIX + java.util.Base64.getEncoder().encodeToString(protectedBytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public char[] unprotect(String blob) {
        if (blob == null || blob.isEmpty()) return new char[0];
        if (!blob.startsWith(BLOB_PREFIX)) {
            return null;
        }
        byte[] protectedBytes;
        try {
            protectedBytes = java.util.Base64.getDecoder().decode(blob.substring(BLOB_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] plain = DpapiUtil.unprotect(protectedBytes);
        if (plain == null) {
            return null;
        }
        try {
            return bytesToChars(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static byte[] charsToBytes(char[] chars) {
        // passwords are ASCII-ish on this platform; UTF-8 keeps any char round-trippable
        return new String(chars).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static char[] bytesToChars(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).toCharArray();
    }
}
