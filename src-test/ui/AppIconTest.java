package ui;

import org.junit.jupiter.api.Test;

import java.awt.Image;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIconTest {
    @Test
    void loadsTheSingleClasspathLogo() {
        Image image = AppIcon.image();
        assertNotNull(image);
        assertTrue(image.getWidth(null) > 0);
        assertTrue(image.getHeight(null) > 0);
        assertTrue(AppIcon.class.getResource("/icons/logo.png") != null);
    }
}
