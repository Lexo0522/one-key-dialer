package ui;

import javax.swing.*;
import java.awt.*;

/**
 * Schedule tab: daily dial / disconnect toggles and time spinners.
 */
public class SchedulePanel extends JPanel {
    public interface Host {
        void onScheduleChanged();

        void saveSettings();
    }

    private final JCheckBox chkScheduledDial = new JCheckBox("定时自动拨号");
    private final JCheckBox chkScheduledDisconnect = new JCheckBox("定时自动断开");
    private final JSpinner spnDialHour = new JSpinner(new SpinnerNumberModel(8, 0, 23, 1));
    private final JSpinner spnDialMinute = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
    private final JSpinner spnDisconnectHour = new JSpinner(new SpinnerNumberModel(23, 0, 23, 1));
    private final JSpinner spnDisconnectMinute = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));

    public SchedulePanel(Host host) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(UiTheme.COLOR_BG);
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        add(buildDialCard(host));
        add(Box.createVerticalStrut(12));
        add(buildDisconnectCard(host));
        add(Box.createVerticalGlue());
    }

    private JPanel buildDialCard(Host host) {
        JPanel dialCard = createCard();
        dialCard.setLayout(new BorderLayout(10, 5));

        JPanel dialHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        dialHeader.setOpaque(false);
        chkScheduledDial.setFont(UiTheme.FONT_CN_BOLD);
        chkScheduledDial.addActionListener(e -> {
            host.onScheduleChanged();
            host.saveSettings();
        });
        dialHeader.add(chkScheduledDial);

        JPanel dialTime = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        dialTime.setOpaque(false);
        JLabel lblDialTime = new JLabel("每天");
        lblDialTime.setFont(UiTheme.FONT_CN);
        dialTime.add(lblDialTime);
        styleSpinner(spnDialHour);
        styleSpinner(spnDialMinute);
        dialTime.add(spnDialHour);
        dialTime.add(new JLabel("时") {{ setFont(UiTheme.FONT_CN); }});
        dialTime.add(spnDialMinute);
        dialTime.add(new JLabel("分") {{ setFont(UiTheme.FONT_CN); }});
        attachSpinnerListeners(host, spnDialHour, spnDialMinute);

        dialCard.add(dialHeader, BorderLayout.NORTH);
        dialCard.add(dialTime, BorderLayout.CENTER);
        return dialCard;
    }

    private JPanel buildDisconnectCard(Host host) {
        JPanel card = createCard();
        card.setLayout(new BorderLayout(10, 5));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        header.setOpaque(false);
        chkScheduledDisconnect.setFont(UiTheme.FONT_CN_BOLD);
        chkScheduledDisconnect.addActionListener(e -> {
            host.onScheduleChanged();
            host.saveSettings();
        });
        header.add(chkScheduledDisconnect);

        JPanel time = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        time.setOpaque(false);
        JLabel lbl = new JLabel("每天");
        lbl.setFont(UiTheme.FONT_CN);
        time.add(lbl);
        styleSpinner(spnDisconnectHour);
        styleSpinner(spnDisconnectMinute);
        time.add(spnDisconnectHour);
        time.add(new JLabel("时") {{ setFont(UiTheme.FONT_CN); }});
        time.add(spnDisconnectMinute);
        time.add(new JLabel("分") {{ setFont(UiTheme.FONT_CN); }});
        attachSpinnerListeners(host, spnDisconnectHour, spnDisconnectMinute);

        card.add(header, BorderLayout.NORTH);
        card.add(time, BorderLayout.CENTER);
        return card;
    }

    private static void styleSpinner(JSpinner spn) {
        spn.setFont(UiTheme.FONT_CN_BOLD);
        spn.setPreferredSize(new Dimension(55, 30));
    }

    private void attachSpinnerListeners(Host host, JSpinner... spinners) {
        for (JSpinner spn : spinners) {
            spn.addChangeListener(e -> {
                host.onScheduleChanged();
                host.saveSettings();
            });
        }
    }

    private static JPanel createCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(UiTheme.COLOR_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiTheme.COLOR_BORDER),
            BorderFactory.createEmptyBorder(10, 15, 10, 15)));
        return card;
    }

    public boolean isDialEnabled() {
        return chkScheduledDial.isSelected();
    }

    public boolean isDisconnectEnabled() {
        return chkScheduledDisconnect.isSelected();
    }

    public int dialHour() {
        return (Integer) spnDialHour.getValue();
    }

    public int dialMinute() {
        return (Integer) spnDialMinute.getValue();
    }

    public int disconnectHour() {
        return (Integer) spnDisconnectHour.getValue();
    }

    public int disconnectMinute() {
        return (Integer) spnDisconnectMinute.getValue();
    }

    public void setDialEnabled(boolean v) {
        chkScheduledDial.setSelected(v);
    }

    public void setDisconnectEnabled(boolean v) {
        chkScheduledDisconnect.setSelected(v);
    }

    public void setDialTime(int hour, int minute) {
        spnDialHour.setValue(hour);
        spnDialMinute.setValue(minute);
    }

    public void setDisconnectTime(int hour, int minute) {
        spnDisconnectHour.setValue(hour);
        spnDisconnectMinute.setValue(minute);
    }

    /** Apply schedule fields from typed settings. */
    public void applyFrom(model.AppSettings s) {
        if (s == null) return;
        setDialEnabled(s.scheduledDial);
        setDisconnectEnabled(s.scheduledDisconnect);
        try { setDialTime(s.scheduledDialHour, s.scheduledDialMinute); } catch (Exception ignored) { }
        try { setDisconnectTime(s.scheduledDisconnectHour, s.scheduledDisconnectMinute); } catch (Exception ignored) { }
    }

    /** Write schedule fields into settings object. */
    public void writeTo(model.AppSettings s) {
        if (s == null) return;
        s.scheduledDial = isDialEnabled();
        s.scheduledDialHour = dialHour();
        s.scheduledDialMinute = dialMinute();
        s.scheduledDisconnect = isDisconnectEnabled();
        s.scheduledDisconnectHour = disconnectHour();
        s.scheduledDisconnectMinute = disconnectMinute();
    }
}
