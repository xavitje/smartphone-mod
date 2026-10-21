package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 112: one big button (press twice to confirm) that shares your location; recent alerts below. */
public class EmergencyApp extends PhoneApp {
    private long armedAt;

    @Override
    public String id() {
        return "emergency";
    }

    private boolean armed() {
        return System.currentTimeMillis() - armedAt < 4000;
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.emergency"), null);
        col.addChild(new UiKit.Label(Component.translatable(ClientPhoneState.serverInfo.emergencyToAll()
                ? "smartphone.emergency.info_all" : "smartphone.emergency.info_admins"), UiKit.TEXT_2, w));
        col.addChild(new SosButton(w), s -> s.paddingVertical(6));
        col.addChild(new UiKit.LiveLabel(() -> armed()
                ? Component.translatable("smartphone.emergency.confirm").withStyle(ChatFormatting.BOLD)
                : Component.translatable("smartphone.emergency.press"), UiKit.RED, w).centered());

        UiKit.ScrollList list = list("alerts", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        rows.add(UiKit.headerRow(Component.translatable("smartphone.emergency.recent")));
        if (ClientPhoneState.alerts.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.bank.none"), UiKit.TEXT_3));
        for (ClientPhoneState.Alert a : ClientPhoneState.alerts) {
            var al = a.alert();
            long minutes = (System.currentTimeMillis() - a.time()) / 60000;
            rows.add(new UiKit.ListRow(UiKit.avatarOf(al.name(), false), Component.literal(al.name()),
                    Component.literal(al.x() + ", " + al.y() + ", " + al.z() + " · " + minutes + " min"), null, 0,
                    () -> phone.open(new MapApp())));
        }
        list.rows(rows);
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.ALERT) phone.refresh();
    }

    private class SosButton extends UiKit.Widget {
        SosButton(int width) {
            super(width, 74);
            onClick(() -> {
                if (!armed()) {
                    armedAt = System.currentTimeMillis();
                } else {
                    armedAt = 0;
                    ClientPlayNetworking.send(C2S.Emergency.INSTANCE);
                }
            });
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            int cx = getX() + width / 2, cy = getY() + height / 2;
            boolean armed = armed();
            if (armed) {
                int pulse = (int) ((System.currentTimeMillis() / 60) % 8);
                UiKit.circle(g, cx, cy, 30 + pulse, UiKit.withAlpha(UiKit.RED, 0x40));
            }
            UiKit.circle(g, cx, cy, 31, isHovered() ? UiKit.shade(UiKit.RED, 1.15f) : UiKit.RED);
            UiKit.bigText(g, Component.literal("SOS").withStyle(ChatFormatting.BOLD), cx - (int) (font.width("SOS") * 1.2f) - 2, cy - 9, 2.4f, UiKit.WHITE);
        }
    }
}
