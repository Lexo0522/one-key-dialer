package ui;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the CI-release "every glyph is a hollow box" bug: on zh-CN
 * Windows the JDK font enumeration lacks the exact "Microsoft YaHei" family,
 * which used to drop the whole UI onto Segoe UI — a physical font with no CJK
 * glyphs that Java2D never falls back from.
 */
class UiThemeTest {
    @Test
    void uiFontIsNeverABareNonCjkPhysicalFont() {
        assertNotEquals("Segoe UI", UiTheme.FONT_NAME_CN);
        Font uiFont = new Font(UiTheme.FONT_NAME_CN, Font.PLAIN, 13);
        boolean logicalComposite = Font.SANS_SERIF.equals(UiTheme.FONT_NAME_CN);
        assertTrue(uiFont.canDisplayUpTo("\u4e2d\u6587\u663e\u793a\u6d4b\u8bd5") == -1
                || logicalComposite,
            "UI font must carry CJK glyphs or be a logical composite, got: "
                + uiFont.getFamily());
    }

    @Test
    void diagFontSharesResolvedCjkFamily() {
        // Locale.US: getFamily() returns the localized name (微软雅黑) in the default locale
        assertEquals(UiTheme.FONT_NAME_CN, UiTheme.FONT_DIAG.getFamily(Locale.US));
    }
}
