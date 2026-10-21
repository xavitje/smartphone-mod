package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.hud.Notifications;
import nl.smartphonemod.client.phone.MapWidget;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** GPS: live minimap with players, waypoints with distance/direction, and navigation. */
public class MapApp extends PhoneApp {
    private MapWidget map;
    private boolean subscribed;
    private boolean adding;

    @Override
    public String id() {
        return "map";
    }

    @Override
    protected void build(LinearLayout col) {
        String mode = modeLabel();
        header(col, Component.translatable("smartphone.app.map"), new UiKit.Button(font.width(mode) + 12, 14,
                Component.literal(mode), UiKit.Style.TINTED, modeColor(), () -> phone.open(new SettingsApp())));
        if (!subscribed) {
            subscribed = true;
            ClientPlayNetworking.send(new C2S.GpsSubscribe(true));
            ClientPlayNetworking.send(C2S.MapRequest.INSTANCE);
        }
        int mapH = Math.min(w, (int) (h * 0.48));
        if (map == null) map = new MapWidget(w, mapH);
        else map.resize(w, mapH);
        col.addChild(map);

        LinearLayout controls = LinearLayout.horizontal().spacing(3);
        controls.addChild(new UiKit.Button(18, 16, Component.literal("−"), UiKit.Style.GREY, UiKit.TEXT, () -> {
            map.zoom(map.zoom() * 2);
            phone.refresh();
        }));
        controls.addChild(new UiKit.LiveLabel(() -> Component.literal("1:" + map.zoom()), UiKit.TEXT_2, 22).centered(),
                s -> s.alignVertically(0.5f));
        controls.addChild(new UiKit.Button(18, 16, Component.literal("+"), UiKit.Style.GREY, UiKit.TEXT, () -> {
            map.zoom(map.zoom() / 2);
            phone.refresh();
        }));
        controls.addChild(net.minecraft.client.gui.layouts.SpacerElement.width(Math.max(0, w - 18 - 22 - 18 - 56 - 12)));
        controls.addChild(new UiKit.Button(56, 16, Component.translatable("smartphone.map.add_here"), UiKit.Style.TINTED, UiKit.BLUE, () -> {
            adding = !adding;
            focusKey = adding ? "wp_name" : null;
            phone.refresh();
        }));
        col.addChild(controls);
        col.addChild(new UiKit.LiveLabel(this::coords, UiKit.TEXT_2, w).centered());

        if (adding) {
            LinearLayout add = LinearLayout.horizontal().spacing(4);
            add.addChild(field("wp_name", w - 4 - 50, Component.translatable("smartphone.map.waypoint_name"), 24, this::saveWaypoint));
            add.addChild(new UiKit.Button(50, 18, Component.translatable("smartphone.map.save"), UiKit.Style.FILLED, UiKit.BLUE, this::saveWaypoint));
            col.addChild(add);
        }

        UiKit.ScrollList list = list("waypoints", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        rows.add(UiKit.headerRow(Component.translatable("smartphone.map.waypoints")));
        if (ClientPhoneState.waypoints.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.map.no_waypoints"), UiKit.TEXT_3));
        var player = mc.player;
        String dim = player == null ? "" : player.level().dimension().identifier().toString();
        List<S2C.WaypointEntry> sorted = new ArrayList<>(ClientPhoneState.waypoints);
        if (player != null) sorted.sort(Comparator.comparingDouble(wp -> wp.dimension().equals(dim)
                ? Math.hypot(wp.x() - player.getX(), wp.z() - player.getZ()) : Double.MAX_VALUE));
        for (S2C.WaypointEntry wp : sorted) {
            boolean sameDim = wp.dimension().equals(dim);
            boolean nav = wp.id() == ClientPhoneState.navigationTarget;
            UiKit.ListRow.Leading arrow = (g, x, y, size) -> {
                UiKit.circle(g, x + size / 2, y + size / 2, size / 2, UiKit.withAlpha(nav ? UiKit.ORANGE : UiKit.BLUE, 0x44));
                if (sameDim && player != null) {
                    double angle = Math.atan2(wp.z() + 0.5 - player.getZ(), wp.x() + 0.5 - player.getX())
                            - Math.toRadians(player.getYRot() + 90);
                    Notifications.drawArrow(g, x + size / 2, y + size / 2, angle);
                } else {
                    UiKit.textCentered(g, Component.literal("✕"), x + size / 2, y + (size - 8) / 2, UiKit.TEXT_3);
                }
            };
            Component sub = Component.literal(sameDim && player != null
                    ? (int) Math.hypot(wp.x() - player.getX(), wp.z() - player.getZ()) + " m · " + wp.x() + ", " + wp.y() + ", " + wp.z()
                    : wp.dimension().replace("minecraft:", ""));
            rows.add(new UiKit.ListRow(arrow, Component.literal(wp.name()), sub, null, 0, null)
                    .button(Component.translatable(nav ? "smartphone.map.stop_nav" : "smartphone.map.navigate"), nav ? UiKit.ORANGE : UiKit.BLUE, () -> {
                        ClientPhoneState.navigationTarget = nav ? 0 : wp.id();
                        phone.refresh();
                    })
                    .button(Component.literal("✕"), UiKit.RED, () -> {
                        if (ClientPhoneState.navigationTarget == wp.id()) ClientPhoneState.navigationTarget = 0;
                        ClientPlayNetworking.send(new C2S.WaypointDelete(wp.id()));
                    }));
        }
        list.rows(rows);
    }

    private Component coords() {
        if (mc.player == null) return Component.empty();
        int others = (int) ClientPhoneState.positions.stream().filter(p -> !p.uuid().equals(mc.player.getUUID())).count();
        boolean live = System.currentTimeMillis() - ClientPhoneState.positionsTime < 3000;
        return Component.literal(mc.player.getBlockX() + ", " + mc.player.getBlockZ() + "  ☺" + others)
                .append(Component.literal(live ? " ●" : "").withColor(UiKit.GREEN));
    }

    private String modeLabel() {
        return Component.translatable(switch (ClientPhoneState.gpsMode) {
            case 0 -> "smartphone.privacy.nobody_short";
            case 1 -> "smartphone.privacy.friends_short";
            default -> "smartphone.privacy.everyone_short";
        }).getString();
    }

    private int modeColor() {
        return switch (ClientPhoneState.gpsMode) {
            case 0 -> UiKit.TEXT_2;
            case 1 -> UiKit.YELLOW;
            default -> UiKit.GREEN;
        };
    }

    private void saveWaypoint() {
        String name = text("wp_name");
        if (name.isEmpty()) return;
        ClientPlayNetworking.send(new C2S.WaypointSave(name));
        fields.put("wp_name", "");
        adding = false;
        focusKey = null;
        phone.refresh();
    }

    private int ticks;

    @Override
    protected void tick() {
        // Distances and arrows in the list follow the player.
        if (++ticks % 10 == 0 && !adding) phone.refresh();
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.MAP || update == PhoneScreen.Update.ALERT) phone.refresh();
    }

    @Override
    protected void close() {
        if (subscribed) ClientPlayNetworking.send(new C2S.GpsSubscribe(false));
        subscribed = false;
        if (map != null) map.dispose();
        map = null;
    }
}
