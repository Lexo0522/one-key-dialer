package ui;

import javax.swing.*;

import java.awt.Font;

/** Install FlatLaf (light or dark) when present, else system L&F, then apply shared fonts. */
public final class LookAndFeelInstaller {
    private LookAndFeelInstaller() {
    }

    public static void install() {
        install(false);
    }

    public static void install(boolean dark) {
        boolean flat = false;
        try {
            Class<?> laf = Class.forName(dark
                ? "com.formdev.flatlaf.FlatDarkLaf" : "com.formdev.flatlaf.FlatLightLaf");
            LookAndFeel instance = (LookAndFeel) laf.getDeclaredConstructor().newInstance();
            UIManager.setLookAndFeel(instance);
            flat = true;
        } catch (Exception ignored) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored2) {
            }
        }
        try {
            // Every component family that renders Chinese must get an explicit
            // CJK-capable font: LAF defaults (Segoe UI on Windows) are physical
            // fonts without CJK glyphs and tofu the text.
            Font font = UiTheme.FONT_CN;
            Font small = UiTheme.FONT_CN_SMALL;
            UIManager.put("Label.font", font);
            UIManager.put("Button.font", font);
            UIManager.put("TextField.font", font);
            UIManager.put("PasswordField.font", font);
            UIManager.put("FormattedTextField.font", font);
            UIManager.put("CheckBox.font", font);
            UIManager.put("RadioButton.font", font);
            UIManager.put("ToggleButton.font", font);
            UIManager.put("Spinner.font", font);
            UIManager.put("ComboBox.font", font);
            UIManager.put("List.font", font);
            UIManager.put("TabbedPane.font", font);
            UIManager.put("TitledBorder.font", font);
            UIManager.put("TextArea.font", font);
            UIManager.put("EditorPane.font", font);
            UIManager.put("TextPane.font", font);
            UIManager.put("Menu.font", font);
            UIManager.put("MenuItem.font", font);
            UIManager.put("MenuBar.font", font);
            UIManager.put("PopupMenu.font", font);
            UIManager.put("Table.font", small);
            UIManager.put("TableHeader.font", font);
            UIManager.put("OptionPane.font", font);
            UIManager.put("OptionPane.messageFont", font);
            UIManager.put("OptionPane.buttonFont", font);
            UIManager.put("ToolTip.font", small);
            UIManager.put("ProgressBar.font", small);
            if (flat) {
                UIManager.put("Component.arrowType", "chevron");
            }
        } catch (Exception ignored) {
        }
    }
}
