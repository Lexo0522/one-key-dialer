package ui;

import javax.swing.*;

/** Install FlatLaf (light or dark) when present, else system L&F, then apply shared fonts. */
public final class LookAndFeelInstaller {
    private LookAndFeelInstaller() {
    }

    public static void install() {
        install(false);
    }

    public static void install(boolean dark) {
        System.setProperty("awt.font", UiTheme.FONT_NAME_CN);
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
            UIManager.put("Label.font", UiTheme.FONT_CN);
            UIManager.put("Button.font", UiTheme.FONT_CN);
            UIManager.put("TextField.font", UiTheme.FONT_CN);
            UIManager.put("CheckBox.font", UiTheme.FONT_CN);
            UIManager.put("Spinner.font", UiTheme.FONT_CN);
            UIManager.put("ComboBox.font", UiTheme.FONT_CN);
            UIManager.put("TabbedPane.font", UiTheme.FONT_CN);
            UIManager.put("TitledBorder.font", UiTheme.FONT_CN);
            UIManager.put("Table.font", UiTheme.FONT_CN_SMALL);
            UIManager.put("TableHeader.font", UiTheme.FONT_CN);
            UIManager.put("ScrollPane.font", UiTheme.FONT_CN);
            if (flat) {
                UIManager.put("Component.arrowType", "chevron");
            }
        } catch (Exception ignored) {
        }
    }
}
