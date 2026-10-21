package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.Conv;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Picks a contact (and optionally a group). The callback receives the conversation and its name. */
public class ContactPickerApp extends PhoneApp {
    private final Component title;
    private final boolean includeGroups;
    private final Predicate<UUID> filter;
    private final BiConsumer<Conv, String> onPick;
    private boolean requested;

    public ContactPickerApp(Component title, boolean includeGroups, Predicate<UUID> filter, BiConsumer<Conv, String> onPick) {
        this.title = title;
        this.includeGroups = includeGroups;
        this.filter = filter;
        this.onPick = onPick;
    }

    @Override
    public String id() {
        return "picker";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, title, null);
        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(C2S.ContactsRequest.INSTANCE);
        }
        UiKit.ScrollList list = list("pick", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        if (includeGroups) {
            for (S2C.GroupEntry g : ClientPhoneState.groups) {
                rows.add(new UiKit.ListRow(UiKit.avatarOf(g.name(), true), Component.literal(g.name()), null, null, 0,
                        () -> pick(Conv.group(g.id()), g.name())));
            }
        }
        if (ClientPhoneState.contacts != null) {
            for (S2C.ContactEntry c : ClientPhoneState.contacts) {
                if (filter != null && !filter.test(c.uuid())) continue;
                rows.add(new UiKit.ListRow(UiKit.avatarOf(c.name(), false), Component.literal(c.name()), null, null, 0,
                        () -> pick(Conv.direct(c.uuid()), c.name())));
            }
        }
        if (rows.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.messages.empty"), UiKit.TEXT_2));
        list.rows(rows);
    }

    private void pick(Conv conv, String name) {
        phone.back();
        onPick.accept(conv, name);
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.CONTACTS) phone.refresh();
    }
}
