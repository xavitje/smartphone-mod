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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Opslag: your storage hubs (and your friends' hubs); tap one to open it from anywhere. */
public class StorageApp extends PhoneApp {
    private boolean requested;

    @Override
    public String id() {
        return "storage";
    }

    private static String dimensionName(String id) {
        return switch (id) {
            case "minecraft:overworld" -> Component.translatable("smartphone.storage.dim.overworld").getString();
            case "minecraft:the_nether" -> Component.translatable("smartphone.storage.dim.nether").getString();
            case "minecraft:the_end" -> Component.translatable("smartphone.storage.dim.end").getString();
            default -> id.substring(id.indexOf(':') + 1);
        };
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.storage"), null);
        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(C2S.StorageHubsRequest.INSTANCE);
        }
        S2C.StorageHubs data = ClientPhoneState.storageHubs;
        if (data == null) {
            col.addChild(new UiKit.Label(Component.translatable("smartphone.loading"), UiKit.TEXT_3, w));
            return;
        }
        if (!data.remoteEnabled()) {
            col.addChild(new UiKit.Label(Component.translatable("smartphone.storage.remote_off"), UiKit.ORANGE, w));
        } else if (data.fee() > 0) {
            col.addChild(new UiKit.Label(Component.translatable("smartphone.storage.fee_hint", data.fee()), UiKit.TEXT_2, w));
        }
        UiKit.ScrollList list = list("hubs", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        ItemStack icon = new ItemStack(ModBlocks.STORAGE_HUB_ITEM);
        UiKit.ListRow.Leading leading = (g, x, y, size) -> {
            UiKit.roundRect(g, x, y, size, size, 5, UiKit.SURFACE_2);
            g.item(icon, x + (size - 16) / 2, y + (size - 16) / 2);
        };
        if (data.hubs().isEmpty()) {
            rows.add(UiKit.textRow(Component.translatable("smartphone.storage.no_hubs"), UiKit.TEXT_3));
            rows.add(UiKit.textRow(Component.translatable("smartphone.storage.friends_hint"), UiKit.TEXT_3));
        }
        for (S2C.HubEntry h : data.hubs()) {
            String name = h.name().isBlank() ? Component.translatable("block.smartphone.storage_hub").getString() : h.name();
            Component sub = Component.literal(h.x() + ", " + h.y() + ", " + h.z() + " · " + dimensionName(h.dimension())
                    + (h.mine() ? "" : " · " + h.owner()));
            Runnable open = () -> {
                if (!data.remoteEnabled()) return;
                ClientPlayNetworking.send(new C2S.StorageOpenRemote(h.dimension(), new BlockPos(h.x(), h.y(), h.z())));
            };
            rows.add(new UiKit.ListRow(leading, Component.literal(name), sub, Component.literal("›"),
                    data.remoteEnabled() ? UiKit.TEXT_2 : UiKit.TEXT_3, open));
        }
        list.rows(rows);
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.STORAGE) phone.refresh();
    }
}
