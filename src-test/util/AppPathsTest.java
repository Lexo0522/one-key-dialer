package util;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Data-dir discriminator: dev runs (exploded classes) vs packaged runs (jar code source). */
class AppPathsTest {
    @Test
    void explodedClassesAreADevRun() {
        assertTrue(AppPaths.isDevRun(AppPathsTest.class), "tests run from target/test-classes");
    }

    /**
     * A packaged run (jar code source) must never be treated as a dev run: the
     * user.dir fallback is dev-only, because the updater relaunches installed
     * apps with the updates dir as CWD, which would otherwise be adopted as the
     * data dir and boot the app factory-fresh.
     */
    @Test
    void jarCodeSourceIsNotADevRun() {
        assertFalse(AppPaths.isDevRun(Gson.class), "gson ships inside a jar");
    }
}
