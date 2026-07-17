package ui;

import model.PasswordChars;

import javax.swing.JPasswordField;

/**
 * Swing password field helpers. {@link JPasswordField} still stores text in a Document
 * (String-based); this only shortens intermediate copies when prefilling from {@code char[]}.
 */
public final class PasswordFields {
    private PasswordFields() {
    }

    /** Prefill from chars; clears the temporary String reference promptly. */
    public static void setPassword(JPasswordField field, char[] chars) {
        if (field == null) return;
        if (chars == null || chars.length == 0) {
            field.setText("");
            return;
        }
        String s = new String(chars);
        try {
            field.setText(s);
        } finally {
            // Local ref only; Document retains its own copy until cleared.
            s = null;
        }
    }

    /** Read field password and clear after consumer (consumer should not retain the array). */
    public static void withPassword(JPasswordField field, java.util.function.Consumer<char[]> consumer) {
        if (field == null || consumer == null) return;
        char[] pw = field.getPassword();
        try {
            consumer.accept(pw);
        } finally {
            PasswordChars.clear(pw);
        }
    }
}
