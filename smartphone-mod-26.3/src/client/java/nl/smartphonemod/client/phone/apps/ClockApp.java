package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientConfig;
import nl.smartphonemod.client.ClockState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.ui.UiKit;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.network.chat.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Clock (real + in-game time), stopwatch with laps, and a countdown timer with an alarm. */
public class ClockApp extends PhoneApp {
    private int tab;

    @Override
    public String id() {
        return "clock";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.clock"), null);
        col.addChild(new UiKit.Segmented(w, List.of(Component.translatable("smartphone.clock.tab.clock"),
                Component.translatable("smartphone.clock.tab.stopwatch"), Component.translatable("smartphone.clock.tab.timer")), tab, i -> {
            tab = i;
            phone.refresh();
        }));
        switch (tab) {
            case 1 -> stopwatch(col);
            case 2 -> timer(col);
            default -> clock(col);
        }
    }

    private void clock(LinearLayout col) {
        col.addChild(SpacerElement.height(10));
        col.addChild(new UiKit.LiveLabel(() -> Component.literal(LocalDateTime.now().format(DateTimeFormatter.ofPattern(
                ClientConfig.get().use24h ? "HH:mm:ss" : "hh:mm:ss a"))), UiKit.TEXT, w).centered().scale(2.2f));
        col.addChild(new UiKit.LiveLabel(() -> Component.literal(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", HomeApp.locale()))),
                UiKit.TEXT_2, w).centered());
        col.addChild(SpacerElement.height(12));
        col.addChild(new UiKit.Label(Component.translatable("smartphone.clock.ingame"), UiKit.TEXT_2, w).centered());
        col.addChild(new UiKit.LiveLabel(this::gameTime, UiKit.ORANGE, w).centered().scale(1.8f));
        col.addChild(new UiKit.LiveLabel(() -> {
            long day = mc.level == null ? 0 : mc.level.getOverworldClockTime() / 24000L + 1;
            return Component.translatable("smartphone.home.day", day);
        }, UiKit.TEXT_2, w).centered());
    }

    private Component gameTime() {
        if (mc.level == null) return Component.literal("--:--");
        long ticks = (mc.level.getOverworldClockTime() + 6000L) % 24000L;
        long minutes = ticks * 1440L / 24000L;
        return Component.literal(String.format("%02d:%02d", minutes / 60, minutes % 60));
    }

    private void stopwatch(LinearLayout col) {
        col.addChild(SpacerElement.height(10));
        col.addChild(new UiKit.LiveLabel(() -> Component.literal(ClockState.format(ClockState.stopwatchMs(), true)), UiKit.TEXT, w)
                .centered().scale(2.6f));
        LinearLayout buttons = LinearLayout.horizontal().spacing(w - 2 * 52);
        buttons.addChild(new UiKit.Button(52, 22, Component.translatable(ClockState.swRunning ? "smartphone.clock.lap" : "smartphone.clock.reset"),
                UiKit.Style.GREY, UiKit.TEXT, () -> {
            ClockState.stopwatchLapOrReset();
            phone.refresh();
        }).radius(11));
        buttons.addChild(new UiKit.Button(52, 22, Component.translatable(ClockState.swRunning ? "smartphone.clock.stop" : "smartphone.clock.start"),
                UiKit.Style.TINTED, ClockState.swRunning ? UiKit.RED : UiKit.GREEN, () -> {
            ClockState.stopwatchToggle();
            phone.refresh();
        }).radius(11));
        col.addChild(buttons, s -> s.paddingTop(6));
        UiKit.ScrollList list = list("laps", 2, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        for (int i = 0; i < ClockState.laps.size(); i++) {
            long t = ClockState.laps.get(i);
            long prev = i + 1 < ClockState.laps.size() ? ClockState.laps.get(i + 1) : 0;
            rows.add(new UiKit.ListRow(null, Component.translatable("smartphone.clock.lap_n", ClockState.laps.size() - i), null,
                    Component.literal(ClockState.format(t - prev, true)), UiKit.TEXT, null));
        }
        list.rows(rows);
    }

    private void timer(LinearLayout col) {
        col.addChild(SpacerElement.height(10));
        col.addChild(new UiKit.LiveLabel(() -> Component.literal(ClockState.format(ClockState.timerMs(), false)),
                ClockState.timerRunning ? UiKit.ORANGE : UiKit.TEXT, w).centered().scale(3f));
        LinearLayout adjust = LinearLayout.horizontal().spacing(4);
        int bw = (w - 12) / 4;
        for (String[] a : new String[][]{{"−1m", "-60000"}, {"+1m", "60000"}, {"+5m", "300000"}, {"+15m", "900000"}}) {
            long delta = Long.parseLong(a[1]);
            UiKit.Button b = new UiKit.Button(bw, 18, Component.literal(a[0]), UiKit.Style.GREY, UiKit.TEXT, () -> {
                ClockState.timerAdjust(delta);
                phone.refresh();
            });
            b.setEnabled(!ClockState.timerRunning);
            adjust.addChild(b);
        }
        col.addChild(adjust, s -> s.paddingTop(8));
        LinearLayout buttons = LinearLayout.horizontal().spacing(w - 2 * 52);
        buttons.addChild(new UiKit.Button(52, 22, Component.translatable("smartphone.clock.reset"), UiKit.Style.GREY, UiKit.TEXT, () -> {
            ClockState.timerReset();
            phone.refresh();
        }).radius(11));
        buttons.addChild(new UiKit.Button(52, 22, Component.translatable(ClockState.timerRunning ? "smartphone.clock.pause" : "smartphone.clock.start"),
                UiKit.Style.TINTED, ClockState.timerRunning ? UiKit.ORANGE : UiKit.GREEN, () -> {
            ClockState.timerToggle();
            phone.refresh();
        }).radius(11));
        col.addChild(buttons, s -> s.paddingTop(6));
    }
}
