package i18n;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * UI text facade over a UTF-8 {@link ResourceBundle} (JDK 9+ reads properties
 * as UTF-8 natively). The base bundle is Simplified Chinese; {@code messages_en}
 * provides English. Locales without a bundle fall back to the base language.
 * <p>
 * First extraction batch: main home panel, tab titles, tray menu, theme, and the
 * shared dial-outcome words. Remaining surfaces keep inline text for now.
 */
public final class Messages {
    private static final ResourceBundle BUNDLE = load();

    private Messages() {
    }

    private static ResourceBundle load() {
        try {
            return ResourceBundle.getBundle("i18n.messages", Locale.getDefault(),
                Messages.class.getClassLoader());
        } catch (MissingResourceException e) {
            return null;
        }
    }

    /** @return the translated text, or {@code key} itself when the bundle is missing. */
    public static String get(String key) {
        if (BUNDLE == null) return key;
        try {
            return BUNDLE.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
    }
}
