package ui;

import java.awt.Color;
import java.awt.Font;

/** Shared Swing look constants for extracted UI components. */
public final class UiTheme {
    public static final String FONT_NAME_CN = "Microsoft YaHei";
    public static final String FONT_NAME_EN = "Consolas";

    public static final Font FONT_CN = new Font(FONT_NAME_CN, Font.PLAIN, 13);
    public static final Font FONT_CN_BOLD = new Font(FONT_NAME_CN, Font.BOLD, 13);
    public static final Font FONT_CN_SMALL = new Font(FONT_NAME_CN, Font.PLAIN, 11);
    public static final Font FONT_LOG = new Font(FONT_NAME_EN, Font.PLAIN, 12);
    public static final Font FONT_DIAG = new Font(FONT_NAME_CN, Font.PLAIN, 12);

    public static final Color COLOR_SUCCESS = new Color(34, 197, 94);
    public static final Color COLOR_ERROR = new Color(220, 53, 69);
    public static final Color COLOR_INFO = new Color(0, 123, 255);
    public static final Color COLOR_WARNING = new Color(255, 193, 7);
    public static final Color COLOR_BG = new Color(248, 249, 250);
    public static final Color COLOR_CARD = Color.WHITE;
    public static final Color COLOR_DARK = new Color(40, 44, 52);
    public static final Color COLOR_BORDER = new Color(209, 213, 219);
    public static final Color COLOR_BORDER_LIGHT = new Color(218, 220, 224);
    public static final Color COLOR_HINT = new Color(150, 150, 150);
    public static final Color COLOR_TABLE_GRID = new Color(230, 230, 230);
    public static final Color COLOR_TABLE_SEL = new Color(232, 240, 254);
    public static final Color COLOR_TABLE_HEADER = new Color(245, 245, 245);

    private UiTheme() {
    }
}
