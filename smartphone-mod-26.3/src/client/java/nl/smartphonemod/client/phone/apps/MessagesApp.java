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

/** Conversations: group chats first, then every player that has played on the server. */
public class MessagesApp extends PhoneApp {
    private boolean requested;

    @Override
    public String id() {
        return "messages";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.messages"),
                new UiKit.Button(48, 14, Component.translatable("smartphone.messages.new_group"), UiKit.Style.TINTED, UiKit.BLUE,
                        () -> phone.open(new NewGroupApp())));
        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(C2S.ContactsRequest.INSTANCE);
        }
        // Search: a dropdown of online players and contacts; picking one opens the chat.
        col.addChild(field("search", w, Component.translatable("smartphone.messages.search"), 16, this::openFirstMatch)
                .suggestPlayers(this::openChatWith));
        UiKit.ScrollList list = list("contacts", 3, true);
        col.addChild(list);

        List<UiKit.Row> rows = new ArrayList<>();
        var contacts = ClientPhoneState.contacts;
        if (contacts == null) {
            rows.add(UiKit.textRow(Component.translatable("smartphone.loading"), UiKit.TEXT_2));
        } else {
            if (!ClientPhoneState.groups.isEmpty()) {
                rows.add(UiKit.headerRow(Component.translatable("smartphone.messages.groups")));
                for (S2C.GroupEntry g : ClientPhoneState.groups) {
                    rows.add(new UiKit.ListRow(UiKit.avatarOf(g.name(), true), Component.literal(g.name()),
                            Component.translatable("smartphone.messages.members", g.members()), null, 0,
                            () -> phone.open(new ChatApp(Conv.group(g.id()), g.name()))).badge(g.unread()));
                }
                rows.add(UiKit.headerRow(Component.translatable("smartphone.messages.people")));
            }
            if (contacts.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.messages.empty"), UiKit.TEXT_2));
            // Unread first, then who is online, then the rest (the server already sorts by last seen).
            List<S2C.ContactEntry> sorted = new ArrayList<>(contacts);
            sorted.sort(java.util.Comparator.comparing((S2C.ContactEntry c) -> c.unread() == 0).thenComparing(c -> !c.online()));
            for (S2C.ContactEntry c : sorted) {
                Component sub = Component.translatable(c.online() ? "smartphone.messages.online" : "smartphone.messages.offline")
                        .append(c.friend() ? Component.literal("  ★").withColor(UiKit.YELLOW) : Component.empty());
                rows.add(new UiKit.ListRow(UiKit.avatarOf(c.name(), false), Component.literal(c.name()), sub,
                        c.online() ? Component.literal("●") : null, UiKit.GREEN,
                        () -> phone.open(new ChatApp(Conv.direct(c.uuid()), c.name()))).badge(c.unread()));
            }
        }
        list.rows(rows);
    }

    private void openChatWith(String name) {
        for (var e : nl.smartphonemod.client.PlayerNames.search(name, 50)) {
            if (e.name().equalsIgnoreCase(name)) {
                fields.remove("search");
                phone.open(new ChatApp(Conv.direct(e.uuid()), e.name()));
                return;
            }
        }
    }

    private void openFirstMatch() {
        var matches = nl.smartphonemod.client.PlayerNames.search(text("search"), 1);
        if (!matches.isEmpty()) openChatWith(matches.get(0).name());
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.NEW_MESSAGE) ClientPlayNetworking.send(C2S.ContactsRequest.INSTANCE);
        else if (update == PhoneScreen.Update.CONTACTS || update == PhoneScreen.Update.UNREAD) phone.refresh();
    }
}
