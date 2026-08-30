package ui;

import model.SettingsSnapshot;

import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

/**
 * Shared Swing look constants for extracted UI components.
 * <p>
 * Colors are resolved once at startup via {@link #init(String)}: components
 * capture the references when they are constructed, so a theme change needs an
 * app restart. Before {@link #init} runs (tests, static access) the light
 * palette is active.
 */
public final class UiTheme {
    /** CJK-capable UI font; falls back to a logical composite font when YaHei is absent. */
    public static final String FONT_NAME_CN = resolveCjkFontName();

    public static final Font FONT_CN = new Font(FONT_NAME_CN, Font.PLAIN, 13);
    public static final Font FONT_CN_BOLD = new Font(FONT_NAME_CN, Font.BOLD, 13);
    public static final Font FONT_CN_SMALL = new Font(FONT_NAME_CN, Font.PLAIN, 11);
    public static final Font FONT_DIAG = new Font(FONT_NAME_CN, Font.PLAIN, 12);

    public static Color COLOR_SUCCESS;
    public static Color COLOR_ERROR;
    public static Color COLOR_INFO;
    public static Color COLOR_WARNING;
    public static Color COLOR_BG;
    public static Color COLOR_CARD;
    public static Color COLOR_DARK;
    public static Color COLOR_BORDER;
    public static Color COLOR_BORDER_LIGHT;
    public static Color COLOR_HINT;
    public static Color COLOR_TABLE_GRID;
    public static Color COLOR_TABLE_SEL;
    public static Color COLOR_TABLE_HEADER;

    private static boolean dark;

    static {
        applyLight();
    }

    private UiTheme() {
    }

    /**
     * Resolve the effective theme from the {@code system | light | dark} preference.
     * "system" reads the Windows app-light/dark registry switch (best effort).
     */
    public static void init(String themePref) {
        dark = resolveDark(themePref);
        if (dark) {
            applyDark();
        } else {
            applyLight();
        }
    }

    public static boolean isDark() {
        return dark;
    }

    private static boolean resolveDark(String themePref) {
        if (SettingsSnapshot.THEME_DARK.equals(themePref)) return true;
        if (SettingsSnapshot.THEME_LIGHT.equals(themePref)) return false;
        return systemPrefersDark();
    }

    private static boolean systemPrefersDark() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme")
                .redirectErrorStream(true)
                .start();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            byte[] out = p.getInputStream().readAllBytes();
            String text = new String(out, "UTF-8");
            // "AppsUseLightTheme    REG_DWORD    0x0" → 0 means dark
            int idx = text.indexOf("0x");
            if (idx >= 0) {
                int value = Integer.parseInt(text.substring(idx).split("\\s+")[0].substring(2), 16);
                return value == 0;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void applyLight() {
        COLOR_SUCCESS = new Color(34, 197, 94);
        COLOR_ERROR = new Color(220, 53, 69);
        COLOR_INFO = new Color(0, 123, 255);
        COLOR_WARNING = new Color(255, 193, 7);
        COLOR_BG = new Color(248, 249, 250);
        COLOR_CARD = Color.WHITE;
        COLOR_DARK = new Color(40, 44, 52);
        COLOR_BORDER = new Color(209, 213, 219);
        COLOR_BORDER_LIGHT = new Color(218, 220, 224);
        COLOR_HINT = new Color(150, 150, 150);
        COLOR_TABLE_GRID = new Color(230, 230, 230);
        COLOR_TABLE_SEL = new Color(232, 240, 254);
        COLOR_TABLE_HEADER = new Color(245, 245, 245);
    }

    private static void applyDark() {
        COLOR_SUCCESS = new Color(52, 199, 123);
        COLOR_ERROR = new Color(239, 83, 80);
        COLOR_INFO = new Color(77, 163, 255);
        COLOR_WARNING = new Color(255, 202, 44);
        COLOR_BG = new Color(30, 31, 34);
        COLOR_CARD = new Color(43, 45, 49);
        COLOR_DARK = new Color(212, 215, 221);
        COLOR_BORDER = new Color(64, 67, 73);
        COLOR_BORDER_LIGHT = new Color(56, 59, 65);
        COLOR_HINT = new Color(138, 143, 152);
        COLOR_TABLE_GRID = new Color(56, 59, 65);
        COLOR_TABLE_SEL = new Color(38, 62, 92);
        COLOR_TABLE_HEADER = new Color(47, 50, 55);
    }

    private static String resolveCjkFontName() {
        // Family-name enumeration is NOT a reliable existence probe: on zh-CN
        // Windows the JDK lists the localized name (微软雅黑) and "Microsoft YaHei
        // UI", but not "Microsoft YaHei" — an exact-match check there silently
        // degraded the whole UI to a bare physical font. Java2D never falls back
        // for physical fonts, and Segoe UI/Consolas have no CJK glyphs, so every
        // Chinese character rendered as a hollow box. Construct + verify instead:
        // an unknown name resolves to the logical Dialog family, which must be
        // rejected as a false positive.
        for (String candidate : new String[]{
            "Microsoft YaHei", "Microsoft YaHei UI", "\u5fae\u8f6f\u96c5\u9ed1"}) {
            Font f = new Font(candidate, Font.PLAIN, 13);
            if (f.canDisplayUpTo("\u4e2d\u6587") == -1
                && !"Dialog".equals(f.getFamily(Locale.US))) {
                return candidate;
            }
        }
        // Logical composite fonts keep the platform CJK mapping on any machine;
        // a bare physical font (e.g. Segoe UI) would tofu every CJK character.
        return Font.SANS_SERIF;
    }
}
