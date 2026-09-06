package ui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;

/** Loads the single application logo shared by the window and system tray. */
public final class AppIcon {
    private static final String RESOURCE = "/icons/logo.png";
    private static final Image IMAGE = load();

    private AppIcon() {
    }

    public static Image image() {
        return IMAGE;
    }

    private static Image load() {
        try (InputStream in = AppIcon.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Application logo resource is missing: " + RESOURCE);
            }
            Image image = ImageIO.read(in);
            if (image == null || image.getWidth(null) <= 0 || image.getHeight(null) <= 0) {
                throw new IllegalStateException("Application logo resource is invalid: " + RESOURCE);
            }
            return image;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load application logo: " + RESOURCE, e);
        }
    }
}
