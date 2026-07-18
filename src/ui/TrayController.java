package ui;

import model.AccountInfo;
import util.FormatUtil;
import util.RedactUtil;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * System tray icon, tooltip, and show/hide helpers.
 * Uses Swing JPopupMenu (not AWT PopupMenu) so Chinese labels render correctly
 * under -Dfile.encoding=UTF-8 on Windows.
 * <p>
 * Popup is anchored on a dedicated always-on-top {@link JWindow} so the menu still
 * appears when the main frame is hidden (minimize-to-tray).
 */
public final class TrayController {
    public interface Host {
        void showWindow();

        void exitProgram();

        boolean isOnline();

        AccountInfo currentAccount();

        long connectTimeMillis();

        long currentSpeedDown();

        long currentSpeedUp();

        long totalDownload();

        long totalUpload();

        long sessionStartDownload();

        long sessionStartUpload();

        /** Account list for tray switch menu; may be empty. */
        default List<AccountInfo> accounts() {
            return java.util.Collections.emptyList();
        }

        /** Switch to account index (EDT). */
        default void switchToAccount(int index) {
        }

        default void dialNow() {
        }

        default void disconnectNow() {
        }

        default void checkForUpdates() {
        }
    }

    private static final int POPUP_SHOW_DELAY_MS = 80;

    private final String appTitle;
    private final Host host;
    private final Consumer<String> onError;

    private SystemTray systemTray;
    private TrayIcon trayIcon;
    private Image trayImageOffline;
    private Image trayImageOnline;
    private JPopupMenu trayPopup;
    private JMenu accountsMenu;
    private JWindow popupInvoker;
    private Timer showTimer;
    private final StringBuilder tooltipSb = new StringBuilder(256);

    public TrayController(String appTitle, Host host, Consumer<String> onError) {
        this.appTitle = appTitle;
        this.host = host;
        this.onError = onError != null ? onError : msg -> {
        };
    }

    public void init() {
        if (!SystemTray.isSupported()) return;
        if (trayIcon != null) return;
        systemTray = SystemTray.getSystemTray();
        trayImageOffline = createTrayImage(Color.GRAY);
        trayImageOnline = createTrayImage(UiTheme.COLOR_SUCCESS);
        trayIcon = new TrayIcon(trayImageOffline, appTitle);
        trayIcon.setImageAutoSize(true);
        trayIcon.setToolTip(appTitle + "\n未连接");

        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        trayPopup = buildPopupMenu();
        trayPopup.setLightWeightPopupEnabled(false);
        ensurePopupInvoker();

        trayPopup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                refreshAccountMenu();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                hidePopupInvoker();
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                hidePopupInvoker();
            }
        });

        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    scheduleTrayPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    scheduleTrayPopup(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    host.showWindow();
                }
            }
        });

        try {
            systemTray.add(trayIcon);
        } catch (AWTException e) {
            onError.accept("托盘图标失败");
            disposePopupUi();
            trayIcon = null;
        }
    }

    public void ensureReady() {
        if (trayIcon == null) init();
    }

    public void remove() {
        cancelShowTimer();
        disposePopupUi();
        if (systemTray != null && trayIcon != null) {
            systemTray.remove(trayIcon);
        }
        trayIcon = null;
    }

    public void updateOnlineIcon(boolean online) {
        if (trayIcon != null) {
            trayIcon.setImage(online ? trayImageOnline : trayImageOffline);
        }
    }

    public void displayMessage(String title, String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    public void updateTooltip() {
        if (trayIcon == null) return;
        tooltipSb.setLength(0);
        tooltipSb.append(appTitle).append('\n');
        if (host.isOnline()) {
            tooltipSb.append("状态: 已连接\n");
            AccountInfo current = host.currentAccount();
            if (current != null) {
                tooltipSb.append("账号: ").append(RedactUtil.maskAccount(current.username)).append('\n');
            }
            long ct = host.connectTimeMillis();
            if (ct > 0) {
                long sec = (System.currentTimeMillis() - ct) / 1000;
                tooltipSb.append("时长: ");
                FormatUtil.appendTime(tooltipSb, sec).append('\n');
            }
            tooltipSb.append("↓ ");
            FormatUtil.appendSpeed(tooltipSb, host.currentSpeedDown());
            tooltipSb.append('\n');
            tooltipSb.append("↑ ");
            FormatUtil.appendSpeed(tooltipSb, host.currentSpeedUp());
            tooltipSb.append('\n');
            long sessionTotal = (host.totalDownload() - host.sessionStartDownload())
                + (host.totalUpload() - host.sessionStartUpload());
            tooltipSb.append("总流量: ");
            FormatUtil.appendBytes(tooltipSb, sessionTotal);
        } else {
            tooltipSb.append("状态: 未连接");
        }
        trayIcon.setToolTip(tooltipSb.toString());
    }

    /** Rebuild the switch-account submenu from {@link Host#accounts()}. Safe on EDT. */
    public void refreshAccountMenu() {
        if (accountsMenu == null) return;
        accountsMenu.removeAll();
        List<AccountInfo> list = host.accounts();
        if (list == null || list.isEmpty()) {
            JMenuItem empty = new JMenuItem("(无账号)");
            empty.setEnabled(false);
            empty.setFont(UiTheme.FONT_CN);
            accountsMenu.add(empty);
            return;
        }
        AccountInfo current = host.currentAccount();
        for (int i = 0; i < list.size(); i++) {
            AccountInfo a = list.get(i);
            if (a == null) continue;
            final int idx = i;
            String label = a.name != null && !a.name.isEmpty() ? a.name : a.username;
            if (current != null && current == a) {
                label = "✓ " + label;
            }
            JMenuItem item = new JMenuItem(label);
            item.setFont(UiTheme.FONT_CN);
            item.addActionListener(e -> host.switchToAccount(idx));
            accountsMenu.add(item);
        }
    }

    private JPopupMenu buildPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        popup.setFont(UiTheme.FONT_CN);

        JMenuItem showItem = new JMenuItem("显示窗口");
        showItem.setFont(UiTheme.FONT_CN);
        showItem.addActionListener(e -> host.showWindow());
        popup.add(showItem);

        JMenuItem dialItem = new JMenuItem("连接宽带");
        dialItem.setFont(UiTheme.FONT_CN);
        dialItem.addActionListener(e -> host.dialNow());
        popup.add(dialItem);

        JMenuItem discItem = new JMenuItem("断开连接");
        discItem.setFont(UiTheme.FONT_CN);
        discItem.addActionListener(e -> host.disconnectNow());
        popup.add(discItem);

        popup.addSeparator();

        accountsMenu = new JMenu("切换账号");
        accountsMenu.setFont(UiTheme.FONT_CN);
        popup.add(accountsMenu);
        refreshAccountMenu();

        popup.addSeparator();

        JMenuItem updateItem = new JMenuItem("检查更新");
        updateItem.setFont(UiTheme.FONT_CN);
        updateItem.addActionListener(e -> host.checkForUpdates());
        popup.add(updateItem);

        popup.addSeparator();

        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.setFont(UiTheme.FONT_CN);
        exitItem.addActionListener(e -> host.exitProgram());
        popup.add(exitItem);

        return popup;
    }

    private void ensurePopupInvoker() {
        if (popupInvoker != null) return;
        popupInvoker = new JWindow();
        popupInvoker.setAlwaysOnTop(true);
        popupInvoker.setFocusableWindowState(false);
        popupInvoker.setSize(1, 1);
        try {
            popupInvoker.setType(Window.Type.POPUP);
        } catch (Exception ignored) {
        }
    }

    private void hidePopupInvoker() {
        if (popupInvoker != null && popupInvoker.isVisible()) {
            popupInvoker.setVisible(false);
        }
    }

    private void disposePopupUi() {
        cancelShowTimer();
        if (trayPopup != null) {
            trayPopup.setVisible(false);
            trayPopup = null;
        }
        accountsMenu = null;
        if (popupInvoker != null) {
            popupInvoker.setVisible(false);
            popupInvoker.dispose();
            popupInvoker = null;
        }
    }

    private void cancelShowTimer() {
        if (showTimer != null) {
            showTimer.stop();
            showTimer = null;
        }
    }

    private void scheduleTrayPopup(MouseEvent e) {
        final Point anchor = resolveTrayPopupPoint(e);
        cancelShowTimer();
        showTimer = new Timer(POPUP_SHOW_DELAY_MS, ev -> {
            showTimer = null;
            Point p = currentPointerOr(anchor);
            showTrayPopupAt(p.x, p.y);
        });
        showTimer.setRepeats(false);
        showTimer.start();
    }

    private static Point resolveTrayPopupPoint(MouseEvent e) {
        Point fromPointer = currentPointerOr(null);
        if (fromPointer != null) {
            return fromPointer;
        }
        if (e != null) {
            try {
                Point los = e.getLocationOnScreen();
                if (los != null) return new Point(los);
            } catch (Exception ignored) {
            }
            try {
                return new Point(e.getXOnScreen(), e.getYOnScreen());
            } catch (Exception ignored) {
            }
            return new Point(e.getX(), e.getY());
        }
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle b = ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return new Point(b.x + b.width - 32, b.y + b.height - 32);
    }

    private static Point currentPointerOr(Point fallback) {
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info != null) {
                Point loc = info.getLocation();
                if (loc != null) {
                    return new Point(loc);
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? new Point(fallback) : null;
    }

    private void showTrayPopupAt(int screenX, int screenY) {
        Runnable show = () -> {
            if (trayPopup == null) return;
            ensurePopupInvoker();
            if (popupInvoker == null) return;

            trayPopup.pack();
            Dimension menuSize = trayPopup.getPreferredSize();
            if (menuSize.width <= 0) menuSize.width = 120;
            if (menuSize.height <= 0) menuSize.height = 60;

            GraphicsConfiguration gc = graphicsConfigurationAt(screenX, screenY);
            Rectangle bounds = gc.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            int minX = bounds.x + insets.left;
            int minY = bounds.y + insets.top;
            int maxX = bounds.x + bounds.width - insets.right;
            int maxY = bounds.y + bounds.height - insets.bottom;

            int maxInset = Math.max(Math.max(insets.top, insets.bottom), Math.max(insets.left, insets.right));
            boolean taskbarBottom = insets.bottom == maxInset && insets.bottom > 0;
            boolean taskbarTop = insets.top == maxInset && insets.top > 0 && !taskbarBottom;
            boolean taskbarLeft = insets.left == maxInset && insets.left > 0 && !taskbarBottom && !taskbarTop;
            boolean taskbarRight = insets.right == maxInset && insets.right > 0
                && !taskbarBottom && !taskbarTop && !taskbarLeft;

            int x = screenX;
            int y = screenY;
            if (taskbarBottom || (!taskbarTop && !taskbarLeft && !taskbarRight)) {
                y = screenY - menuSize.height;
                if (y < minY) y = screenY;
            } else if (taskbarTop) {
                y = screenY;
            } else {
                y = screenY - menuSize.height / 2;
            }
            if (taskbarRight) {
                x = screenX - menuSize.width;
            } else if (taskbarLeft) {
                x = screenX;
            }

            if (y + menuSize.height > maxY) {
                y = maxY - menuSize.height;
            }
            if (x + menuSize.width > maxX) {
                x = maxX - menuSize.width;
            }
            if (x < minX) x = minX;
            if (y < minY) y = minY;

            if (trayPopup.isVisible()) {
                trayPopup.setVisible(false);
            }

            popupInvoker.setBounds(x, y, 1, 1);
            popupInvoker.setVisible(true);
            popupInvoker.toFront();
            trayPopup.show(popupInvoker, 0, 0);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    private static GraphicsConfiguration graphicsConfigurationAt(int screenX, int screenY) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : ge.getScreenDevices()) {
            GraphicsConfiguration conf = device.getDefaultConfiguration();
            if (conf.getBounds().contains(screenX, screenY)) {
                return conf;
            }
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration();
    }

    private static Image createTrayImage(Color c) {
        int s = 16;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.fillOval(1, 1, s - 2, s - 2);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("P", (s - fm.stringWidth("P")) / 2, (s - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }
}
