package util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM password protection with a per-machine key file.
 * On Windows, master.key is preferably DPAPI-wrapped (prefix DP1:); plain 32-byte keys still load
 * and are upgraded on next write. Also migrates legacy XOR+Base64 blobs.
 */
public final class CryptoUtil {
    private static final String AES = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final int KEY_LEN = 32;
    /** Historical hardcoded key used only to migrate old files. */
    private static final String LEGACY_XOR_KEY = "PPoE2026SecretKey";
    private static final String AES_PREFIX = "AES1:";
    /** DPAPI-wrapped master key file marker (ASCII) + base64 payload. */
    private static final byte[] DPAPI_FILE_MAGIC = new byte[]{'D', 'P', '1', ':'};

    private static volatile SecretKey cachedKey;
    private static volatile File keyFile;
    private static volatile boolean keyStoredWithDpapi = false;

    private CryptoUtil() {
    }

    public static synchronized void init(File masterKeyFile) throws Exception {
        keyFile = masterKeyFile;
        cachedKey = loadOrCreateKey(masterKeyFile);
    }

    public static boolean isInitialized() {
        return cachedKey != null;
    }

    public static String encrypt(String text) {
        if (text == null || text.isEmpty()) return "";
        char[] chars = text.toCharArray();
        try {
            return encrypt(chars);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Encrypt UTF-8 bytes of {@code chars}. Does not clear the input array (caller owns it).
     * Intermediate UTF-8 buffer is zeroed.
     */
    public static String encrypt(char[] chars) {
        if (chars == null || chars.length == 0) return "";
        if (cachedKey == null) {
            throw new IllegalStateException("CryptoUtil not initialized");
        }
        java.nio.CharBuffer cb = java.nio.CharBuffer.wrap(chars);
        ByteBuffer utf8 = StandardCharsets.UTF_8.encode(cb);
        byte[] plain = new byte[utf8.remaining()];
        utf8.get(plain);
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, cachedKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plain);
            ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherBytes.length);
            buf.put(iv);
            buf.put(cipherBytes);
            return AES_PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed: " + e.getMessage(), e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * Decrypt AES blob, or migrate legacy XOR if needed.
     * @return plaintext; never returns ciphertext as plaintext on failure
     */
    public static String decrypt(String text) {
        char[] chars = decryptToChars(text);
        try {
            return chars.length == 0 ? "" : new String(chars);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Decrypt to char[]; caller must clear. Fail-closed (throws) on error.
     */
    public static char[] decryptToChars(String text) {
        if (text == null || text.isEmpty()) return new char[0];
        if (text.startsWith(AES_PREFIX)) {
            if (cachedKey == null) {
                throw new IllegalStateException("CryptoUtil not initialized");
            }
            byte[] plain = null;
            try {
                byte[] all = Base64.getDecoder().decode(text.substring(AES_PREFIX.length()));
                if (all.length <= IV_LEN) {
                    throw new IllegalArgumentException("ciphertext too short");
                }
                byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
                byte[] cipherBytes = Arrays.copyOfRange(all, IV_LEN, all.length);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, cachedKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
                plain = cipher.doFinal(cipherBytes);
                java.nio.CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
                char[] out = new char[cb.remaining()];
                cb.get(out);
                return out;
            } catch (Exception e) {
                throw new IllegalStateException("decrypt failed", e);
            } finally {
                if (plain != null) Arrays.fill(plain, (byte) 0);
            }
        }
        // Legacy XOR migration path
        try {
            String legacy = decryptLegacyXor(text, LEGACY_XOR_KEY);
            return legacy != null ? legacy.toCharArray() : new char[0];
        } catch (Exception e) {
            throw new IllegalStateException("legacy decrypt failed", e);
        }
    }

    public static boolean looksEncrypted(String text) {
        return text != null && (text.startsWith(AES_PREFIX) || isLikelyBase64(text));
    }

    public static boolean isAesFormat(String text) {
        return text != null && text.startsWith(AES_PREFIX);
    }

    /** Whether the loaded master.key is DPAPI-wrapped (Windows). */
    public static boolean isKeyDpapiProtected() {
        return keyStoredWithDpapi;
    }

    /** Exposed for tests: legacy XOR used by older builds. */
    public static String encryptLegacyXor(String text, String secretKey) {
        if (text == null || text.isEmpty()) return "";
        try {
            byte[] d = text.getBytes(StandardCharsets.UTF_8);
            byte[] k = secretKey.getBytes(StandardCharsets.UTF_8);
            byte[] r = new byte[d.length];
            for (int i = 0; i < d.length; i++) r[i] = (byte) (d[i] ^ k[i % k.length]);
            return Base64.getEncoder().encodeToString(r);
        } catch (Exception e) {
            throw new IllegalStateException("legacy encrypt failed", e);
        }
    }

    public static String decryptLegacyXor(String text, String secretKey) {
        if (text == null || text.isEmpty()) return "";
        byte[] d = Base64.getDecoder().decode(text);
        byte[] k = secretKey.getBytes(StandardCharsets.UTF_8);
        byte[] r = new byte[d.length];
        for (int i = 0; i < d.length; i++) r[i] = (byte) (d[i] ^ k[i % k.length]);
        return new String(r, StandardCharsets.UTF_8);
    }

    private static boolean isLikelyBase64(String text) {
        if (text.length() < 4) return false;
        return text.matches("^[A-Za-z0-9+/=]+$");
    }

    private static SecretKey loadOrCreateKey(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (file.exists()) {
            byte[] disk = Files.readAllBytes(file.toPath());
            byte[] raw = unwrapKeyBytes(disk);
            if (raw.length != KEY_LEN) {
                throw new IllegalStateException("invalid master.key length");
            }
            // Upgrade plain key file to DPAPI when available
            if (!keyStoredWithDpapi && DpapiUtil.isLikelyWindows()) {
                try {
                    writeKeyFile(file, raw);
                } catch (Exception ignored) {
                }
            }
            return new SecretKeySpec(raw, AES);
        }
        byte[] raw = new byte[KEY_LEN];
        new SecureRandom().nextBytes(raw);
        writeKeyFile(file, raw);
        return new SecretKeySpec(raw, AES);
    }

    private static byte[] unwrapKeyBytes(byte[] disk) throws Exception {
        keyStoredWithDpapi = false;
        if (disk.length > DPAPI_FILE_MAGIC.length && startsWith(disk, DPAPI_FILE_MAGIC)) {
            String b64 = new String(disk, DPAPI_FILE_MAGIC.length, disk.length - DPAPI_FILE_MAGIC.length,
                StandardCharsets.US_ASCII).trim();
            byte[] protectedBytes = Base64.getDecoder().decode(b64);
            byte[] plain = DpapiUtil.unprotect(protectedBytes);
            if (plain == null || plain.length != KEY_LEN) {
                throw new IllegalStateException("DPAPI unprotect master.key failed");
            }
            keyStoredWithDpapi = true;
            return plain;
        }
        if (disk.length == KEY_LEN) {
            return disk;
        }
        throw new IllegalStateException("invalid master.key length");
    }

    private static void writeKeyFile(File file, byte[] rawKey) throws Exception {
        byte[] toWrite = rawKey;
        keyStoredWithDpapi = false;
        if (DpapiUtil.isLikelyWindows()) {
            byte[] protectedBytes = DpapiUtil.protect(rawKey);
            if (protectedBytes != null && protectedBytes.length > 0) {
                String b64 = Base64.getEncoder().encodeToString(protectedBytes);
                byte[] payload = b64.getBytes(StandardCharsets.US_ASCII);
                toWrite = new byte[DPAPI_FILE_MAGIC.length + payload.length];
                System.arraycopy(DPAPI_FILE_MAGIC, 0, toWrite, 0, DPAPI_FILE_MAGIC.length);
                System.arraycopy(payload, 0, toWrite, DPAPI_FILE_MAGIC.length, payload.length);
                keyStoredWithDpapi = true;
            }
        }
        Files.write(file.toPath(), toWrite);
        try {
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            file.setExecutable(false, false);
        } catch (Exception ignored) {
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || prefix == null || data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
