package util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {
    @Test
    void aesRoundTripAndLegacy() throws Exception {
        var key = Files.createTempFile("master", ".key").toFile();
        key.deleteOnExit();
        Files.deleteIfExists(key.toPath());
        CryptoUtil.init(key);
        String plain = "campus-pass-测试";
        String enc = CryptoUtil.encrypt(plain);
        assertTrue(CryptoUtil.isAesFormat(enc));
        assertEquals(plain, CryptoUtil.decrypt(enc));

        String legacy = CryptoUtil.encryptLegacyXor(plain, "PPoE2026SecretKey");
        assertEquals(plain, CryptoUtil.decrypt(legacy));
    }
}
