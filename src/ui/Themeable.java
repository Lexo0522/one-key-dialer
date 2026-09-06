package ui;

/**
 * A component that can re-apply the current {@link UiTheme} palette in place,
 * without being recreated. Implementations move their themed-color setup into
 * {@link #restyle()} and call it once from the constructor; the live theme
 * switch invokes it again on every open window (EDT).
 */
public interface Themeable {
    /** Re-read every themed color from {@link UiTheme}. Must run on the EDT. */
    void restyle();
}
