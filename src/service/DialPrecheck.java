package service;

import model.PasswordChars;

import java.util.Optional;

/**
 * Pure pre-dial gates (no Swing). UI layer maps failures to messages / dialogs.
 */
public final class DialPrecheck {
    public enum Failure {
        ALREADY_ONLINE,
        NO_ACCOUNT,
        EMPTY_USERNAME,
        EMPTY_PASSWORD
    }

    private DialPrecheck() {
    }

    /**
     * @param online      session already connected
     * @param hasAccount  current account row exists
     * @param username    trimmed username from UI/cache
     * @param password    password chars (not cleared by this method)
     */
    public static Optional<Failure> check(boolean online, boolean hasAccount,
                                          String username, char[] password) {
        if (online) return Optional.of(Failure.ALREADY_ONLINE);
        if (!hasAccount) return Optional.of(Failure.NO_ACCOUNT);
        String user = username != null ? username.trim() : "";
        if (user.isEmpty()) return Optional.of(Failure.EMPTY_USERNAME);
        if (PasswordChars.isBlank(password)) return Optional.of(Failure.EMPTY_PASSWORD);
        return Optional.empty();
    }

    public static String logMessage(Failure f) {
        if (f == null) return "";
        switch (f) {
            case ALREADY_ONLINE:
                return "当前已连接，无需重复拨号";
            case NO_ACCOUNT:
                return "当前账号索引无效，请重新选择账号";
            case EMPTY_USERNAME:
                return "拨号前校验失败: 学号/账号为空";
            case EMPTY_PASSWORD:
                return "拨号前校验失败: 密码为空";
            default:
                return "拨号前校验失败";
        }
    }

    public static String dialogMessage(Failure f) {
        if (f == null) return "拨号失败";
        switch (f) {
            case NO_ACCOUNT:
                return "当前账号无效，请重新选择账号";
            case EMPTY_USERNAME:
                return "请输入学号/账号";
            case EMPTY_PASSWORD:
                return "请输入密码";
            case ALREADY_ONLINE:
                return "当前已连接";
            default:
                return "拨号失败";
        }
    }

    /** Failures that warrant an interactive dialog when {@code interactive=true}. */
    public static boolean showDialog(Failure f) {
        return f == Failure.NO_ACCOUNT
            || f == Failure.EMPTY_USERNAME
            || f == Failure.EMPTY_PASSWORD;
    }
}
