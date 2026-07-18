/**
 * Backward-compatible entry for scripts that still invoke {@code PPoEDialer}.
 * Prefer {@link com.lexo0522.ppoe.PPoEDialer} as the real main class.
 */
public final class PPoEDialer {
    private PPoEDialer() {
    }

    public static void main(String[] args) {
        com.lexo0522.ppoe.PPoEDialer.main(args);
    }
}
