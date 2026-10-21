package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Create a group chat: a name and the members, ticked from the contact list. */
public class NewGroupApp extends PhoneApp {
    private final Set<UUID> selected = new LinkedHashSet<>();

    @Override
    public String id() {
        return "messages";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.messages.new_group_title"), null);
        col.addChild(field("name", w, Component.translatable("smartphone.messages.group_name"), 24, this::create));
        UiKit.Button create = new UiKit.Button(w, 18,
                Component.translatable("smartphone.messages.create_group", selected.size()), UiKit.Style.FILLED, UiKit.BLUE, this::create);
        create.setEnabled(!selected.isEmpty());
        col.addChild(create);
        UiKit.ScrollList list = list("members", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        if (ClientPhoneState.contacts != null) {
            for (S2C.ContactEntry c : ClientPhoneState.contacts) {
                boolean on = selected.contains(c.uuid());
                rows.add(new UiKit.ListRow((g, x, y, size) -> check(g, x, y, size, on), Component.literal(c.name()), null, null, 0, () -> {
                    if (!selected.remove(c.uuid())) selected.add(c.uuid());
                    phone.refresh();
                }));
            }
        }
        list.rows(rows);
    }

    private static void check(GuiGraphicsExtractor g, int x, int y, int size, boolean on) {
        int r = size / 2 - 1;
        UiKit.circle(g, x + size / 2, y + size / 2, r, on ? UiKit.BLUE : UiKit.SURFACE_3);
        if (!on) UiKit.circle(g, x + size / 2, y + size / 2, r - 1, UiKit.SURFACE);
        else UiKit.textCentered(g, Component.literal("✓"), x + size / 2 + 1, y + size / 2 - 4, UiKit.TEXT);
    }

    private void create() {
        String name = text("name");
        if (name.isEmpty() || selected.isEmpty()) return;
        ClientPlayNetworking.send(new C2S.GroupCreate(name, List.copyOf(selected)));
        phone.back();
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.CONTACTS) phone.refresh();
    }
}
