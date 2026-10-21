package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModBlocks;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** CCTV: every security camera on the server; tap to watch live. Only visible with the view permission. */
public class CctvApp extends PhoneApp {
    private boolean requested;
    private S2C.CctvCamera renaming;
    private int tab;

    @Override
    public String id() {
        return "cctv";
    }

    @Override
    protected void build(LinearLayout col) {
        if (renaming != null) {
            buildRename(col);
            return;
        }
        header(col, Component.translatable("smartphone.app.cctv"), null);
        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(C2S.CctvList.INSTANCE);
        }
        col.addChild(new UiKit.Segmented(w, List.of(Component.translatable("smartphone.cctv.app.tab.cameras"),
                Component.translatable("smartphone.cctv.app.tab.clips")), tab, i -> {
            tab = i;
            phone.refresh();
        }));
        if (tab == 1) {
            buildClips(col);
            return;
        }
        List<S2C.CctvCamera> cams = ClientPhoneState.cctvCameras;
        if (cams == null) {
            col.addChild(new UiKit.Label(Component.translatable("smartphone.loading"), UiKit.TEXT_3, w));
            return;
        }
        if (ClientPhoneState.cctvManage) {
            col.addChild(new UiKit.Label(Component.translatable("smartphone.cctv.app.manage_hint"), UiKit.TEXT_2, w).scale(0.85f));
        }
        UiKit.ScrollList list = list("cams", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        if (cams.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.cctv.app.none"), UiKit.TEXT_3));
        ItemStack icon = new ItemStack(ModBlocks.CCTV_ITEM);
        UiKit.ListRow.Leading leading = (g, x, y, size) -> {
            UiKit.roundRect(g, x, y, size, size, 5, UiKit.SURFACE_2);
            g.item(icon, x + (size - 16) / 2, y + (size - 16) / 2);
        };
        for (S2C.CctvCamera c : cams) {
            Component status;
            int color;
            if (!c.sameDim()) {
                status = Component.translatable("smartphone.cctv.app.other_dim");
                color = UiKit.TEXT_3;
            } else if (!c.online()) {
                status = Component.translatable("smartphone.cctv.app.offline");
                color = UiKit.TEXT_3;
            } else if (c.viewers() > 0) {
                status = Component.translatable("smartphone.cctv.app.watching", c.viewers());
                color = UiKit.RED;
            } else {
                status = Component.translatable("smartphone.cctv.app.online");
                color = UiKit.GREEN;
            }
            Component sub = Component.literal(c.pos().getX() + ", " + c.pos().getY() + ", " + c.pos().getZ() + " · ").append(status);
            boolean usable = c.sameDim() && c.online();
            UiKit.ListRow row = new UiKit.ListRow(leading, Component.literal(c.name()), sub, Component.literal(usable ? "▶" : ""),
                    color, usable ? () -> ClientPlayNetworking.send(new C2S.CctvView(c.dimension(), c.pos())) : null);
            if (ClientPhoneState.cctvManage && c.online()) {
                row.button(Component.literal("✎"), UiKit.BLUE, () -> {
                    renaming = c;
                    fields.put("cam_name", c.name().startsWith("Camera ") ? "" : c.name());
                    phone.refresh();
                });
            }
            rows.add(row);
        }
        list.rows(rows);
    }

    /** Clips saved on this computer (.minecraft/cctv/clips). Playing one opens its camera first. */
    private void buildClips(LinearLayout col) {
        col.addChild(new UiKit.Label(Component.translatable("smartphone.cctv.app.clips_hint",
                nl.smartphonemod.client.cctv.CctvClips.dir().toString()), UiKit.TEXT_2, w).scale(0.8f));
        UiKit.ScrollList list = list("clips", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        var clips = nl.smartphonemod.client.cctv.CctvClips.list();
        if (clips.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.cctv.app.no_clips"), UiKit.TEXT_3));
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd-MM HH:mm:ss");
        for (var clip : clips) {
            String when = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clip.wallMs()), java.time.ZoneId.systemDefault()).format(fmt);
            rows.add(new UiKit.ListRow(null, Component.literal(clip.camera()), Component.literal(when + " · " + (clip.durationMs() / 1000) + " s"),
                    Component.literal("▶"), UiKit.TEXT_2, () -> nl.smartphonemod.client.cctv.CctvView.openClip(clip))
                    .button(Component.literal("🗑"), UiKit.RED, () -> {
                        nl.smartphonemod.client.cctv.CctvClips.delete(clip);
                        phone.refresh();
                    }));
        }
        list.rows(rows);
    }

    private void buildRename(LinearLayout col) {
        header(col, Component.translatable("smartphone.cctv.app.rename"), null);
        col.addChild(new UiKit.Label(Component.literal(renaming.pos().getX() + ", " + renaming.pos().getY() + ", " + renaming.pos().getZ()),
                UiKit.TEXT_2, w));
        col.addChild(field("cam_name", w, Component.translatable("smartphone.cctv.app.name_hint"), 24, this::saveName));
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        int bw = (w - 4) / 2;
        row.addChild(new UiKit.Button(bw, 18, Component.translatable("smartphone.cctv.app.save"), UiKit.Style.FILLED, UiKit.BLUE, this::saveName));
        row.addChild(new UiKit.Button(bw, 18, Component.translatable("smartphone.cctv.app.cancel"), UiKit.Style.GREY, UiKit.TEXT_2, () -> {
            renaming = null;
            phone.refresh();
        }));
        col.addChild(row);
    }

    private void saveName() {
        if (renaming == null) return;
        ClientPlayNetworking.send(new C2S.CctvRename(renaming.dimension(), renaming.pos(), text("cam_name")));
        renaming = null;
        phone.refresh();
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.CCTV) phone.refresh();
    }
}
